#pragma once
#include <mujoco/mujoco.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <unordered_map>
#include <vector>

// Pure CPU bridge. Called only by the simulation worker; no OpenGL or mjData
// pointers leave it. Mesh vertices are already compiled into MuJoCo's mesh frame.
namespace duck_visual {
constexpr int kHeader = 9, kGeomStride = 17, kCameraTail = 16, kVersion = 2;
struct IndexedMesh {
    std::vector<float> vertices; // interleaved position + normal
    std::vector<int> indices;   // GLES GL_UNSIGNED_INT, in original face order
};
inline IndexedMesh indexed_mesh(const mjModel& m,int mesh) {
    if(mesh<0 || mesh>=m.nmesh) throw std::runtime_error("Invalid visual mesh");
    const int faces=m.mesh_facenum[mesh], fadr=m.mesh_faceadr[mesh];
    const int vadr=m.mesh_vertadr[mesh], nadr=m.mesh_normaladr[mesh];
    if(faces<0 || faces>std::numeric_limits<int>::max()/18) throw std::runtime_error("Visual mesh too large");
    IndexedMesh out;
    out.indices.reserve(static_cast<std::size_t>(faces)*3);
    std::unordered_map<std::uint64_t,int> pairs;
    for(int f=0;f<faces;++f) {
        const int* ids=m.mesh_face+3*(fadr+f);
        for(int corner=0;corner<3;++corner) if(ids[corner]<0 || ids[corner]>=m.mesh_vertnum[mesh])
            throw std::runtime_error("Invalid visual vertex index");
        mjtNum a[3],b[3],flat[3];
        for(int k=0;k<3;++k) {
            a[k]=m.mesh_vert[3*(vadr+ids[1])+k]-m.mesh_vert[3*(vadr+ids[0])+k];
            b[k]=m.mesh_vert[3*(vadr+ids[2])+k]-m.mesh_vert[3*(vadr+ids[0])+k];
        }
        mju_cross(flat,a,b); if(mju_normalize3(flat)<1e-12) { flat[0]=flat[1]=0; flat[2]=1; }
        for(int corner=0;corner<3;++corner) {
            int vid=ids[corner], nid=m.mesh_facenormal[3*(fadr+f)+corner];
            bool normal_valid=nid>=0 && nid<m.mesh_normalnum[mesh];
            // Preserve hard edges: position alone is not a render vertex.
            // Missing normals use a separate flat vertex for each face corner.
            std::uint64_t key=(std::uint64_t(std::uint32_t(vid))<<32)|std::uint32_t(nid);
            auto found=normal_valid ? pairs.find(key) : pairs.end();
            if(found!=pairs.end()) { out.indices.push_back(found->second); continue; }
            int index=static_cast<int>(out.vertices.size()/6);
            out.indices.push_back(index);
            if(normal_valid) pairs.emplace(key,index);
            for(int k=0;k<3;++k) {
                out.vertices.push_back(m.mesh_vert[3*(vadr+vid)+k]);
            }
            for(int k=0;k<3;++k) out.vertices.push_back(normal_valid ? m.mesh_normal[3*(nadr+nid)+k] : flat[k]);
        }
    }
    return out;
}
inline bool visible_mesh(const mjModel& m,int g) {
    return m.geom_type[g]==mjGEOM_MESH && m.geom_group[g]<3 && m.geom_dataid[g]>=0;
}
inline std::vector<float> snapshot(const mjModel& m,const mjData& d,int root_qpos,int floor) {
    int count=0; for(int g=0;g<m.ngeom;++g) if(visible_mesh(m,g)) ++count;
    std::vector<float> out(kHeader+count*kGeomStride+3*d.ncon+kCameraTail);
    out[0]=kVersion; out[1]=count; out[2]=d.ncon; out[3]=d.time;
    for(int k=0;k<3;++k) out[4+k]=d.qpos[root_qpos+k];
    out[7]=floor>=0 ? d.geom_xpos[3*floor+2] : 0; out[8]=0; // reserved
    int at=kHeader;
    for(int g=0;g<m.ngeom;++g) if(visible_mesh(m,g)) {
        out[at]=m.geom_dataid[g];
        for(int k=0;k<3;++k) out[at+1+k]=d.geom_xpos[3*g+k];
        for(int k=0;k<9;++k) out[at+4+k]=d.geom_xmat[9*g+k]; // row major
        const float* color=m.geom_rgba+4*g; int material=m.geom_matid[g];
        if(material>=0 && color[0]==.5f && color[1]==.5f && color[2]==.5f && color[3]==1)
            color=m.mat_rgba+4*material;
        for(int k=0;k<4;++k) out[at+13+k]=color[k];
        at+=kGeomStride;
    }
    for(int c=0;c<d.ncon;++c) for(int k=0;k<3;++k) out[at++]=d.contact[c].pos[k];
    const int camera=mj_name2id(&m,mjOBJ_CAMERA,"head_camera");
    if(camera>=0) {
        out[at]=1; out[at+1]=m.cam_fovy[camera];
        for(int k=0;k<3;++k) out[at+2+k]=d.cam_xpos[3*camera+k];
        // Match sim/camera.py: local X half-turn, R_corrected = R_raw * diag(1,-1,-1).
        // Snapshot only: never mutate the model or physics for the camera correction.
        for(int row=0;row<3;++row) for(int col=0;col<3;++col)
            out[at+5+row*3+col]=d.cam_xmat[9*camera+row*3+col]*(col==0?1:-1);
        out[at+14]=m.vis.map.znear*m.stat.extent;
        out[at+15]=m.vis.map.zfar*m.stat.extent;
    }
    return out;
}
} // namespace duck_visual
