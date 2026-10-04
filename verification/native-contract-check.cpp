// Desktop-only check of the exact C++ helpers called by Android JNI.
#include "../app/src/main/cpp/native_sim.cpp"
#include <fstream>
#include <iostream>
#include <limits>
int main(int argc,char** argv) {
    if(argc!=3) return 2;
    Simulation s; char error[2048]={}; s.model=mj_loadXML(argv[1],nullptr,error,sizeof(error));
    if(!s.model) { std::cerr<<error; return 2; }
    s.data=mj_makeData(s.model);
    int root=mj_name2id(s.model,mjOBJ_JOINT,"trunk_base_freejoint");
    s.root_qpos=s.model->jnt_qposadr[root]; s.root_dof=s.model->jnt_dofadr[root];
    for(int i=0;i<N;++i) {
        int j=mj_name2id(s.model,mjOBJ_JOINT,JOINTS[i]); s.qpos[i]=s.model->jnt_qposadr[j]; s.qvel[i]=s.model->jnt_dofadr[j];
        for(int a=0;a<s.model->nu;++a) if(s.model->actuator_trnid[2*a]==j) s.actuators[i]=a;
        s.base_gain[i]=s.model->actuator_gainprm[s.actuators[i]*mjNGAIN];
    }
    std::ifstream f(argv[2]);
    auto read=[&](auto* array,int n) { for(int i=0;i<n;++i) if(!(f>>array[i])) throw std::runtime_error("Invalid fixture"); };
    read(s.data->qpos,s.model->nq); read(s.data->qvel,s.model->nv);
    float prev[N],cmd[13],obs[61],expected[61],action[N];
    read(prev,N); read(cmd,13); read(expected,61); read(action,N);
    build_observation(s,prev,cmd,obs); double max_error=0;
    for(int i=0;i<61;++i) max_error=std::max(max_error,std::abs(double(obs[i])-expected[i]));
    if(max_error>1e-6) throw std::runtime_error("Observation differs from Python MuJoCo reference");
    apply_policy_action(s,action,false,true);
    double prior[N];
    for(int i=0;i<N;++i) {
        prior[i]=HOME[i]+.9*action[i];
        if(std::abs(prior[i]-s.data->ctrl[s.actuators[i]])>1e-12) throw std::runtime_error("Walk scaling mismatch");
    }
    apply_policy_action(s,action,true,true);
    for(int i=0;i<N;++i) {
        double alpha=(i>=5 && i<=8)?.5:.7;
        double target=alpha*(HOME[i]+action[i])+(1-alpha)*prior[i];
        if(std::abs(target-s.data->ctrl[s.actuators[i]])>1e-12 ||
           std::abs(s.model->actuator_gainprm[s.actuators[i]*mjNGAIN]-.8*s.base_gain[i])>1e-12)
            throw std::runtime_error("Standing filter/gain mismatch");
    }
    apply_scaled_action(s,action,1.0,1.0,false);
    for(int i=0;i<N;++i) {
        if(std::abs(s.data->ctrl[s.actuators[i]]-(HOME[i]+action[i]))>1e-12 ||
           s.model->actuator_gainprm[s.actuators[i]*mjNGAIN]!=s.base_gain[i])
            throw std::runtime_error("Roulade full scale/gain mismatch");
    }
    action[0]=std::numeric_limits<float>::quiet_NaN(); bool rejected=false;
    try { apply_policy_action(s,action,true,true); } catch(const std::runtime_error&) { rejected=true; }
    if(!rejected) throw std::runtime_error("NaN action accepted");
    Simulation baseline; baseline.model=mj_copyModel(nullptr,s.model); baseline.data=mj_makeData(baseline.model);
    mj_copyData(baseline.data,baseline.model,s.data);
    for(int tick=0;tick<500;++tick) {
        advance(s,4);
        for(int step=0;step<4;++step) one_step(baseline);
        mj_forward(baseline.model,baseline.data);
        for(int i=0;i<s.model->nq;++i) if(s.data->qpos[i]!=baseline.data->qpos[i]) throw std::runtime_error("Advance qpos regression");
        for(int i=0;i<s.model->nv;++i) if(s.data->qvel[i]!=baseline.data->qvel[i]) throw std::runtime_error("Advance qvel regression");
        if(s.data->time!=baseline.data->time || s.data->ncon!=baseline.data->ncon || s.steps!=baseline.steps)
            throw std::runtime_error("Advance time/contact regression");
        for(int i=0;i<s.model->ngeom*3;++i) if(s.data->geom_xpos[i]!=baseline.data->geom_xpos[i])
            throw std::runtime_error("Advance transform regression");
    }
    auto steps=s.steps; rejected=false;
    try { advance(s,0); } catch(const std::runtime_error&) { rejected=true; }
    if(!rejected || s.steps!=steps) throw std::runtime_error("Invalid advance accepted");
    std::cout<<"{\"observation_max_error\":"<<max_error<<",\"action_scale_filter_gain\":\"passed\",\"nan_rejection\":\"passed\","
             <<"\"advance_baseline_500_ticks_exact\":true,\"invalid_step_rejection\":true,\"roulade_scale_gain\":true}\n";
}
