package com.microduck.sim;

import org.json.JSONObject;
import java.util.Iterator;

/** Validated immutable LAN command. Only the simulation worker applies it. */
final class RemoteCommand {
    final String op,pose;
    final float vx,vy,yaw;
    private RemoteCommand(String op,String pose,float vx,float vy,float yaw) {
        this.op=op; this.pose=pose; this.vx=vx; this.vy=vy; this.yaw=yaw;
    }
    static RemoteCommand parse(String text) throws Exception {
        JSONObject data=new JSONObject(text);
        Object value=data.get("op");
        if(!(value instanceof String)) throw new IllegalArgumentException("op must be a string");
        String op=(String)value;
        String allowed;
        switch(op) {
            case "stand": case "pause": case "heartbeat": allowed="op"; break;
            case "walk": allowed="op,vx,vy,yaw"; break;
            case "reset": allowed="op,pose"; break;
            default: throw new IllegalArgumentException("Unknown command");
        }
        for(Iterator<String> it=data.keys();it.hasNext();) {
            String key=it.next(); boolean known=false;
            for(String field:allowed.split(",")) if(field.equals(key)) known=true;
            if(!known) throw new IllegalArgumentException("Unknown command field");
        }
        String pose="SIT";
        if(op.equals("reset")) {
            Object p=data.get("pose");
            if(!(p instanceof String) || !(p.equals("SIT") || p.equals("STAND") || p.equals("INIT")))
                throw new IllegalArgumentException("Invalid reset pose");
            pose=(String)p;
        }
        return new RemoteCommand(op,pose,number(data,"vx",.30f),number(data,"vy",.15f),number(data,"yaw",.50f));
    }
    private static float number(JSONObject data,String key,float limit) throws Exception {
        if(!data.has(key)) return 0;
        Object raw=data.get(key);
        if(!(raw instanceof Number)) throw new IllegalArgumentException("Velocity must be numeric");
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value) || Math.abs(value)>limit) throw new IllegalArgumentException("Velocity outside supported range");
        return (float)value;
    }
}
