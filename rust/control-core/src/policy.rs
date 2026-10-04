//! Feedforward ORT adapter, not a second implementation of the upstream controller.
use std::{cell::RefCell,rc::Rc};
use crate::{Inference,obs::{Observation,ACTION_LEN}};
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Net {Walk,Stand,SitStand,GroundPick,Skill(usize)}
#[derive(Debug,thiserror::Error)]
#[error("Android ONNX callback failed or unsupported network requested")]
pub struct PolicyError;
pub struct Policy(pub(crate) Rc<RefCell<Option<Inference>>>);
impl Policy {
    pub fn will_stand(&self,magnitude:f64)->bool{magnitude<=0.05}
    pub fn has_standing(&self)->bool{true}
    pub fn has_sitstand(&self)->bool{false}
    pub fn has_ground_pick(&self)->bool{false}
    pub fn skill_count(&self)->usize{1}
    pub fn reset(&mut self){} // Three feedforward sessions have no hidden state.
    pub fn carry_over(&mut self,_:&Self){}
    pub fn infer(&mut self,obs:&Observation,net:Net)->Result<[f32;ACTION_LEN],PolicyError>{
        let index=match net{Net::Stand=>0,Net::Walk=>1,Net::Skill(0)=>2,_=>return Err(PolicyError)};
        let host=self.0.borrow().ok_or(PolicyError)?;
        let mut action=[f32::NAN;ACTION_LEN];
        let result=unsafe{(host.callback)(host.context,index,obs.as_slice().as_ptr(),action.as_mut_ptr())};
        if result!=0 || action.iter().any(|v|!v.is_finite()){return Err(PolicyError)}
        Ok(action)
    }
}
