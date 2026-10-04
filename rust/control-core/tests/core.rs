use microduck_android_core::*;
use std::ffi::c_void;
struct Callback { observations:Vec<Vec<f32>>,nets:Vec<i32>,action:f32,fail:bool }
unsafe extern "C" fn infer(ctx:*mut c_void,net:i32,obs:*const f32,out:*mut f32)->i32 {
    let c=unsafe{&mut *(ctx as *mut Callback)};
    c.nets.push(net);c.observations.push(unsafe{std::slice::from_raw_parts(obs,61)}.to_vec());
    unsafe{std::slice::from_raw_parts_mut(out,14)}.fill(c.action);
    if c.fail{-1}else{0}
}
fn home()->[f64;40]{let mut s=[0.;40];s[..15].copy_from_slice(&model::DEFAULT_POSITION);s[35]=-1.;s[36]=1.;s}
fn callback()->Callback{Callback{observations:vec![],nets:vec![],action:0.,fail:false}}
fn tick(id:u64,s:&[f64;40],vx:f64,c:&mut Callback)->Result<[f64;18],i32>{
    let mut out=[0.;18];let code=unsafe{duck_core_tick(id,s.as_ptr(),[vx,0.,0.].as_ptr(),1,Some(infer),c as *mut _ as *mut c_void,out.as_mut_ptr())};
    if code==0{Ok(out)}else{Err(code)}
}
#[test]
fn observation_feedback_selection_filter_and_gain(){
    let id=duck_core_open();let s=home();let mut c=callback();c.action=0.2;
    let stand=tick(id,&s,0.,&mut c).unwrap();assert_eq!(stand[15],160.);assert_eq!(c.nets,[0]);
    assert_eq!(c.observations[0][5],-1.);assert!(c.observations[0][6..].iter().all(|x|*x==0.));
    c.action=0.4;let walk=tick(id,&s,0.3,&mut c).unwrap();assert_eq!(walk[15],200.);assert_eq!(c.nets,[0,1]);
    assert!(c.observations[1][34..48].iter().all(|x|*x==0.2));
    assert!((walk[0]-(0.7*0.9*0.4f32 as f64+0.3*0.2f32 as f64)).abs()<1e-10);
    assert!((walk[5]-(model::DEFAULT_POSITION[5]+0.5*0.9*0.4f32 as f64+0.5*0.2f32 as f64)).abs()<1e-10);
    assert_eq!(walk[9],0.);assert_eq!(duck_core_close(id),0);
}
#[test]
fn roll_exactly_100_ticks_repeated_command_no_extension_and_cancel_preserves_feedback(){
    let id=duck_core_open();let mut c=callback();c.action=0.1;let s=home();
    assert_eq!(duck_core_command(id,1),0);
    for _ in 0..100{assert_eq!(duck_core_command(id,1),0);let out=tick(id,&s,0.,&mut c).unwrap();assert_eq!(out[16],2.);assert_eq!(out[15],200.);}
    assert_eq!(tick(id,&s,0.,&mut c).unwrap()[16],0.);
    assert_eq!(duck_core_command(id,1),0);tick(id,&s,0.,&mut c).unwrap();
    assert_eq!(duck_core_command(id,2),0);tick(id,&s,0.3,&mut c).unwrap();
    assert!(c.observations.last().unwrap()[34..48].iter().all(|x|*x==0.1));
    assert_eq!(duck_core_command(id,0),0);tick(id,&s,0.,&mut c).unwrap();
    assert!(c.observations.last().unwrap()[34..48].iter().all(|x|*x==0.));duck_core_close(id);
}
#[test]
fn faults_and_thread_ownership(){
    let id=duck_core_open();let s=home();let mut c=callback();c.fail=true;
    assert!(tick(id,&s,0.,&mut c).is_err());c.fail=false;c.action=f32::NAN;
    assert!(tick(id,&s,0.,&mut c).is_err());c.action=0.;
    let mut bad=s;bad[4]=f64::INFINITY;assert!(tick(id,&bad,0.,&mut c).is_err());
    assert_eq!(std::thread::spawn(move||duck_core_command(id,0)).join().unwrap(),-1);
    tick(id,&s,0.,&mut c).unwrap();assert_eq!(duck_core_close(id),0);assert_eq!(duck_core_command(id,0),-1);
}
#[test]
fn safety_clamps_unreasonable_targets_and_reports_fall_without_aborting_roll(){
    let id=duck_core_open();let mut c=callback();c.action=100.;let s=home();
    let out=tick(id,&s,0.,&mut c).unwrap();assert!(out[..15].iter().all(|x|x.abs()<=std::f64::consts::PI));
    c.action=0.;let mut fallen=s;fallen[35]=1.;duck_core_command(id,1);
    let mut out=[0.;18];for _ in 0..20{out=tick(id,&fallen,0.,&mut c).unwrap();}
    assert_eq!(out[17],1.);assert_eq!(out[16],2.);duck_core_close(id);
}

#[test]
fn roll_can_restart_immediately_after_last_completed_tick(){
    let id=duck_core_open();let s=home();let mut c=callback();
    for _ in 0..2 {
        assert_eq!(duck_core_command(id,1),0);
        for _ in 0..100 {assert_eq!(tick(id,&s,0.,&mut c).unwrap()[16],2.);}
    }
    assert_eq!(tick(id,&s,0.,&mut c).unwrap()[16],0.);duck_core_close(id);
}
