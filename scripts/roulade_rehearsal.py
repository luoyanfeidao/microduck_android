#!/usr/bin/env python3
"""Rehearse the bundled forward roll with Android XML servos (CPU, no display).

This measures actual motion; finite inference alone does not establish a successful roll.
"""
from pathlib import Path
import argparse
import hashlib
import json
import mujoco
import numpy as np
import onnxruntime as ort

ROOT=Path(__file__).resolve().parents[1]
HOME=np.array([0,-.0873,-.4579,-.0049,.4530,.3491,.3491,0,0,0,.0873,.4579,.0049,-.4530])
NAMES='left_hip_yaw left_hip_roll left_hip_pitch left_knee left_ankle neck_pitch head_pitch head_yaw head_roll right_hip_yaw right_hip_roll right_hip_pitch right_knee right_ankle'.split()

def rehearse(duration=2.0):
    roll_ticks=round(duration*50)
    if duration<=0 or roll_ticks<1 or abs(duration-roll_ticks*.02)>1e-8:
        raise ValueError('Duration must be a positive multiple of 20 ms')
    options=ort.SessionOptions(); options.intra_op_num_threads=1; options.inter_op_num_threads=1
    sessions={name:ort.InferenceSession(str(ROOT/'app/src/main/assets/policies'/f'{name}.onnx'),sess_options=options)
              for name in ('alpha_stand','roulade')}
    for session in sessions.values():
        assert [(x.name,x.shape,x.type) for x in session.get_inputs()]==[('obs',[1,61],'tensor(float)')]
        assert [(x.name,x.shape,x.type) for x in session.get_outputs()]==[('actions',[1,14],'tensor(float)')]
        assert session.get_modelmeta().custom_metadata_map['joint_names'].split(',')==NAMES
    records=[]
    for settled in (False,True):
        for filtering in (True,False):
            m=mujoco.MjModel.from_xml_path(str(ROOT/'app/src/main/assets/model/scene.xml')); m.opt.timestep=.005
            d=mujoco.MjData(m)
            joints=np.array([m.joint(name).id for name in NAMES]); qa=m.jnt_qposadr[joints]; va=m.jnt_dofadr[joints]
            actuators=np.array([np.where(m.actuator_trnid[:,0]==j)[0][0] for j in joints])
            gains=m.actuator_gainprm[actuators,0].copy()
            d.qpos[:7]=[0,0,.125,1,0,0,0]; d.qpos[qa]=HOME; d.ctrl[actuators]=HOME
            mujoco.mj_forward(m,d)
            previous=np.zeros(14,np.float32); last_target=None; rotation=np.empty(9)
            angle=0.; max_angle=0.; max_lateral=0.; min_height=float('inf')
            projected_angle=0.; max_projected=0.; previous_pitch=None; head_contact=False
            roll_trace=[]; floor=m.geom('floor').id; head=m.body('jaw_soft').id
            phases=[('alpha_stand',50)] if settled else []
            phases += [('roulade',roll_ticks),('alpha_stand',150)]
            roll_end=None
            for policy,phase_ticks in phases:
                for control_tick in range(phase_ticks):
                    mujoco.mju_quat2Mat(rotation,d.qpos[3:7])
                    obs=np.r_[d.qvel[3:6],-rotation[6:9],d.qpos[qa]-HOME,d.qvel[va],previous,np.zeros(13)].astype(np.float32)
                    action=sessions[policy].run(['actions'],{'obs':obs.reshape(1,61)})[0][0]
                    assert action.shape==(14,) and np.isfinite(action).all()
                    target=HOME+action
                    if filtering and last_target is not None:
                        alpha=np.full(14,.7); alpha[5:9]=.5; target=alpha*target+(1-alpha)*last_target
                    last_target=target.copy(); previous=action.copy(); d.ctrl[actuators]=target
                    gain=gains*(1. if policy=='roulade' else .8)
                    m.actuator_gainprm[actuators,0]=gain; m.actuator_biasprm[actuators,1]=-gain
                    for _ in range(4):
                        if policy=='roulade' or roll_end is not None:
                            angle+=float(d.qvel[4])*.005
                            max_angle=max(max_angle,angle); max_lateral=max(max_lateral,abs(float(d.qvel[3])))
                        mujoco.mj_step(m,d)
                        assert np.isfinite(d.qpos).all() and np.isfinite(d.qvel).all()
                        assert not any(d.warning[i].number for i in (mujoco.mjtWarning.mjWARN_BADQPOS,mujoco.mjtWarning.mjWARN_BADQVEL,mujoco.mjtWarning.mjWARN_BADQACC))
                    mujoco.mj_forward(m,d); min_height=min(min_height,float(d.qpos[2]))
                    if policy=='roulade':
                        mujoco.mju_quat2Mat(rotation,d.qpos[3:7])
                        pitch=float(np.arctan2(-rotation[6],rotation[8]))
                        if previous_pitch is None: projected_angle=pitch
                        else: projected_angle+=float(np.arctan2(np.sin(pitch-previous_pitch),np.cos(pitch-previous_pitch)))
                        previous_pitch=pitch; max_projected=max(max_projected,projected_angle)
                        head_contact |= any((c.geom[0]==floor and m.geom_bodyid[c.geom[1]]==head) or
                                            (c.geom[1]==floor and m.geom_bodyid[c.geom[0]]==head) for c in d.contact)
                        if (control_tick+1)%10==0 or control_tick+1==phase_ticks:
                            roll_trace.append({'roll_s':(control_tick+1)*.02,'pitch_integral_degrees':float(np.rad2deg(angle)),
                                               'projected_pitch_degrees':float(np.rad2deg(projected_angle)),
                                               'root_z':float(d.qpos[2]),'tilt_degrees':float(np.rad2deg(np.arccos(np.clip(rotation[8],-1,1))))})
                if policy=='roulade':
                    roll_end={'root_xyz':d.qpos[:3].tolist(),'quaternion':d.qpos[3:7].tolist(),
                              'pitch_integral_degrees':float(np.rad2deg(angle)),
                              'projected_pitch_degrees':float(np.rad2deg(projected_angle)),
                              'tilt_degrees':float(np.rad2deg(np.arccos(np.clip(rotation[8],-1,1))))}
            mujoco.mju_quat2Mat(rotation,d.qpos[3:7])
            assert roll_trace[-1]['roll_s']==duration
            assert abs(d.time-((1 if settled else 0)+duration+3))<1e-8
            records.append({'initial_state':'standing_policy_1s' if settled else 'HOME','filter':filtering,
                            'roll_ticks':round(duration*50),'recovery_stand_ticks':150,'finite':True,'roll_end':roll_end,
                            'head_touched_floor':bool(head_contact),'max_projected_pitch_degrees':float(np.rad2deg(max_projected)),
                            'roll_trace':roll_trace,
                            'max_forward_pitch_integral_degrees':float(np.rad2deg(max_angle)),
                            'max_lateral_rate_rad_s':max_lateral,'min_root_height_m':min_height,
                            'final_root_xyz':d.qpos[:3].tolist(),'final_quaternion':d.qpos[3:7].tolist(),
                            'final_tilt_degrees':float(np.rad2deg(np.arccos(np.clip(rotation[8],-1,1))))})
    sha=hashlib.sha256((ROOT/'app/src/main/assets/policies/roulade.onnx').read_bytes()).hexdigest()
    return {'scope':'CPU XML servos matching Android action scale/gain/filter; not BAM or device acceptance',
            'mujoco':mujoco.__version__,'onnxruntime':ort.__version__,'roulade_sha256':sha,
            'action_scale':1.0,'gain_ratio':1.0,'duration_s':duration,'command':[0]*13,'rollouts':records,
            'limitations':['Pitch-rate integral describes motion and is not a contact-gated success detector.',
                           'Unwrapped projected pitch can count shoulder rotation; head contact alone does not prove head-top support.',
                           'No guarantee that arbitrary walking/fallen starting states complete a roll.']}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--duration',type=float,default=2.0)
    parser.add_argument('--sweep',action='store_true',help='Compare 1, 1.5, 2, 2.5, 3, 4 and 5 second handoffs')
    parser.add_argument('--report',type=Path)
    args=parser.parse_args()
    result=[rehearse(duration) for duration in (1,1.5,2,2.5,3,4,5)] if args.sweep else rehearse(args.duration)
    target=args.report or ROOT/('verification/host-roulade-duration-sweep.json' if args.sweep else 'verification/host-roulade-m71.json')
    target.write_text(json.dumps(result,indent=2)+'\n')
    for entry in result if args.sweep else [result]:
        print(json.dumps({'duration_s':entry['duration_s'],'rollouts':[
            {'initial_state':r['initial_state'],'filter':r['filter'],
             'handoff_pitch_degrees':r['roll_end']['pitch_integral_degrees'],
             'handoff_projected_pitch_degrees':r['roll_end']['projected_pitch_degrees'],
             'handoff_tilt_degrees':r['roll_end']['tilt_degrees'],'final_tilt_degrees':r['final_tilt_degrees']}
            for r in entry['rollouts']]}))
