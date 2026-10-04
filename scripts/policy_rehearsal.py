#!/usr/bin/env python3
"""CPU XML-servo reference: generate Android ONNX smoke fixtures and rehearse M2.

Uses the runtime's home/observation/action conventions. This is not BAM training physics.
Run after prepare_assets.py with the RL repository's Python environment.
"""
from pathlib import Path
import hashlib,json,time
import mujoco
import numpy as np
import onnxruntime as ort

ROOT=Path(__file__).resolve().parents[1]
HOME=np.array([0,-.0873,-.4579,-.0049,.4530,.3491,.3491,0,0,0,.0873,.4579,.0049,-.4530])
NAMES='left_hip_yaw left_hip_roll left_hip_pitch left_knee left_ankle neck_pitch head_pitch head_yaw head_roll right_hip_yaw right_hip_roll right_hip_pitch right_knee right_ankle'.split()
options=ort.SessionOptions(); options.intra_op_num_threads=1; options.inter_op_num_threads=1
sessions={name:ort.InferenceSession(str(ROOT/'app/src/main/assets/policies'/f'{name}.onnx'),sess_options=options) for name in ('alpha_stand','alpha_walking','roulade')}
tests=[]
for name,s in sessions.items():
    assert [(n.name,n.shape,n.type) for n in s.get_inputs()]==[('obs',[1,61],'tensor(float)')]
    assert [(n.name,n.shape,n.type) for n in s.get_outputs()]==[('actions',[1,14],'tensor(float)')]
    assert s.get_modelmeta().custom_metadata_map['joint_names'].split(',')==NAMES
    sha=hashlib.sha256((ROOT/'app/src/main/assets/policies'/f'{name}.onnx').read_bytes()).hexdigest()
    for obs in [np.r_[np.zeros(5),-1,np.zeros(55)],np.r_[.1,-.2,.3,.2,-.1,-np.sqrt(.95),np.linspace(-.1,.1,14),np.linspace(-1,1,14),np.linspace(-.5,.5,14),.15,0,.1,np.zeros(10)]]:
        obs=obs.astype(np.float32); assert obs.shape==(61,)
        action=s.run(['actions'],{'obs':obs.reshape(1,61)})[0][0]
        assert np.isfinite(action).all()
        tests.append({'policy':name,'sha256':sha,'observation':obs.tolist(),'actions':action.tolist()})
(ROOT/'app/src/main/assets/policies/smoke.json').write_text(json.dumps({'desktop_ort':ort.__version__,'tests':tests},indent=2)+'\n')

# Tilted, moving state exercises gravity rotation, local gyro, joint order, raw
# feedback, command slots and the runtime's forced-zero body x/y/yaw entries.
fixture_m=mujoco.MjModel.from_xml_path(str(ROOT/'app/src/main/assets/model/scene.xml'))
fixture_d=mujoco.MjData(fixture_m)
qa=np.array([fixture_m.jnt_qposadr[mujoco.mj_name2id(fixture_m,mujoco.mjtObj.mjOBJ_JOINT,n)] for n in NAMES])
va=np.array([fixture_m.jnt_dofadr[mujoco.mj_name2id(fixture_m,mujoco.mjtObj.mjOBJ_JOINT,n)] for n in NAMES])
q=np.array([.9,.1,-.2,.3]); q/=np.linalg.norm(q)
fixture_d.qpos[:7]=[.2,-.1,.125,*q]; fixture_d.qpos[qa]=HOME+np.linspace(-.12,.12,14)
fixture_d.qvel[:6]=[.1,-.2,.3,.4,-.5,.6]; fixture_d.qvel[va]=np.linspace(-1,1,14)
mujoco.mj_forward(fixture_m,fixture_d)
rotation=np.empty(9); mujoco.mju_quat2Mat(rotation,q)
previous=np.linspace(-.5,.5,14,dtype=np.float32); command=np.linspace(-.3,.3,13,dtype=np.float32)
obs=np.r_[fixture_d.qvel[3:6],-rotation[6:9],fixture_d.qpos[qa]-HOME,fixture_d.qvel[va],previous,command].astype(np.float32)
obs[[55,56,60]]=0
action=np.linspace(-.7,.7,14,dtype=np.float32)
values=np.r_[fixture_d.qpos,fixture_d.qvel,previous,command,obs,action]
(ROOT/'verification/native-contract-fixture.txt').write_text('\n'.join(format(float(v),'.17g') for v in values)+'\n')

records=[]
for vx in (0.,.15,.3):
  for filtering in (True,False):
    m=mujoco.MjModel.from_xml_path(str(ROOT/'app/src/main/assets/model/scene.xml')); m.opt.timestep=.005
    d=mujoco.MjData(m); root=mujoco.mj_name2id(m,mujoco.mjtObj.mjOBJ_JOINT,'trunk_base_freejoint')
    rpos=m.jnt_qposadr[root]; rdof=m.jnt_dofadr[root]
    joints=np.array([mujoco.mj_name2id(m,mujoco.mjtObj.mjOBJ_JOINT,n) for n in NAMES]); assert (joints>=0).all()
    actuators=np.array([int(np.where((m.actuator_trnid[:,0]==j)&(m.actuator_trntype==mujoco.mjtTrn.mjTRN_JOINT))[0][0]) for j in joints])
    qa=m.jnt_qposadr[joints]; va=m.jnt_dofadr[joints]; gains=m.actuator_gainprm[actuators,0].copy()
    d.qpos[rpos:rpos+7]=[0,0,.125,1,0,0,0]; d.qpos[qa]=HOME; d.ctrl[actuators]=HOME
    mujoco.mj_forward(m,d); last=np.zeros(14,np.float32); prev=None; heights=[]; actions=[]
    name='alpha_stand' if vx<.05 else 'alpha_walking'; start=time.perf_counter()
    first_obs=None; first_action=None
    for tick in range(500):
        rot=np.empty(9); mujoco.mju_quat2Mat(rot,d.qpos[rpos+3:rpos+7])
        command=np.zeros(13); command[0]=vx
        obs=np.r_[d.qvel[rdof+3:rdof+6],-rot[6:9],d.qpos[qa]-HOME,d.qvel[va],last,command].astype(np.float32)
        action=sessions[name].run(['actions'],{'obs':obs.reshape(1,61)})[0][0]
        assert np.isfinite(action).all()
        if tick==0: first_obs=obs.tolist(); first_action=action.tolist()
        target=HOME+action*(1 if vx<.05 else .9)
        if filtering and prev is not None:
            alpha=np.full(14,.7); alpha[5:9]=.5; target=alpha*target+(1-alpha)*prev
        prev=target.copy(); last=action.copy(); d.ctrl[actuators]=target
        gain=gains*(.8 if vx<.05 else 1.)
        m.actuator_gainprm[actuators,0]=gain; m.actuator_biasprm[actuators,1]=-gain
        for _ in range(4): mujoco.mj_step(m,d)
        mujoco.mj_forward(m,d)
        assert np.isfinite(d.qpos).all() and np.isfinite(d.qvel).all()
        heights.append(float(d.qpos[rpos+2])); actions.append(action.tolist())
    wall=time.perf_counter()-start
    record={'policy':name,'vx':vx,'filter':filtering,'ticks':500,'sim_time':float(d.time),'finite':True,'wall_seconds':wall,
      'final_root_xyz':d.qpos[rpos:rpos+3].tolist(),'final_quaternion':d.qpos[rpos+3:rpos+7].tolist(),
      'min_height':min(heights),'max_abs_raw_action':float(np.max(np.abs(actions))),
      'first_observation':first_obs,'first_action':first_action,'qpos':d.qpos.tolist(),'qvel':d.qvel.tolist()}
    records.append(record)
    print(json.dumps({k:v for k,v in record.items() if k not in ('first_observation','first_action','qpos','qvel')}))
(ROOT/'verification/host-policy-rehearsal.json').write_text(json.dumps({'mujoco':mujoco.__version__,'onnxruntime':ort.__version__,'scope':'CPU XML servos, not BAM','rollouts':records},indent=2)+'\n')
