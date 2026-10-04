#!/usr/bin/env python3
"""Diagnose head recovery with the actual policies and Android XML servos.

Host comparisons are candidates, not phone acceptance or a reason to override a policy.
"""
import hashlib
import json
import mujoco
import numpy as np
import onnxruntime as ort
from roulade_rehearsal import ROOT, HOME, NAMES


def run(roll_duration, recovery_head_alpha=.5):
    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    sessions = {name: ort.InferenceSession(str(ROOT / 'app/src/main/assets/policies' / f'{name}.onnx'),
                                         sess_options=options) for name in ('alpha_stand', 'roulade')}
    m = mujoco.MjModel.from_xml_path(str(ROOT / 'app/src/main/assets/model/scene.xml'))
    m.opt.timestep = .005
    d = mujoco.MjData(m)
    joints = np.array([m.joint(name).id for name in NAMES])
    qa, va = m.jnt_qposadr[joints], m.jnt_dofadr[joints]
    actuators = np.array([np.where(m.actuator_trnid[:, 0] == joint)[0][0] for joint in joints])
    gains = m.actuator_gainprm[actuators, 0].copy()
    d.qpos[:7] = [0, 0, .125, 1, 0, 0, 0]
    d.qpos[qa] = HOME
    d.ctrl[actuators] = HOME
    mujoco.mj_forward(m, d)
    previous = np.zeros(14, np.float32)
    last_target = None
    rotation = np.empty(9)
    camera = m.camera('head_camera').id
    history = []
    for phase, policy, ticks in [('initial_stand', 'alpha_stand', 50),
                                 ('roll', 'roulade', round(roll_duration * 50)),
                                 ('recovery', 'alpha_stand', 150)]:
        for tick in range(ticks):
            mujoco.mju_quat2Mat(rotation, d.qpos[3:7])
            obs = np.r_[d.qvel[3:6], -rotation[6:9], d.qpos[qa] - HOME,
                        d.qvel[va], previous, np.zeros(13)].astype(np.float32)
            action = sessions[policy].run(['actions'], {'obs': obs.reshape(1, 61)})[0][0]
            raw_target = HOME + action
            target = raw_target.copy()
            alpha = np.full(14, .7)
            alpha[5:9] = recovery_head_alpha if phase == 'recovery' else .5
            if last_target is not None:
                target = alpha * target + (1 - alpha) * last_target
            last_target, previous = target.copy(), action.copy()
            d.ctrl[actuators] = target
            gain = gains * (1 if policy == 'roulade' else .8)
            m.actuator_gainprm[actuators, 0] = gain
            m.actuator_biasprm[actuators, 1] = -gain
            for _ in range(4):
                mujoco.mj_step(m, d)
                assert np.isfinite(d.qpos).all() and np.isfinite(d.qvel).all()
                assert not any(d.warning[i].number for i in (mujoco.mjtWarning.mjWARN_BADQPOS,
                    mujoco.mjtWarning.mjWARN_BADQVEL, mujoco.mjtWarning.mjWARN_BADQACC))
            mujoco.mj_forward(m, d)
            mujoco.mju_quat2Mat(rotation, d.qpos[3:7])
            # Android's local-X 180 degree correction makes forward = original camera +Z.
            forward = d.cam_xmat[camera].reshape(3, 3)[:, 2]
            history.append({'phase': phase, 'phase_s': round((tick + 1) * .02, 2),
                'tilt_degrees': float(np.rad2deg(np.arccos(np.clip(rotation[8], -1, 1)))),
                'head_camera_elevation_degrees': float(np.rad2deg(np.arcsin(np.clip(forward[2], -1, 1)))),
                'head_qpos_rad': d.qpos[qa[5:9]].tolist(),
                'head_raw_target_rad': raw_target[5:9].tolist(),
                'head_filtered_target_rad': target[5:9].tolist(),
                'head_velocity_rad_s': d.qvel[va[5:9]].tolist(),
                'head_actuator_force': d.actuator_force[actuators[5:9]].tolist()})
    recovery = [entry for entry in history if entry['phase'] == 'recovery']
    reference = np.mean([entry['head_qpos_rad'] for entry in recovery[-25:]], axis=0)
    errors = [float(np.max(np.abs(np.array(entry['head_qpos_rad']) - reference))) for entry in recovery]
    settled = next((entry['phase_s'] for i, entry in enumerate(recovery) if max(errors[i:]) <= .1), None)
    lowest = min(recovery, key=lambda entry: entry['head_camera_elevation_degrees'])
    return {'roll_duration_s': roll_duration, 'recovery_head_alpha': recovery_head_alpha,
            'head_reference_rad_last_half_second': reference.tolist(),
            'head_all_four_joints_within_0_1rad_of_reference_s': settled,
            'recovery_lowest_camera_elevation_degrees': lowest['head_camera_elevation_degrees'],
            'recovery_lowest_camera_elevation_at_s': lowest['phase_s'],
            'final_camera_elevation_degrees': recovery[-1]['head_camera_elevation_degrees'],
            'finite': True, 'trace_every_100ms_and_phase_ends': [entry for entry in history
                if round(entry['phase_s'] * 50) % 5 == 0]}


if __name__ == '__main__':
    result = {'scope': 'Host CPU actual ONNX + Android XML servos; standing 1s then roll then stand 3s',
        'mujoco': mujoco.__version__, 'onnxruntime': ort.__version__,
        'policy_sha256': {name: hashlib.sha256((ROOT / 'app/src/main/assets/policies' /
            f'{name}.onnx').read_bytes()).hexdigest() for name in ('alpha_stand', 'roulade')},
        'limitations': ['Not a replay of the phone episode; exact starting joint states were not logged.',
            'Camera elevation is geometric orientation, not a rendered image.',
            'Settling uses the final half-second of each own rollout; does not prove identical final posture.',
            'Removing only recovery head filtering is an experiment, not a runtime change.',
            'No formal contact-gated full-roll detector or performance acceptance.'],
        'cases': [run(duration, alpha) for duration in (2, 1.5) for alpha in (.5, 1)]}
    (ROOT / 'verification/host-M71-head-recovery.json').write_text(json.dumps(result, indent=2) + '\n')
    for case in result['cases']:
        print(json.dumps({key: value for key, value in case.items() if not key.startswith('trace')}))
