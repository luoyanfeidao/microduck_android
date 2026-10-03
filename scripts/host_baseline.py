#!/usr/bin/env python3
"""Run the same M1 position-servo scene on desktop MuJoCo; not a policy rollout."""
from pathlib import Path
import json,time
import mujoco
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
SCENE=ROOT/'app/src/main/assets/model/scene.xml'
records=[]
for pose in ('SIT','STAND','INIT'):
    m=mujoco.MjModel.from_xml_path(str(SCENE)); m.opt.timestep=.005
    d=mujoco.MjData(m)
    k=mujoco.mj_name2id(m,mujoco.mjtObj.mjOBJ_KEY,pose)
    assert k>=0
    mujoco.mj_resetDataKeyframe(m,d,k); mujoco.mj_forward(m,d)
    initial=d.qpos.copy().tolist()
    for _ in range(100): mujoco.mj_step(m,d)
    start=time.perf_counter()
    for _ in range(2000): mujoco.mj_step(m,d)
    wall=time.perf_counter()-start
    mujoco.mj_forward(m,d)
    assert np.isfinite(d.qpos).all() and np.isfinite(d.qvel).all()
    trunk=mujoco.mj_name2id(m,mujoco.mjtObj.mjOBJ_BODY,'trunk_base')
    record={'keyframe':pose,'mujoco':mujoco.__version__,'nq':m.nq,'nv':m.nv,'nu':m.nu,
            'nbody':m.nbody,'nmesh':m.nmesh,'initial_qpos':initial,
            'sim_time':d.time,'trunk_z':float(d.xpos[trunk,2]),'contacts':d.ncon,
            'finite':True,'wall_seconds':wall,'realtime_factor':10/wall,
            'benchmark_steps':2000,'warmup_steps':100,
            'qpos':d.qpos.tolist(),'qvel':d.qvel.tolist()}
    records.append(record)
    print(json.dumps({k:v for k,v in record.items() if k not in ('qpos','qvel','initial_qpos')}))
OUT=ROOT/'verification'; OUT.mkdir(exist_ok=True)
(OUT/'host-baseline.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n')
