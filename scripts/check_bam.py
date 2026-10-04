"""Compare Android's actual C++ BAM port against installed upstream CPU BAM; host only."""
import sys
sys.dont_write_bytecode = True
import argparse
import contextlib
import ctypes as C
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import numpy as np
import mujoco
import bam
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--rl', type=Path, default=ROOT.parent/'Pollen/microduck_rl')
    args = parser.parse_args()
    locked=json.loads((ROOT/'BAM.lock.json').read_text())
    upstream=Path(bam.__file__).parent
    for name,sha in locked['reference_sha256'].items():
        assert hashlib.sha256((upstream/name).read_bytes()).hexdigest()==sha,'BAM reference changed: '+name
    spec = importlib.util.spec_from_file_location('bam_reference', args.rl/'scripts/infer_policy.py')
    ref = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ref)
    package = Path(mujoco.__file__).parent
    lib = package/'libmujoco.3.10.0.dylib'
    report = {'scope': 'Host C++/upstream Python BAM comparison, not Android device validation'}
    with tempfile.TemporaryDirectory(prefix='microduck-bam-') as tmp:
        output = Path(tmp)/'bam-check.dylib'
        subprocess.run(['clang++','-std=c++20','-O2','-dynamiclib','-Wall','-Wextra','-Werror',
            '-I'+str(package/'include'),'-I'+str(args.java_home/'include'),'-I'+str(args.java_home/'include/darwin'),
            str(ROOT/'verification/bam-bridge.cpp'),str(lib),'-o',str(output)],check=True)
        subprocess.run(['install_name_tool','-change','@rpath/mujoco.framework/Versions/A/libmujoco.3.10.0.dylib',str(lib),str(output)],check=True)
        native = C.CDLL(str(output)); ptr=C.c_void_p; dp=C.POINTER(C.c_double); fp=C.POINTER(C.c_float)
        for name, types, result in [
            ('duck_create',[C.c_char_p],ptr),('duck_destroy',[ptr],None),('duck_home',[ptr],None),
            ('duck_mode',[ptr,C.c_int],None),('duck_action',[ptr,fp,C.c_int,C.c_int],None),
            ('duck_targets',[ptr,dp],None),('duck_power',[ptr,C.c_int],None),('duck_gain',[ptr,C.c_int],None),
            ('duck_step',[ptr,C.c_int],None),('duck_values',[ptr,dp],C.c_int),
            ('duck_obs',[ptr,fp,fp,fp],None),('duck_sensors',[ptr],C.c_char_p),
            ('duck_eval',[C.c_double]*7+[dp],None)]:
            fn=getattr(native,name);fn.argtypes=types;fn.restype=result
        model=ref.load_bam_model(200,7.4,None)
        rng=np.random.default_rng(981);maximum=0.;out=np.zeros(2)
        for i in range(1000):
            q,target,motor,external=rng.uniform(-3,3,4)
            velocity=0. if i%7==0 else rng.uniform(-30,30)
            kp=float(rng.choice([0,50,160,200,65535]));volts=rng.uniform(6,8.2)
            model.actuator.kp=kp;model.actuator.vin=volts
            native.duck_eval(target,q,velocity,kp,volts,motor,external,out.ctypes.data_as(dp))
            control=model.actuator.compute_control(target,q,velocity,.005)
            expected=[model.actuator.compute_torque(control,True,q,velocity),model.compute_frictions(motor,external,velocity)[0]]
            maximum=max(maximum,float(np.max(np.abs(out-expected))))
        assert maximum<1e-12, maximum
        report['scalar_cases']=1000;report['scalar_max_abs_error']=maximum
        scene=ROOT/'app/src/main/assets/model/scene.xml'
        handle=native.duck_create(str(scene).encode());assert handle
        def values():
            buf=np.zeros(128);size=native.duck_values(handle,buf.ctypes.data_as(dp));return buf[:size]
        def observation(previous,command):
            obs=np.zeros(61,dtype=np.float32)
            native.duck_obs(handle,previous.ctypes.data_as(fp),command.ctypes.data_as(fp),obs.ctypes.data_as(fp));return obs
        try:
            model=ref.load_bam_model(200,7.4,None)
            with contextlib.redirect_stdout(io.StringIO()):
                m,d,ctrl,names=ref.load_mujoco_with_bam(str(scene),model,.005,.1,6.)
            # Android's existing HOME literals are doubles; align initial state, not float32 round-off.
            home=np.round(ref.DEFAULT_POSE.astype(np.float64),4)
            mujoco.mj_resetData(m,d);d.qpos[2]=.125;d.qpos[ctrl.qpos_indexes]=home
            ctrl.reset(d.qpos);mujoco.mj_forward(m,d);native.duck_home(handle)
            maximum=0.
            # Nonuniform targets exercise every joint and the 15-to-14 wire mapping.
            for tick in range(150):
                targets=np.array(ref.DEFAULT_POSE)+.05*np.sin(tick*.09+np.arange(14))
                wire=np.insert(targets,9,.7).astype(np.float64)
                native.duck_targets(handle,wire.ctypes.data_as(dp));ctrl.q_target[:]=targets
                for _ in range(4):ctrl.update();mujoco.mj_step(m,d)
                mujoco.mj_forward(m,d);native.duck_step(handle,4)
                expected=np.r_[d.qpos,d.qvel,d.ctrl[ctrl.act_indexes],m.dof_frictionloss[ctrl.dof_indexes],m.dof_damping[ctrl.dof_indexes]]
                actual=values()[:-1];error=float(np.max(np.abs(actual-expected)));maximum=max(maximum,error)
                np.testing.assert_allclose(actual,expected,atol=2e-7,rtol=2e-7,err_msg=f'tick {tick}')
            sensors=json.loads(native.duck_sensors(handle));assert len(sensors['positions'])==15
            assert sensors['positions'][9]==0 and 'currents_ma' not in sensors
            np.testing.assert_allclose(np.delete(sensors['positions'],9),d.qpos[ctrl.qpos_indexes],atol=2e-7)
            report['cpu_bam_600_steps_max_abs_error']=maximum
            report['wire_mouth_mapping_and_unavailable_load']=True
            # Restore the XML profile, including armature/friction, then return to a fresh BAM state.
            native.duck_mode(handle,0);xml=mujoco.MjModel.from_xml_path(str(scene))
            vd=xml.jnt_dofadr[xml.actuator_trnid[:,0]]
            np.testing.assert_allclose(values()[xml.nq+xml.nv+14:xml.nq+xml.nv+28],xml.dof_frictionloss[vd],atol=0)
            xml.opt.timestep=.005;xd=mujoco.MjData(xml)
            mujoco.mj_resetDataKeyframe(xml,xd,mujoco.mj_name2id(xml,mujoco.mjtObj.mjOBJ_KEY,'SIT'));mujoco.mj_forward(xml,xd)
            native.duck_step(handle,100)
            for _ in range(100):mujoco.mj_step(xml,xd)
            mujoco.mj_forward(xml,xd)
            np.testing.assert_allclose(values()[:xml.nq+xml.nv],np.r_[xd.qpos,xd.qvel],atol=1e-10,rtol=1e-10)
            native.duck_mode(handle,1);native.duck_home(handle)
            fresh=values().copy();native.duck_step(handle,4);native.duck_home(handle)
            np.testing.assert_array_equal(values(),fresh);report['reset_and_xml_switch']=True
            # The same released body keeps moving under gravity with torque off, not frozen.
            native.duck_power(handle,0);native.duck_step(handle,4)
            assert np.all(values()[xml.nq+xml.nv:xml.nq+xml.nv+14]==0)
            assert not np.array_equal(values()[:xml.nq],fresh[:xml.nq]);report['torque_off_is_limp']=True
            report['policy_rollouts']={}
            for name,mode,vx in [('alpha_stand',0,0.),('alpha_walking',1,.3),('roulade',2,0.)]:
                native.duck_home(handle);previous=np.zeros(14,dtype=np.float32);cmd=np.zeros(13,dtype=np.float32);cmd[0]=vx
                session=ort.InferenceSession(str(ROOT/'app/src/main/assets/policies'/f'{name}.onnx'),providers=['CPUExecutionProvider'])
                steps=100 if mode==2 else 250;tilts=[];angles=[]
                for tick in range(steps):
                    obs=observation(previous,cmd)
                    raw=session.run(None,{session.get_inputs()[0].name:obs[None]})[0].reshape(14).astype(np.float32)
                    native.duck_action(handle,raw.ctypes.data_as(fp),mode,1);native.duck_step(handle,4);previous=raw
                    gravity=json.loads(native.duck_sensors(handle))['imu']['gravity'];tilts.append(float(np.degrees(np.arccos(np.clip(-gravity[2],-1,1)))))
                    angles.append(float(np.arctan2(gravity[0],-gravity[2])))
                    assert np.isfinite(values()).all()
                report['policy_rollouts'][name]={'seconds':steps*.02,'final_trunk_z':json.loads(native.duck_sensors(handle))['trunk_z'],
                    'final_tilt_deg':tilts[-1],'max_tilt_deg':max(tilts),'pitch_travel_deg':float(np.degrees(np.unwrap(angles)[-1]-angles[0])),'finite':True}
                assert tilts[-1]<10 and report['policy_rollouts'][name]['final_trunk_z']>.095
                if mode==2:assert abs(report['policy_rollouts'][name]['pitch_travel_deg'])>330
        finally:native.duck_destroy(handle)
    source_paths=[ROOT/'app/src/main/cpp/bam_actuator.h',ROOT/'app/src/main/cpp/native_sim.cpp',ROOT/'verification/bam-bridge.cpp',Path(__file__),ROOT/'BAM.lock.json']
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in source_paths}
    upstream=Path(bam.__file__).parent
    report['upstream_bam_sha256']={p:hashlib.sha256((upstream/p).read_bytes()).hexdigest() for p in
        ['model.py','mujoco.py','actuator.py','dynamixel/actuator.py','params/xl330/m6.json']}
    (ROOT/'verification/m9-bam-checks.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
