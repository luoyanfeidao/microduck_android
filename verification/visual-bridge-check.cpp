#include "../app/src/main/cpp/visual_snapshot.h"
#include <fstream>
#include <iostream>
#include <memory>
int main(int argc,char** argv) {
    if(argc!=3) return 2;
    char error[2048]={};
    std::unique_ptr<mjModel,decltype(&mj_deleteModel)> m(mj_loadXML(argv[1],nullptr,error,sizeof(error)),mj_deleteModel);
    if(!m) { std::cerr<<error; return 2; }
    std::unique_ptr<mjData,decltype(&mj_deleteData)> d(mj_makeData(m.get()),mj_deleteData);
    std::ifstream f(argv[2]);
    for(int i=0;i<m->nq;++i) f>>d->qpos[i]; for(int i=0;i<m->nv;++i) f>>d->qvel[i];
    int length; f>>length; std::vector<float> reference(length); for(float& v:reference) f>>v;
    if(!f) throw std::runtime_error("Invalid reference fixture");
    mj_forward(m.get(),d.get());
    int root=mj_name2id(m.get(),mjOBJ_JOINT,"trunk_base_freejoint"),floor=mj_name2id(m.get(),mjOBJ_GEOM,"floor");
    auto packet=duck_visual::snapshot(*m,*d,m->jnt_qposadr[root],floor);
    if(packet.size()!=reference.size()) throw std::runtime_error("Scene size mismatch");
    double max_error=0;
    for(std::size_t i=0;i<packet.size();++i) max_error=std::max(max_error,std::abs(double(packet[i])-reference[i]));
    if(max_error>1e-6) throw std::runtime_error("Scene differs from MuJoCo mjv_updateScene");
    std::size_t triangles=0;
    for(int mesh=0;mesh<m->nmesh;++mesh) {
        auto data=duck_visual::mesh_triangles(*m,mesh);
        if(data.size()!=static_cast<std::size_t>(m->mesh_facenum[mesh])*18) throw std::runtime_error("Mesh size mismatch");
        for(float value:data) if(!std::isfinite(value)) throw std::runtime_error("Invalid mesh value");
        for(int face:{0,m->mesh_facenum[mesh]-1}) for(int corner=0;corner<3;++corner) {
            int v=m->mesh_face[3*(m->mesh_faceadr[mesh]+face)+corner];
            for(int k=0;k<3;++k) if(data[face*18+corner*6+k]!=m->mesh_vert[3*(m->mesh_vertadr[mesh]+v)+k])
                throw std::runtime_error("Mesh coordinate mismatch");
        }
        triangles+=data.size()/18;
    }
    std::cout<<"{\"mjv_scene_max_error\":"<<max_error<<",\"meshes\":"<<m->nmesh
             <<",\"unique_triangles\":"<<triangles<<",\"visual_geoms\":"<<packet[1]<<",\"contacts\":"<<packet[2]<<"}\n";
}
