package com.microduck.sim;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native body operations and release/torque semantics; all methods run on simulation worker. */
final class SimIoBody implements SimIoServer.Body {
    interface Control {
        boolean active();
        void pause();
        void start();
        void disconnected();
    }
    private final long handle;
    private final Control control;
    boolean connected,released;
    SimIoBody(long handle,Control control){this.handle=handle;this.control=control;}
    @Override public String apply(SimIoRequest request)throws Exception {
        if(!control.active())throw new IllegalStateException("Body service ended");
        switch(request.op){
            case "hello":
                control.pause();connected=true;released=false;NativeSim.bodyTorque(handle,false);NativeSim.bodyGain(handle,200);
                return "{\"protocol\":1}";
            case "read":return NativeSim.bodySensors(handle);
            case "write":NativeSim.bodyTargets(handle,request.targets);break;
            case "gain":NativeSim.bodyGain(handle,request.kp);break;
            case "torque":
                NativeSim.bodyTorque(handle,request.on);
                if(request.on)released=true;
                if(released)control.start(); // torque off is limp after first release
                break;
            case "slow":
                JSONArray temps=new JSONArray();for(int i=0;i<15;i++)temps.put(32.0);
                return new JSONObject().put("volts",7.4).put("temps_c",temps).put("simulated",true).toString();
            default:throw new IllegalArgumentException("Unsupported body request");
        }
        return "{}";
    }
    @Override public void disconnected(){
        if(!control.active())return;
        control.pause();NativeSim.bodyTorque(handle,false);connected=false;released=false;
        control.disconnected();
    }
}
