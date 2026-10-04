//! Android-hosted upstream controller. ORT is supplied by a synchronous host callback.
//! All handles belong to the creating simulation thread; no pointer crosses the public ABI.
#![allow(dead_code)] // Upstream modules expose hardware helpers unused by this body.
extern crate self as duck_control;
#[path = "../../../../Pollen/microduck/duck-control/src/model.rs"]
pub mod model;
#[path = "../../../../Pollen/microduck/duck-control/src/imu.rs"]
pub mod imu;
#[path = "../../../../Pollen/microduck/duck-control/src/io.rs"]
pub mod io;
#[path = "../../../../Pollen/microduck/duck-control/src/obs.rs"]
pub mod obs;
#[path = "../../../../Pollen/microduck/duck-control/src/safety.rs"]
pub mod safety;
include!(concat!(env!("OUT_DIR"),"/control_module.rs"));
pub mod policy;
pub use io::Sensors;
use std::{cell::RefCell, collections::HashMap, ffi::c_void, rc::Rc, time::Duration};
use std::sync::atomic::{AtomicU64,Ordering};
use control::{Controller,SkillTuning,Tuning};
use io::{RobotIo,JointTargets,IoError,SlowSensors};
use safety::{Safety,SafetyConfig};

pub type Infer = unsafe extern "C" fn(*mut c_void,i32,*const f32,*mut f32)->i32;
#[derive(Clone,Copy)]
pub struct Inference { callback: Infer, context: *mut c_void }
#[derive(Default)]
struct Body { sensors: Sensors, targets: [f64;15], gain:u16 }
struct BodyIo(Rc<RefCell<Body>>);
impl RobotIo for BodyIo {
    fn read(&mut self)->io::Result<Sensors>{Ok(self.0.borrow().sensors)}
    fn write(&mut self,t:&JointTargets)->io::Result<()>{self.0.borrow_mut().targets=t.positions;Ok(())}
    fn set_gain(&mut self,g:u16)->io::Result<()>{self.0.borrow_mut().gain=g;Ok(())}
    fn set_torque(&mut self,_:bool)->io::Result<()>{Ok(())}
    fn reboot(&mut self,_:u8)->io::Result<()>{Err(IoError::Simulated)}
    fn slow_sensors(&mut self)->io::Result<SlowSensors>{Err(IoError::Simulated)}
    fn measures_load(&self)->bool{false}
}
struct Core {
    controller:Controller, inference:Rc<RefCell<Option<Inference>>>,
    body:Rc<RefCell<Body>>, safety:Safety<BodyIo>, filter:bool,
}
impl Core {
    fn controller(inference:Rc<RefCell<Option<Inference>>>,filter:bool)->Controller {
        let mut tuning=Tuning::default();
        if !filter {tuning.head_lowpass=None;tuning.legs_lowpass=None;}
        let roll=serde_json::from_str(r#"{"name":"roulade","duration":2.0,"chain":false,"params":{"action_scale":1.0,"gain_ratio":1.0}}"#).unwrap();
        Controller::new(policy::Policy(inference),tuning,SkillTuning{skills:vec![roll],..SkillTuning::default()})
    }
    fn new(filter:bool)->Self {
        let inference=Rc::new(RefCell::new(None));let body=Rc::new(RefCell::new(Body::default()));
        Self {controller:Self::controller(inference.clone(),filter),inference,
            safety:Safety::new(BodyIo(body.clone()),SafetyConfig::default()),body,filter}
    }
    fn filter(&mut self,filter:bool){
        if self.filter!=filter {
            let mut next=Self::controller(self.inference.clone(),filter);
            next.carry_over(&self.controller);self.controller=next;self.filter=filter;
        }
    }
}
thread_local! {static CORES:RefCell<HashMap<u64,Core>>=RefCell::new(HashMap::new());}
static NEXT:AtomicU64=AtomicU64::new(1);
fn with_core(id:u64,f:impl FnOnce(&mut Core)->Result<(),()>)->i32 {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(||CORES.with(|all|{
        let mut all=all.try_borrow_mut().map_err(|_|())?;
        f(all.get_mut(&id).ok_or(())?)
    }))).ok().and_then(Result::ok).map_or(-1,|_|0)
}
#[unsafe(no_mangle)]
pub extern "C" fn duck_core_open()->u64 {
    std::panic::catch_unwind(||CORES.with(|all|{
        let id=NEXT.fetch_add(1,Ordering::Relaxed);all.borrow_mut().insert(id,Core::new(true));id
    })).unwrap_or(0)
}
#[unsafe(no_mangle)]
pub extern "C" fn duck_core_close(id:u64)->i32 {
    CORES.with(|all|all.try_borrow_mut().ok().and_then(|mut a|a.remove(&id))).map_or(-1,|_|0)
}
/// 0 reset all episode state; 1 begin non-chaining roll; 2 cancel skill, preserving feedback.
#[unsafe(no_mangle)]
pub extern "C" fn duck_core_command(id:u64,op:i32)->i32 {
    with_core(id,|c|{match op {
        0=>*c=Core::new(c.filter),
        1=>c.controller.android_start_roll().map_err(|_|())?,
        2=>c.controller.android_cancel_skill(),
        _=>return Err(())
    };Ok(())})
}
/// Input: pos15, vel15, gyro3, gravity3, quat4. Output: target15, kp, net, fallen.
/// Caller supplies valid buffers of exactly these sizes; JNI validates Java array widths.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn duck_core_tick(id:u64,sensors:*const f64,twist:*const f64,
    filtered:i32,callback:Option<Infer>,context:*mut c_void,output:*mut f64)->i32 {
    if sensors.is_null()||twist.is_null()||output.is_null()||callback.is_none(){return -1;}
    let input=unsafe{std::slice::from_raw_parts(sensors,40)};
    let twist=unsafe{std::slice::from_raw_parts(twist,3)};
    if input.iter().chain(twist).any(|v|!v.is_finite()){return -1;}
    let result=with_core(id,|c|{
        c.filter(filtered!=0);
        let s=Sensors{positions:input[..15].try_into().unwrap(),velocities:input[15..30].try_into().unwrap(),
            imu:imu::ImuData{gyro:input[30..33].try_into().unwrap(),gravity:input[33..36].try_into().unwrap(),quat:input[36..40].try_into().unwrap()},..Sensors::default()};
        c.body.borrow_mut().sensors=s;c.safety.observe(&s,Duration::from_millis(20));
        // The local command persists until a UI mutation; remote lease is owned by Android.
        let (command,_)=c.safety.gate(obs::Command{twist:twist.try_into().unwrap(),..obs::Command::default()},Duration::ZERO);
        *c.inference.borrow_mut()=Some(Inference{callback:callback.unwrap(),context});
        let step=c.controller.step(&s,&command,false,0.02,1.0);
        *c.inference.borrow_mut()=None;
        let step=step.map_err(|_|())?;
        c.safety.apply(step.targets,s.positions,step.gain).map_err(|_|())?;
        let body=c.body.borrow();
        let out=unsafe{std::slice::from_raw_parts_mut(output,18)};
        out[..15].copy_from_slice(&body.targets);out[15]=body.gain.into();
        out[16]=match step.label.as_ref(){"stand"=>0.,"walk"=>1.,"roulade"=>2.,_=>return Err(())};
        out[17]=if c.safety.fallen(){1.}else{0.};Ok(())
    });
    if result!=0 { // An inference fault invalidates episode state; caller pauses the body.
        let _=duck_core_command(id,0);
    }
    result
}
