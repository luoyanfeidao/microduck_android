// BAM M6 / XL330 CPU port, derived from bam/model.py, actuator.py and mujoco.py.
// Copyright 2025 Marc Duclusaud & Grégoire Passault
// Licensed under the Apache License, Version 2.0. See assets/licenses/BAM-NOTICES.txt.
#pragma once
#include <mujoco/mujoco.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>

namespace duck_bam {
constexpr double KT=.36601349688984386, R=2.8113923539223227;
constexpr double ARMATURE=.0018077432831600838;
constexpr double VISCOUS=.005359668274599504;
constexpr double ERROR_GAIN=4096.0/(2*3.14159265358979323846*256*885);
constexpr double VIN=7.4, DROP_GAIN=.1, VIN_MIN=6.0;
// Nominal deployment profile: deterministic voltage/sag, no current limiter or DR.
inline double torque(double target,double q,double velocity,double kp,double volts) {
    const double duty=std::clamp((target-q)*kp*ERROR_GAIN,-1.0,1.0);
    return KT*(volts*duty)/R-KT*KT*velocity/R;
}
inline double friction(double motor,double external,double velocity) {
    const double stribeck=std::exp(-std::pow(std::abs(velocity/2.890372094130307),8.683259907618984));
    double value=.004771183165566
        +std::abs(external*8.515871897059342e-06-motor*.2667860954283698)
        +stribeck*(.004676345799486616
            +std::abs(external*.08077928978935671-motor*1.0722918395099123e-05));
    auto sign=[](double x) { return (x>0)-(x<0); };
    if(sign(external)!=sign(motor)) {
        if(std::abs(external)<std::abs(motor)) value+=stribeck*.004902565732332559*external*external;
        if(std::abs(external)>std::abs(motor)) value+=stribeck*.009972471242139415*motor*motor;
    }
    return value;
}
class Actuators {
    struct Original {
        int actuator=0,joint=0,dof=0,qpos=0,gain_type=0,bias_type=0;
        mjtByte ctrl_limited=0,force_limited=0;
        double gain[mjNGAIN]{},bias[mjNBIAS]{},gear[6]{},force[2]{};
        double armature=0,damping=0,frictionloss=0,solref[mjNREF]{},solimp[mjNIMP]{};
    };
    std::array<Original,14> original{};
    std::array<double,14> previous_torque{};
    bool initialized=false;
public:
    bool enabled=false, powered=true;
    double kp=200, effective_volts=VIN;
    std::array<double,14> targets{};
    void configure(mjModel* m,mjData* d,const int* ids,bool on) {
        if(!initialized) {
            for(int i=0;i<14;++i) {
                auto& o=original[i]; o.actuator=ids[i]; int a=o.actuator;
                if(m->actuator_dyntype[a]!=mjDYN_NONE) throw std::runtime_error("BAM requires stateless joint actuators");
                o.joint=m->actuator_trnid[2*a]; o.dof=m->jnt_dofadr[o.joint]; o.qpos=m->jnt_qposadr[o.joint];
                o.gain_type=m->actuator_gaintype[a]; o.bias_type=m->actuator_biastype[a];
                o.ctrl_limited=m->actuator_ctrllimited[a]; o.force_limited=m->actuator_forcelimited[a];
                std::copy_n(m->actuator_gainprm+a*mjNGAIN,mjNGAIN,o.gain);
                std::copy_n(m->actuator_biasprm+a*mjNBIAS,mjNBIAS,o.bias);
                std::copy_n(m->actuator_gear+a*6,6,o.gear); std::copy_n(m->actuator_forcerange+a*2,2,o.force);
                o.armature=m->dof_armature[o.dof]; o.damping=m->dof_damping[o.dof]; o.frictionloss=m->dof_frictionloss[o.dof];
                std::copy_n(m->dof_solref+o.dof*mjNREF,mjNREF,o.solref);
                std::copy_n(m->dof_solimp+o.dof*mjNIMP,mjNIMP,o.solimp);
            }
            initialized=true;
        }
        enabled=on;
        for(const auto& o:original) {
            int a=o.actuator,v=o.dof;
            m->actuator_gaintype[a]=on?mjGAIN_FIXED:o.gain_type;
            m->actuator_biastype[a]=on?mjBIAS_NONE:o.bias_type;
            m->actuator_ctrllimited[a]=on?0:o.ctrl_limited;
            m->actuator_forcelimited[a]=on?1:o.force_limited;
            std::copy_n(o.gain,mjNGAIN,m->actuator_gainprm+a*mjNGAIN);
            std::copy_n(o.bias,mjNBIAS,m->actuator_biasprm+a*mjNBIAS);
            std::copy_n(o.gear,6,m->actuator_gear+a*6);
            std::copy_n(o.force,2,m->actuator_forcerange+a*2);
            std::copy_n(o.solref,mjNREF,m->dof_solref+v*mjNREF);
            std::copy_n(o.solimp,mjNIMP,m->dof_solimp+v*mjNIMP);
            m->dof_armature[v]=on?ARMATURE:o.armature;
            m->dof_damping[v]=on?0:o.damping; m->dof_frictionloss[v]=on?0:o.frictionloss;
            if(on) {
                std::fill_n(m->actuator_gainprm+a*mjNGAIN,mjNGAIN,0); m->actuator_gainprm[a*mjNGAIN]=1;
                std::fill_n(m->actuator_gear+a*6,6,0); m->actuator_gear[a*6]=1;
                m->actuator_forcerange[a*2]=-VIN*KT/R; m->actuator_forcerange[a*2+1]=VIN*KT/R;
                const double ref[mjNREF]={-5e4,-2e2},imp[mjNIMP]={.99,.9999,.001,.5,2};
                std::copy_n(ref,mjNREF,m->dof_solref+v*mjNREF); std::copy_n(imp,mjNIMP,m->dof_solimp+v*mjNIMP);
            }
        }
        mj_setConst(m,d);
        reset(m,d);
    }
    void reset(mjModel* m,mjData* d) {
        kp=200; powered=true; effective_volts=VIN; previous_torque.fill(0);
        if(!initialized) return;
        for(int i=0;i<14;++i) {
            const auto& o=original[i]; targets[i]=d->qpos[o.qpos];
            if(enabled) { d->ctrl[o.actuator]=0; m->dof_damping[o.dof]=0; m->dof_frictionloss[o.dof]=0; }
        }
    }
    void update(mjModel* m,mjData* d) {
        if(!enabled) return;
        double load=0; for(double t:previous_torque) load+=std::abs(t);
        effective_volts=std::max(VIN_MIN,VIN-DROP_GAIN*load);
        for(int i=0;i<14;++i) {
            const auto& o=original[i]; int v=o.dof;
            double t=powered?torque(targets[i],d->qpos[o.qpos],d->qvel[v],kp,effective_volts):0;
            // CPU BAM uses the previous solved forces for the next friction budget.
            double external=-d->qfrc_bias[v]+d->qfrc_constraint[v];
            for(int e=0;e<d->nefc;++e)
                if(d->efc_type[e]==mjCNSTR_FRICTION_DOF && d->efc_id[e]==o.joint) external-=d->efc_force[e];
            m->dof_frictionloss[v]=friction(d->qfrc_actuator[v],external,d->qvel[v]);
            m->dof_damping[v]=VISCOUS;
            if(!std::isfinite(t) || !std::isfinite(m->dof_frictionloss[v])) throw std::runtime_error("Non-finite BAM state");
            previous_torque[i]=t; d->ctrl[o.actuator]=t;
        }
    }
};
}
