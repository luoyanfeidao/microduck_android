package com.microduck.sim;

import android.content.Context;
import org.json.JSONObject;
import java.util.Arrays;

/** Runs the production PolicyRunner, JNI, Rust Controller/Safety and MuJoCo with real ORT. */
public final class EmbeddedRustCheck {
    private static JSONObject state(long handle)throws Exception{return new JSONObject(NativeSim.state(handle));}
    private static void step(PolicyRunner policy,long handle)throws Exception{
        policy.tick(handle);NativeSim.advance(handle,4);policy.afterStep();
    }
    private static double pitch(long handle)throws Exception{
        double[] s=NativeSim.bodySample(handle);double w=s[36],x=s[37],y=s[38],z=s[39];
        return Math.atan2(2*(w*y-x*z),1-2*(x*x+y*y));
    }
    public float[] rustInfer(int net,float[] obs){throw new IllegalStateException("injected inference fault");}
    public static void main(String[] args)throws Exception{
        JSONObject report=new JSONObject();long h=NativeSim.open(args[0],"SIT");NativeSim.setBam(h,true);
        try(PolicyRunner policy=new PolicyRunner(new Context(args[1]))){
            NativeSim.resetHome(h);policy.reset();
            for(int i=0;i<250;i++)step(policy,h);
            JSONObject stand=state(h);if(!stand.getBoolean("finite")||stand.getDouble("trunk_z")<.095)throw new AssertionError(stand);
            report.put("stand_5s",stand);
            double before=stand.getDouble("root_x");policy.velocity(.30f,0,0);
            for(int i=0;i<150;i++)step(policy,h);
            JSONObject walk=state(h);double delta=walk.getDouble("root_x")-before;
            if(delta<.05||!walk.getBoolean("finite")||walk.getDouble("trunk_z")<.095)throw new AssertionError(walk);
            report.put("walk_3s_delta_x",delta);
            NativeSim.resetHome(h);policy.reset();policy.startRoulade();
            double last=pitch(h),travel=0;
            for(int i=0;i<100;i++){
                policy.startRoulade();step(policy,h);
                if(!policy.active.equals("roulade"))throw new AssertionError("Roll network changed early");
                double angle=pitch(h),d=angle-last;while(d>Math.PI)d-=2*Math.PI;while(d< -Math.PI)d+=2*Math.PI;
                travel+=d;last=angle;
            }
            if(policy.rolling()||Math.abs(Math.toDegrees(travel))<330)throw new AssertionError("Incomplete roll: "+travel);
            report.put("roll_pitch_travel_deg",Math.toDegrees(travel));
            step(policy,h);if(!policy.active.equals("alpha_stand"))throw new AssertionError("No handoff");
            for(int i=0;i<149;i++)step(policy,h);
            JSONObject recovered=state(h);if(!recovered.getBoolean("finite")||recovered.getDouble("trunk_z")<.095)throw new AssertionError(recovered);
            report.put("roll_recovery_3s",recovered);
            policy.filter=false;step(policy,h);policy.filter=true;step(policy,h);
            policy.startRoulade();step(policy,h);policy.velocity(.30f,0,0);step(policy,h);
            if(!policy.active.equals("alpha_walking"))throw new AssertionError("Cancel did not select walk");
            NativeSim.setBam(h,false);NativeSim.resetHome(h);policy.reset();
            for(int i=0;i<100;i++)step(policy,h);
            if(state(h).getDouble("trunk_z")<.095)throw new AssertionError("XML fallback fell");
            report.put("xml_fallback_2s",true);
            // JNI exception propagation must not emit/apply a target on failed inference.
            long core=RustControl.open();double[] sample=NativeSim.bodySample(h);
            try{RustControl.tick(core,sample,new double[3],true,new EmbeddedRustCheck());throw new AssertionError("Lost callback exception");}
            catch(IllegalStateException expected){if(!expected.getMessage().contains("injected"))throw expected;}
            if(!Arrays.equals(sample,NativeSim.bodySample(h)))throw new AssertionError("Failed inference mutated body");
            RustControl.close(core);
            try{RustControl.command(core,0);throw new AssertionError("Closed handle accepted");}catch(IllegalStateException expected){}
            report.put("jni_callback_failure_and_closed_handle",true);
            report.put("policy_smoke_max_abs_error",policy.smokeMaxError);
            report.put("production_policy_runner",true);
        }finally{NativeSim.close(h);}
        System.out.println(report);
    }
}
