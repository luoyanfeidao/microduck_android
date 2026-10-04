// Host-only access to the exact native helpers compiled into Android.
#include "../app/src/main/cpp/native_sim.cpp"
extern "C" {
void* duck_create(const char* path) {
    auto s=std::make_unique<Simulation>();char error[2048]{};
    s->model=mj_loadXML(path,nullptr,error,sizeof(error));if(!s->model)return nullptr;
    s->model->opt.timestep=.005;s->data=mj_makeData(s->model);
    int root=mj_name2id(s->model,mjOBJ_JOINT,"trunk_base_freejoint");
    s->root_qpos=s->model->jnt_qposadr[root];s->root_dof=s->model->jnt_dofadr[root];
    s->trunk=mj_name2id(s->model,mjOBJ_BODY,"trunk_base");
    for(int i=0;i<N;++i) {
        int j=mj_name2id(s->model,mjOBJ_JOINT,JOINTS[i]);
        s->qpos[i]=s->model->jnt_qposadr[j];s->qvel[i]=s->model->jnt_dofadr[j];
        for(int a=0;a<s->model->nu;++a)if(s->model->actuator_trnid[2*a]==j)s->actuators[i]=a;
        s->base_gain[i]=s->model->actuator_gainprm[s->actuators[i]*mjNGAIN];
    }
    s->bam.configure(s->model,s->data,s->actuators,true);
    reset(*s,mj_name2id(s->model,mjOBJ_KEY,"SIT"));return s.release();
}
void duck_destroy(void* p){delete static_cast<Simulation*>(p);}
void duck_home(void* p) {
    auto& s=*static_cast<Simulation*>(p);mj_resetData(s.model,s.data);
    s.data->qpos[s.root_qpos+2]=.125;
    for(int i=0;i<N;++i)s.data->qpos[s.qpos[i]]=HOME[i];
    s.bam.reset(s.model,s.data);s.has_previous_target=false;
    mj_forward(s.model,s.data);
}
void duck_mode(void* p,int on){auto& s=*static_cast<Simulation*>(p);s.bam.configure(s.model,s.data,s.actuators,on);reset(s,mj_name2id(s.model,mjOBJ_KEY,"SIT"));}
void duck_action(void* p,const float* a,int mode,int filter){apply_scaled_action(*static_cast<Simulation*>(p),a,mode==1?.9:1,mode==0?.8:1,filter);}
void duck_targets(void* p,const double* targets){body_targets(*static_cast<Simulation*>(p),targets);}
void duck_power(void* p,int on){body_torque(*static_cast<Simulation*>(p),on);}
void duck_gain(void* p,int kp){body_gain(*static_cast<Simulation*>(p),kp);}
void duck_step(void* p,int count){advance(*static_cast<Simulation*>(p),count);}
void duck_eval(double target,double q,double velocity,double kp,double volts,double motor,double external,double* out){
    out[0]=duck_bam::torque(target,q,velocity,kp,volts);out[1]=duck_bam::friction(motor,external,velocity);
}
int duck_values(void* p,double* out){
    auto& s=*static_cast<Simulation*>(p);int n=0;
    for(int i=0;i<s.model->nq;++i)out[n++]=s.data->qpos[i];
    for(int i=0;i<s.model->nv;++i)out[n++]=s.data->qvel[i];
    for(int a:s.actuators)out[n++]=s.data->ctrl[a];
    for(int v:s.qvel)out[n++]=s.model->dof_frictionloss[v];
    for(int v:s.qvel)out[n++]=s.model->dof_damping[v];
    out[n++]=s.bam.effective_volts;return n;
}
void duck_obs(void* p,const float* prev,const float* cmd,float* out){build_observation(*static_cast<Simulation*>(p),prev,cmd,out);}
const char* duck_sensors(void* p){static thread_local std::string value;value=body_sensors(*static_cast<Simulation*>(p));return value.c_str();}
}
