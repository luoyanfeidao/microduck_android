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
    std::size_t triangles=0,vertices=0,bytes=0;
    for(int mesh=0;mesh<m->nmesh;++mesh) {
        auto data=duck_visual::indexed_mesh(*m,mesh);
        if(data.vertices.size()%6 || data.indices.size()!=static_cast<std::size_t>(m->mesh_facenum[mesh])*3)
            throw std::runtime_error("Mesh size mismatch");
        for(float value:data.vertices) if(!std::isfinite(value)) throw std::runtime_error("Invalid mesh value");
        for(int face=0;face<m->mesh_facenum[mesh];++face) for(int corner=0;corner<3;++corner) {
            int v=m->mesh_face[3*(m->mesh_faceadr[mesh]+face)+corner];
            int n=m->mesh_facenormal[3*(m->mesh_faceadr[mesh]+face)+corner];
            int index=data.indices[face*3+corner];
            if(index<0 || static_cast<std::size_t>(index)>=data.vertices.size()/6) throw std::runtime_error("Index out of range");
            for(int k=0;k<3;++k) if(data.vertices[index*6+k]!=m->mesh_vert[3*(m->mesh_vertadr[mesh]+v)+k])
                throw std::runtime_error("Mesh coordinate mismatch");
            if(n<0 || n>=m->mesh_normalnum[mesh]) throw std::runtime_error("Fixture requires compiled normals");
            for(int k=0;k<3;++k) if(data.vertices[index*6+3+k]!=m->mesh_normal[3*(m->mesh_normaladr[mesh]+n)+k])
                throw std::runtime_error("Mesh normal mismatch");
        }
        triangles+=data.indices.size()/3; vertices+=data.vertices.size()/6;
        bytes+=4*(data.vertices.size()+data.indices.size());
    }
    // Two faces sharing positions but using different normals must stay split.
    mjModel synthetic{}; synthetic.nmesh=1;
    int face_count=2,zero=0,vert_count=3,normal_count=2;
    int faces[]={0,1,2,0,2,1},normals[]={0,0,0,1,1,1};
    float positions[]={0,0,0,1,0,0,0,1,0},directions[]={0,0,1,0,0,-1};
    synthetic.mesh_facenum=&face_count; synthetic.mesh_faceadr=&zero;
    synthetic.mesh_vertadr=&zero; synthetic.mesh_normaladr=&zero;
    synthetic.mesh_vertnum=&vert_count; synthetic.mesh_normalnum=&normal_count;
    synthetic.mesh_face=faces; synthetic.mesh_facenormal=normals;
    synthetic.mesh_vert=positions; synthetic.mesh_normal=directions;
    auto edges=duck_visual::indexed_mesh(synthetic,0);
    if(edges.vertices.size()!=36 || edges.indices[0]==edges.indices[3]) throw std::runtime_error("Hard edge collapsed");
    std::fill(normals,normals+6,-1);
    auto flat=duck_visual::indexed_mesh(synthetic,0);
    if(flat.vertices.size()!=36 || flat.vertices[5]!=1 || flat.vertices[23]!=-1)
        throw std::runtime_error("Flat normal fallback mismatch");
    std::cout<<"{\"mjv_scene_max_error\":"<<max_error<<",\"meshes\":"<<m->nmesh
             <<",\"unique_triangles\":"<<triangles<<",\"indexed_vertices\":"<<vertices<<",\"indexed_bytes\":"<<bytes
             <<",\"all_corners_position_normal_exact\":true,\"hard_edges_and_flat_fallback\":true"
             <<",\"visual_geoms\":"<<packet[1]<<",\"contacts\":"<<packet[2]<<"}\n";
}
