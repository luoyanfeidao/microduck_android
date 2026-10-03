#!/usr/bin/env python3
"""Reference compiled mesh transforms against MuJoCo's CPU mjv_updateScene, no GL."""
from pathlib import Path
import json
import mujoco
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
m=mujoco.MjModel.from_xml_path(str(ROOT/'app/src/main/assets/model/scene.xml'))
d=mujoco.MjData(m)
root=mujoco.mj_name2id(m,mujoco.mjtObj.mjOBJ_JOINT,'trunk_base_freejoint')
q=np.array([.9,.1,-.2,.3]); q/=np.linalg.norm(q)
d.qpos[m.jnt_qposadr[root]:m.jnt_qposadr[root]+7]=[.2,-.1,.125,*q]
d.qpos[7:]=np.linspace(-.3,.3,m.nq-7); d.qvel[:]=np.linspace(-.5,.5,m.nv)
mujoco.mj_forward(m,d)
option=mujoco.MjvOption(); camera=mujoco.MjvCamera(); scene=mujoco.MjvScene(m,maxgeom=1000)
mujoco.mjv_updateScene(m,d,option,None,camera,mujoco.mjtCatBit.mjCAT_ALL,scene)
geoms=[g for g in scene.geoms[:scene.ngeom] if g.objtype==mujoco.mjtObj.mjOBJ_GEOM and g.type==mujoco.mjtGeom.mjGEOM_MESH]
assert len(geoms)==70
floor=mujoco.mj_name2id(m,mujoco.mjtObj.mjOBJ_GEOM,'floor')
values=[1,len(geoms),d.ncon,d.time,*d.qpos[:3],d.geom_xpos[floor,2],0]
for g in geoms:
    assert g.dataid%2==0
    values.extend([g.dataid//2,*g.pos,*g.mat.ravel(),*g.rgba])
values.extend(d.contact.pos[:d.ncon].ravel())
values=np.array(values,dtype=np.float32)
fixture=np.r_[d.qpos,d.qvel,len(values),values]
(ROOT/'verification/visual-reference-fixture.txt').write_text('\n'.join(format(float(v),'.17g') for v in fixture)+'\n')
stats={'mujoco':mujoco.__version__,'reference':'CPU mjv_updateScene, tilted/moving robot','meshes':m.nmesh,
 'unique_triangles':int(sum(m.mesh_facenum)),'visual_mesh_geoms':len(geoms),'contact_points':d.ncon,
 'snapshot_floats':len(values),'unindexed_mesh_bytes':int(sum(m.mesh_facenum))*18*4}
(ROOT/'verification/visual-reference.json').write_text(json.dumps(stats,indent=2)+'\n')
print(json.dumps(stats))
