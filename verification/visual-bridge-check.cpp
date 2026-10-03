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
    if(packet.size()!=reference.size()+duck_visual::kCameraTail || packet[0]!=2)
        throw std::runtime_error("Scene size/version mismatch");
    double max_error=0;
    // The existing independently generated v1 fixture still checks all geom/contact values.
    for(std::size_t i=1;i<reference.size();++i) max_error=std::max(max_error,std::abs(double(packet[i])-reference[i]));
    if(max_error>1e-6) throw std::runtime_error("Scene differs from MuJoCo mjv_updateScene");
    int camera=mj_name2id(m.get(),mjOBJ_CAMERA,"head_camera");
    int yaw=mj_name2id(m.get(),mjOBJ_JOINT,"head_yaw");
    if(camera<0 || yaw<0) throw std::runtime_error("Head camera/joint missing");
    std::unique_ptr<mjModel,decltype(&mj_deleteModel)> corrected(mj_copyModel(nullptr,m.get()),mj_deleteModel);
    mjtNum turn[4]={0,1,0,0},fixed[4];
    mju_mulQuat(fixed,corrected->cam_quat+4*camera,turn);
    std::copy(fixed,fixed+4,corrected->cam_quat+4*camera);
    std::unique_ptr<mjData,decltype(&mj_deleteData)> raw_data(mj_makeData(m.get()),mj_deleteData);
    std::unique_ptr<mjData,decltype(&mj_deleteData)> corrected_data(mj_makeData(corrected.get()),mj_deleteData);
    mjvScene camera_scene; mjv_defaultScene(&camera_scene); mjv_makeScene(corrected.get(),&camera_scene,1000);
    mjvCamera view; mjv_defaultCamera(&view); view.type=mjCAMERA_FIXED; view.fixedcamid=camera;
    mjvOption options; mjv_defaultOption(&options);
    double camera_error=0;
    for(int pose=0;pose<3;++pose) {
        std::copy(d->qpos,d->qpos+m->nq,raw_data->qpos);
        raw_data->qpos[m->jnt_qposadr[yaw]]+=pose*.25;
        if(pose==2) { // Body yaw also affects the head pose, independently of the head joint.
            mjtNum body_turn[4]={std::cos(.2),0,0,std::sin(.2)},body_quat[4];
            mju_mulQuat(body_quat,raw_data->qpos+m->jnt_qposadr[root]+3,body_turn);
            std::copy(body_quat,body_quat+4,raw_data->qpos+m->jnt_qposadr[root]+3);
        }
        std::copy(raw_data->qpos,raw_data->qpos+m->nq,corrected_data->qpos);
        mj_forward(m.get(),raw_data.get()); mj_forward(corrected.get(),corrected_data.get());
        auto captured=duck_visual::snapshot(*m,*raw_data,m->jnt_qposadr[root],floor);
        const int at=static_cast<int>(captured.size())-duck_visual::kCameraTail;
        if(captured[at]!=1 || captured[at+1]!=corrected->cam_fovy[camera]) throw std::runtime_error("Camera intrinsics mismatch");
        mjv_updateScene(corrected.get(),corrected_data.get(),&options,nullptr,&view,mjCAT_ALL,&camera_scene);
        camera_error=std::max(camera_error,std::abs(double(captured[at+14])-camera_scene.camera[0].frustum_near));
        camera_error=std::max(camera_error,std::abs(double(captured[at+15])-camera_scene.camera[0].frustum_far));
        for(int k=0;k<3;++k) {
            const double position=(camera_scene.camera[0].pos[k]+camera_scene.camera[1].pos[k])*.5;
            camera_error=std::max(camera_error,std::abs(double(captured[at+2+k])-position));
            camera_error=std::max(camera_error,std::abs(double(captured[at+5+k*3+1])-camera_scene.camera[0].up[k]));
            camera_error=std::max(camera_error,std::abs(double(captured[at+5+k*3+2])+camera_scene.camera[0].forward[k]));
        }
        for(int k=0;k<9;++k)
            camera_error=std::max(camera_error,std::abs(double(captured[at+5+k])-corrected_data->cam_xmat[9*camera+k]));
    }
    mjv_freeScene(&camera_scene);
    if(camera_error>1e-6) throw std::runtime_error("Camera differs from desktop corrected MuJoCo camera");
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
             <<",\"visual_geoms\":"<<packet[1]<<",\"contacts\":"<<packet[2]
             <<",\"snapshot_version\":2,\"camera_desktop_reference_max_error\":"<<camera_error
             <<",\"camera_tracks_head_and_body\":true}\n";
}
