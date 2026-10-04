"""CPU triangle rays through head_camera: compare two-sided and back-face culled drawing.

Uses compiled visual meshes, not collision ray casting. No OpenGL or model changes.
"""
from pathlib import Path
import json
import mujoco
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
HOME = np.array([0,-.0873,-.4579,-.0049,.4530,.3491,.3491,0,0,0,.0873,.4579,.0049,-.4530])
NAMES = 'left_hip_yaw left_hip_roll left_hip_pitch left_knee left_ankle neck_pitch head_pitch head_yaw head_roll right_hip_yaw right_hip_roll right_hip_pitch right_knee right_ankle'.split()

def check():
    m = mujoco.MjModel.from_xml_path(str(ROOT/'app/src/main/assets/model/scene.xml'))
    d = mujoco.MjData(m)
    d.qpos[:7] = [0,0,.125,1,0,0,0]
    for name, value in zip(NAMES, HOME):
        d.qpos[m.joint(name).qposadr[0]] = value
    mujoco.mj_forward(m,d)
    camera = m.camera('head_camera').id
    origin = d.cam_xpos[camera].copy()
    rotation = d.cam_xmat[camera].reshape(3,3) @ np.diag([1,-1,-1])
    near = m.vis.map.znear*m.stat.extent
    triangles, ids = [], []
    for geom in range(m.ngeom):
        if m.geom_type[geom] != mujoco.mjtGeom.mjGEOM_MESH or m.geom_group[geom] >= 3:
            continue
        mesh = m.geom_dataid[geom]
        vertices = m.mesh_vert[m.mesh_vertadr[mesh]:m.mesh_vertadr[mesh]+m.mesh_vertnum[mesh]]
        faces = m.mesh_face[m.mesh_faceadr[mesh]:m.mesh_faceadr[mesh]+m.mesh_facenum[mesh]]
        world = vertices @ d.geom_xmat[geom].reshape(3,3).T + d.geom_xpos[geom]
        triangles.append(world[faces]); ids.extend([geom]*len(faces))
    triangles = np.concatenate(triangles); ids = np.array(ids)
    a = triangles[:,0]; edge1 = triangles[:,1]-a; edge2 = triangles[:,2]-a
    delta = origin-a; cross = np.cross(delta,edge1)
    tangent = np.tan(np.deg2rad(m.cam_fovy[camera])/2)
    samples = []
    for y in (-.8,0,.8):
        for x in (-.8,0,.8):
            direction = rotation @ np.array([x*tangent*640/360,y*tangent,-1])
            direction /= np.linalg.norm(direction)
            p = np.cross(direction,edge2); determinant = np.einsum('ij,ij->i',edge1,p)
            inverse = np.divide(1,determinant,out=np.zeros_like(determinant),where=np.abs(determinant)>1e-12)
            u = np.einsum('ij,ij->i',delta,p)*inverse
            v = (cross@direction)*inverse
            distance = np.einsum('ij,ij->i',edge2,cross)*inverse
            hit = (np.abs(determinant)>1e-12)&(u>=0)&(v>=0)&(u+v<=1)&(distance*(-(rotation.T@direction)[2])>=near)
            result = {'ray':[x,y]}
            for label, mask in [('two_sided',hit),('back_faces_culled',hit&(determinant>0))]:
                candidates = np.where(mask)[0]
                index = candidates[np.argmin(distance[candidates])] if len(candidates) else None
                geom = int(ids[index]) if index is not None else None
                result[label] = None if index is None else {'geom_id':geom,'body':m.body(int(m.geom_bodyid[geom])).name,
                    'mesh':m.mesh(int(m.geom_dataid[geom])).name,'distance_m':float(distance[index]),'back_face':bool(determinant[index]<0)}
            samples.append(result)
    scene = mujoco.MjvScene(m,maxgeom=1000)
    assert scene.flags[mujoco.mjtRndFlag.mjRND_CULL_FACE]
    occluded = sum(s['two_sided'] is not None and s['two_sided']['back_face'] and s['two_sided']['distance_m']<.05 for s in samples)
    assert occluded >= 5, 'Could not reproduce camera enclosure occlusion'
    assert all(s['back_faces_culled'] is None or not s['back_faces_culled']['back_face'] for s in samples)
    return {'scope':'CPU visual triangle intersections at HOME; not Android GPU validation',
            'mujoco_default_back_face_culling':True,'near_m':float(near),
            'enclosure_back_faces_occlude_rays':occluded,'rays':samples}

if __name__ == '__main__':
    result=check()
    (ROOT/'verification/head-camera-visibility.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
