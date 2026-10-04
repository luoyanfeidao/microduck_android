package com.microduck.sim;

import org.json.JSONArray;
import org.json.JSONObject;

/** Protocol 1 of microduck/duck-control/src/sim.rs; wire slot 9 is mouth. */
final class SimIoRequest {
    final String op;
    final double[] targets;
    final int kp;
    final boolean on;
    private SimIoRequest(String op,double[] targets,int kp,boolean on) {
        this.op=op;this.targets=targets;this.kp=kp;this.on=on;
    }
    static SimIoRequest parse(String line) throws Exception {
        JSONObject value=new JSONObject(line);
        Object tag=value.get("op");
        if(!(tag instanceof String)) throw new IllegalArgumentException("op must be a string");
        String op=(String)tag;double[] targets=null;int kp=0;boolean on=false;int fields=1;
        switch(op) {
            case "hello":
                fields=3;
                if(number(value.get("protocol"))!=1 || number(value.get("joints"))!=15)
                    throw new IllegalArgumentException("Android body requires protocol 1 and 15 joints");
                break;
            case "read": case "slow": break;
            case "write":
                fields=2;JSONArray array=value.getJSONArray("targets");
                if(array.length()!=15) throw new IllegalArgumentException("Expected 15 targets");
                targets=new double[15];
                for(int i=0;i<15;i++) {
                    targets[i]=number(array.get(i));
                    if(Math.abs(targets[i])>100) throw new IllegalArgumentException("Target outside +/-100 rad");
                }
                break;
            case "gain":
                fields=2;double gain=number(value.get("kp"));
                if(gain<0 || gain>65535 || gain!=Math.rint(gain)) throw new IllegalArgumentException("kp must be uint16");
                kp=(int)gain;break;
            case "torque":
                fields=2;Object enabled=value.get("on");
                if(!(enabled instanceof Boolean)) throw new IllegalArgumentException("on must be boolean");
                on=(Boolean)enabled;break;
            default: throw new IllegalArgumentException("Unsupported SimIo operation");
        }
        if(value.length()!=fields) throw new IllegalArgumentException("Unexpected fields");
        return new SimIoRequest(op,targets,kp,on);
    }
    private static double number(Object value) {
        if(!(value instanceof Number)) throw new IllegalArgumentException("Expected a number");
        double result=((Number)value).doubleValue();
        if(!Double.isFinite(result)) throw new IllegalArgumentException("Non-finite number");
        return result;
    }
}
