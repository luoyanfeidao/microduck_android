package com.microduck.sim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Host JNI harness using the production server, body adapter and MuJoCo/BAM implementation. */
public final class SimIoNativeHarness {
    public static void main(String[] args)throws Exception {
        ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
        AtomicBoolean active=new AtomicBoolean(true);boolean[] running={false};int[] disconnected={0};
        long handle=worker.submit(()->{long h=NativeSim.open(args[0],"SIT");NativeSim.setBam(h,true);return h;}).get();
        SimIoBody body=new SimIoBody(handle,new SimIoBody.Control(){
            public boolean active(){return active.get();}
            public void pause(){running[0]=false;}
            public void start(){running[0]=true;}
            public void disconnected(){disconnected[0]++;}
        });
        worker.scheduleAtFixedRate(()->{if(running[0])NativeSim.advance(handle,4);},0,20,TimeUnit.MILLISECONDS);
        try(SimIoServer server=new SimIoServer(0,worker,body)){
            System.out.println(server.port());System.out.flush();
            BufferedReader commands=new BufferedReader(new InputStreamReader(System.in));
            for(String command;(command=commands.readLine())!=null;){
            boolean finish=!command.equals("state");
            String result=worker.submit(()->{
                JSONObject state=new JSONObject(NativeSim.state(handle));
                state.put("body_connected",body.connected).put("running",running[0]).put("disconnect_callbacks",disconnected[0]);
                if(finish && (body.connected || running[0] || state.getBoolean("torque_on") || disconnected[0]<2))
                    throw new AssertionError("Disconnected Rust still controls body");
                return state.toString();
            }).get(2,TimeUnit.SECONDS);
            System.out.println(result);
            System.out.flush();if(finish)break;
            }
        }finally{
            active.set(false);
            worker.submit(()->{running[0]=false;NativeSim.close(handle);}).get();worker.shutdownNow();
        }
    }
}
