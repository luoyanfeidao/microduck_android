#include <jni.h>
#include <mujoco/mujoco.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "visual_snapshot.h"

namespace {
using Clock = std::chrono::steady_clock;
constexpr int N = 14;
const char* JOINTS[N] = {"left_hip_yaw","left_hip_roll","left_hip_pitch","left_knee","left_ankle",
    "neck_pitch","head_pitch","head_yaw","head_roll","right_hip_yaw","right_hip_roll",
    "right_hip_pitch","right_knee","right_ankle"};
constexpr double HOME[N] = {0,-.0873,-.4579,-.0049,.4530,.3491,.3491,0,0,0,.0873,.4579,.0049,-.4530};
struct Simulation {
    mjModel* model = nullptr;
    mjData* data = nullptr;
    int trunk = -1, floor = -1, left_foot = -1, right_foot = -1;
    int key = -1;
    std::uint64_t steps = 0;
    double last_batch_ms = 0;
    int qpos[N] = {}, qvel[N] = {}, actuators[N] = {};
    int root_qpos = -1, root_dof = -1;
    double base_gain[N] = {}, previous_target[N] = {};
    bool has_previous_target = false, home_placement = false;
    ~Simulation() { if (data) mj_deleteData(data); if (model) mj_deleteModel(model); }
};
std::string utf(JNIEnv* env, jstring value) {
    if (!value) throw std::runtime_error("Missing string argument");
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (!text) throw std::runtime_error("Cannot read string");
    std::string result(text); env->ReleaseStringUTFChars(value, text); return result;
}
Simulation& sim(jlong handle) {
    if (!handle) throw std::runtime_error("Simulation is not loaded");
    return *reinterpret_cast<Simulation*>(static_cast<std::uintptr_t>(handle));
}
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
bool finite(const Simulation& s) {
    for (int i=0;i<s.model->nq;++i) if (!std::isfinite(s.data->qpos[i])) return false;
    for (int i=0;i<s.model->nv;++i) if (!std::isfinite(s.data->qvel[i])) return false;
    for (int i=0;i<s.model->nu;++i) if (!std::isfinite(s.data->ctrl[i])) return false;
    return std::isfinite(s.data->time);
}
void reset(Simulation& s, int key) {
    if (key < 0) throw std::runtime_error("Keyframe not found in scene.xml");
    mj_resetDataKeyframe(s.model, s.data, key);
    s.key = key; s.steps = 0; s.last_batch_ms = 0;
    s.home_placement = false; s.has_previous_target = false;
    for(int i=0;i<N;++i) {
        s.model->actuator_gainprm[s.actuators[i]*mjNGAIN] = s.base_gain[i];
        s.model->actuator_biasprm[s.actuators[i]*mjNBIAS+1] = -s.base_gain[i];
    }
    mj_forward(s.model, s.data);
    if (!finite(s)) throw std::runtime_error("Non-finite reset state");
}
void one_step(Simulation& s) {
    if (!finite(s)) throw std::runtime_error("Non-finite input state");
    mj_step(s.model, s.data); ++s.steps;
    if (!finite(s) || s.data->warning[mjWARN_BADQPOS].number ||
        s.data->warning[mjWARN_BADQVEL].number || s.data->warning[mjWARN_BADQACC].number) {
        throw std::runtime_error("Numerical instability: simulation paused; reset required");
    }
}
void advance(Simulation& s,int count) {
    if(count<1 || count>10000) throw std::runtime_error("Invalid step count");
    const auto start=Clock::now();
    for(int i=0;i<count;++i) one_step(s);
    s.last_batch_ms=std::chrono::duration<double,std::milli>(Clock::now()-start).count();
    // Retain the baseline final-state forward pass, including contact/solver work.
    mj_forward(s.model,s.data);
}
void build_observation(const Simulation& s,const float* previous,const float* command,float* obs) {
    std::copy(previous,previous+N,obs+34); std::copy(command,command+13,obs+48);
    // Free-joint angular velocity is local; projected gravity is R^T * (0,0,-1).
    mjtNum rotation[9]; mju_quat2Mat(rotation,s.data->qpos+s.root_qpos+3);
    for(int i=0;i<3;++i) { obs[i]=s.data->qvel[s.root_dof+3+i]; obs[3+i]=-rotation[6+i]; }
    for(int i=0;i<N;++i) { obs[6+i]=s.data->qpos[s.qpos[i]]-HOME[i]; obs[20+i]=s.data->qvel[s.qvel[i]]; }
    obs[55]=obs[56]=obs[60]=0; // Unbound body x/y/yaw.
    for(int i=0;i<61;++i) if(!std::isfinite(obs[i])) throw std::runtime_error("Non-finite observation");
}
void apply_scaled_action(Simulation& s,const float* raw,double scale,double gain_ratio,bool filter) {
    for(int i=0;i<N;++i) if(!std::isfinite(raw[i])) throw std::runtime_error("Non-finite policy action");
    for(int i=0;i<N;++i) {
        double target=HOME[i]+scale*raw[i];
        double alpha=(i>=5 && i<=8)?.5:.7;
        if(filter && s.has_previous_target) target=alpha*target+(1-alpha)*s.previous_target[i];
        s.data->ctrl[s.actuators[i]]=target; s.previous_target[i]=target;
        double gain=s.base_gain[i]*gain_ratio;
        s.model->actuator_gainprm[s.actuators[i]*mjNGAIN]=gain;
        s.model->actuator_biasprm[s.actuators[i]*mjNBIAS+1]=-gain;
    }
    s.has_previous_target=true;
}
void apply_policy_action(Simulation& s,const float* raw,bool standing,bool filter) {
    apply_scaled_action(s,raw,standing?1.0:.9,standing?.8:1.0,filter);
}
std::string state(const Simulation& s) {
    int feet[2] = {0,0};
    for (int i=0;i<s.data->ncon;++i) {
        const auto& c = s.data->contact[i];
        for (int side=0;side<2;++side) {
            const int foot = side == 0 ? s.left_foot : s.right_foot;
            if (foot >= 0 && s.floor >= 0 &&
                ((c.geom[0] == foot && c.geom[1] == s.floor) ||
                 (c.geom[1] == foot && c.geom[0] == s.floor))) ++feet[side];
        }
    }
    const char* keyname = s.home_placement ? "HOME" : mj_id2name(s.model, mjOBJ_KEY, s.key);
    std::ostringstream o; o << std::setprecision(12);
    o << "{\"mujoco\":\"" << mj_versionString() << "\",\"keyframe\":\"" << (keyname ? keyname : "")
      << "\",\"nbody\":" << s.model->nbody << ",\"nq\":" << s.model->nq
      << ",\"nv\":" << s.model->nv << ",\"nu\":" << s.model->nu
      << ",\"nmesh\":" << s.model->nmesh << ",\"timestep\":" << s.model->opt.timestep
      << ",\"sim_time\":" << s.data->time << ",\"steps\":" << s.steps
      << ",\"trunk_z\":" << s.data->xpos[3*s.trunk+2]
      << ",\"trunk_x\":" << s.data->xpos[3*s.trunk]
      << ",\"trunk_y\":" << s.data->xpos[3*s.trunk+1]
      << ",\"root_x\":" << s.data->qpos[s.root_qpos]
      << ",\"root_y\":" << s.data->qpos[s.root_qpos+1]
      << ",\"root_z\":" << s.data->qpos[s.root_qpos+2]
      << ",\"root_quaternion\":[" << s.data->qpos[s.root_qpos+3] << "," << s.data->qpos[s.root_qpos+4]
      << "," << s.data->qpos[s.root_qpos+5] << "," << s.data->qpos[s.root_qpos+6] << "]"
      << ",\"contacts\":" << s.data->ncon << ",\"left_contacts\":" << feet[0]
      << ",\"right_contacts\":" << feet[1]
      << ",\"foot_geoms_resolved\":" << (s.floor >= 0 && s.left_foot >= 0 && s.right_foot >= 0 ? "true" : "false")
      << ",\"finite\":" << (finite(s) ? "true" : "false")
      << ",\"last_batch_ms\":" << s.last_batch_ms << "}";
    return o.str();
}
jstring java_string(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_microduck_sim_NativeSim_open(JNIEnv* env, jclass, jstring path, jstring key) {
    try {
        auto s = std::make_unique<Simulation>(); char error[2048] = {};
        const auto filename = utf(env,path);
        s->model = mj_loadXML(filename.c_str(), nullptr, error, sizeof(error));
        if (!s->model) throw std::runtime_error(std::string("MJCF load failed: ")+error);
        // Exact desktop duck-body step size, independent of its XML default.
        s->model->opt.timestep = 0.005;
        s->data = mj_makeData(s->model);
        if (!s->data) throw std::runtime_error("Cannot allocate mjData");
        s->trunk = mj_name2id(s->model,mjOBJ_BODY,"trunk_base");
        if (s->trunk < 0) throw std::runtime_error("trunk_base body missing");
        s->floor = mj_name2id(s->model,mjOBJ_GEOM,"floor");
        s->left_foot = mj_name2id(s->model,mjOBJ_GEOM,"left_foot_collision");
        s->right_foot = mj_name2id(s->model,mjOBJ_GEOM,"right_foot_collision");
        if(s->model->nu != N) throw std::runtime_error("M2 requires the 14-actuator plain model");
        int root = mj_name2id(s->model,mjOBJ_JOINT,"trunk_base_freejoint");
        if(root < 0 || s->model->jnt_type[root] != mjJNT_FREE) throw std::runtime_error("Free trunk joint missing");
        s->root_qpos=s->model->jnt_qposadr[root]; s->root_dof=s->model->jnt_dofadr[root];
        for(int i=0;i<N;++i) {
            int joint=mj_name2id(s->model,mjOBJ_JOINT,JOINTS[i]);
            if(joint<0 || s->model->jnt_type[joint]!=mjJNT_HINGE) throw std::runtime_error("Policy hinge joint missing");
            int actuator=-1;
            for(int a=0;a<s->model->nu;++a) if(s->model->actuator_trntype[a]==mjTRN_JOINT && s->model->actuator_trnid[2*a]==joint) {
                if(actuator>=0) throw std::runtime_error("Duplicate policy actuator");
                actuator=a;
            }
            if(actuator<0) throw std::runtime_error("Policy actuator missing");
            s->qpos[i]=s->model->jnt_qposadr[joint]; s->qvel[i]=s->model->jnt_dofadr[joint];
            s->actuators[i]=actuator; s->base_gain[i]=s->model->actuator_gainprm[actuator*mjNGAIN];
        }
        const auto keyname = utf(env,key);
        reset(*s,mj_name2id(s->model,mjOBJ_KEY,keyname.c_str()));
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(s.release()));
    } catch (const std::exception& e) { fail(env,e); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_microduck_sim_NativeSim_state(JNIEnv* env,jclass,jlong handle) {
    try { return java_string(env,state(sim(handle))); } catch (const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_microduck_sim_NativeSim_step(JNIEnv* env,jclass,jlong handle,jint count) {
    try {
        auto& s=sim(handle); advance(s,count);
        return java_string(env,state(s));
    } catch(const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_microduck_sim_NativeSim_advance(JNIEnv* env,jclass,jlong handle,jint count) {
    try { advance(sim(handle),count); } catch(const std::exception& e) { fail(env,e); }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_microduck_sim_NativeSim_reset(JNIEnv* env,jclass,jlong handle,jstring key) {
    try {
        auto& s=sim(handle); const auto name=utf(env,key);
        reset(s,mj_name2id(s.model,mjOBJ_KEY,name.c_str()));
        return java_string(env,state(s));
    } catch(const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_microduck_sim_NativeSim_benchmark(JNIEnv* env,jclass,jlong handle,jint count) {
    try {
        if(count < 100 || count > 100000) throw std::runtime_error("Benchmark steps must be 100..100000");
        auto& s=sim(handle); reset(s,s.key);
        for(int i=0;i<100;++i) one_step(s); // Unmeasured solver warm-up.
        const auto start=Clock::now(); std::vector<double> us; us.reserve(count);
        for(int i=0;i<count;++i) {
            const auto tick=Clock::now(); one_step(s);
            us.push_back(std::chrono::duration<double,std::micro>(Clock::now()-tick).count());
        }
        const double seconds=std::chrono::duration<double>(Clock::now()-start).count();
        mj_forward(s.model,s.data);
        std::sort(us.begin(),us.end());
        auto percentile=[&](double fraction) { return us[static_cast<std::size_t>((us.size()-1)*fraction)]; };
        auto json=state(s); json.pop_back(); std::ostringstream o; o << std::setprecision(12) << json;
        o << ",\"benchmark_steps\":" << count << ",\"warmup_steps\":100,\"wall_seconds\":" << seconds
          << ",\"realtime_factor\":" << (count*s.model->opt.timestep/seconds)
          << ",\"step_mean_us\":" << seconds*1e6/count
          << ",\"step_p50_us\":" << percentile(.5) << ",\"step_p95_us\":" << percentile(.95)
          << ",\"step_p99_us\":" << percentile(.99) << ",\"step_max_us\":" << us.back() << "}";
        return java_string(env,o.str());
    } catch(const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_microduck_sim_NativeSim_close(JNIEnv*,jclass,jlong handle) {
    delete reinterpret_cast<Simulation*>(static_cast<std::uintptr_t>(handle));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_microduck_sim_NativeSim_resetHome(JNIEnv* env,jclass,jlong handle) {
    try {
        auto& s=sim(handle); mj_resetData(s.model,s.data);
        s.key=-1; s.steps=0; s.last_batch_ms=0; s.home_placement=true; s.has_previous_target=false;
        auto* root=s.data->qpos+s.root_qpos;
        root[0]=root[1]=0; root[2]=.125; root[3]=1; root[4]=root[5]=root[6]=0;
        for(int i=0;i<N;++i) {
            s.data->qpos[s.qpos[i]]=HOME[i]; s.data->ctrl[s.actuators[i]]=HOME[i];
            s.model->actuator_gainprm[s.actuators[i]*mjNGAIN]=s.base_gain[i];
            s.model->actuator_biasprm[s.actuators[i]*mjNBIAS+1]=-s.base_gain[i];
        }
        mj_forward(s.model,s.data); return java_string(env,state(s));
    } catch(const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_microduck_sim_NativeSim_observation(
    JNIEnv* env,jclass,jlong handle,jfloatArray previous,jfloatArray command) {
    try {
        auto& s=sim(handle);
        if(!previous || !command || env->GetArrayLength(previous)!=N || env->GetArrayLength(command)!=13)
            throw std::runtime_error("Observation requires previousAction[14] and command[13]");
        float obs[61]={}, prev[N], cmd[13];
        env->GetFloatArrayRegion(previous,0,N,prev); env->GetFloatArrayRegion(command,0,13,cmd);
        build_observation(s,prev,cmd,obs);
        jfloatArray out=env->NewFloatArray(61); if(out) env->SetFloatArrayRegion(out,0,61,obs); return out;
    } catch(const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_microduck_sim_NativeSim_applyAction(
    JNIEnv* env,jclass,jlong handle,jfloatArray action,jboolean standing,jboolean filter) {
    try {
        auto& s=sim(handle); float raw[N];
        if(!action || env->GetArrayLength(action)!=N) throw std::runtime_error("Expected rawAction[14]");
        env->GetFloatArrayRegion(action,0,N,raw);
        apply_policy_action(s,raw,standing,filter);
    } catch(const std::exception& e) { fail(env,e); }
}
extern "C" JNIEXPORT void JNICALL Java_com_microduck_sim_NativeSim_applyRouladeAction(
    JNIEnv* env,jclass,jlong handle,jfloatArray action,jboolean filter) {
    try {
        auto& s=sim(handle); float raw[N];
        if(!action || env->GetArrayLength(action)!=N) throw std::runtime_error("Expected rawAction[14]");
        env->GetFloatArrayRegion(action,0,N,raw);
        apply_scaled_action(s,raw,1.0,1.0,filter);
    } catch(const std::exception& e) { fail(env,e); }
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_microduck_sim_NativeSim_visualMeshes(JNIEnv* env,jclass,jlong handle) {
    try {
        const auto& s=sim(handle);
        jclass mesh_class=env->FindClass("com/microduck/sim/VisualMesh"); if(!mesh_class) return nullptr;
        jmethodID ctor=env->GetMethodID(mesh_class,"<init>","([F[I)V");
        if(!ctor) { env->DeleteLocalRef(mesh_class); return nullptr; }
        jobjectArray out=env->NewObjectArray(s.model->nmesh,mesh_class,nullptr);
        if(!out) return nullptr;
        for(int mesh=0;mesh<s.model->nmesh;++mesh) {
            auto data=duck_visual::indexed_mesh(*s.model,mesh);
            jfloatArray vertices=env->NewFloatArray(data.vertices.size()); if(!vertices) return nullptr;
            jintArray indices=env->NewIntArray(data.indices.size()); if(!indices) return nullptr;
            env->SetFloatArrayRegion(vertices,0,data.vertices.size(),data.vertices.data());
            env->SetIntArrayRegion(indices,0,data.indices.size(),data.indices.data());
            if(env->ExceptionCheck()) return nullptr;
            jobject packet=env->NewObject(mesh_class,ctor,vertices,indices);
            env->DeleteLocalRef(vertices); env->DeleteLocalRef(indices);
            if(!packet) return nullptr;
            env->SetObjectArrayElement(out,mesh,packet); env->DeleteLocalRef(packet);
            if(env->ExceptionCheck()) return nullptr;
        }
        env->DeleteLocalRef(mesh_class);
        return out;
    } catch(const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_microduck_sim_NativeSim_visualSnapshot(JNIEnv* env,jclass,jlong handle) {
    try {
        const auto& s=sim(handle); auto values=duck_visual::snapshot(*s.model,*s.data,s.root_qpos,s.floor);
        jfloatArray out=env->NewFloatArray(values.size()); if(out) env->SetFloatArrayRegion(out,0,values.size(),values.data()); return out;
    } catch(const std::exception& e) { fail(env,e); return nullptr; }
}
