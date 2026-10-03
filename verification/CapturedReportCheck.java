package com.microduck.sim;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class CapturedReportCheck {
    public static void main(String[] args) throws Exception {
        // A queued native snapshot must retain its values while later simulation reports change.
        AtomicInteger writes=new AtomicInteger(); AtomicReference<Thread> writer=new AtomicReference<>();
        double[] command={.3,0,0}; double vx=command[0],vy=command[1],yaw=command[2];
        CapturedReport first=new CapturedReport("{\"sim_time\":1,\"mode\":\"running\",\"root_quaternion\":[1,0,0,0]}",data->{
            writes.incrementAndGet(); writer.set(Thread.currentThread());
            data.put("running",true).put("velocity_command",new JSONArray(new double[]{vx,vy,yaw}));
        });
        command[0]=-.3;
        if(writes.get()!=0) throw new AssertionError("Metadata JSON built on capture thread");
        CapturedReport completed=new CapturedReport("{\"sim_time\":30.02,\"mode\":\"realtime_render_test\",\"test_video_frames_published\":298}",
            data->data.put("running",false).put("app_stage","M5.2"));
        ExecutorService report=Executors.newSingleThreadExecutor();
        try {
            Future<JSONObject> result=report.submit(first::materialize); JSONObject value=result.get();
            if(writes.get()!=1 || writer.get()==Thread.currentThread()) throw new AssertionError("Wrong metadata writer thread");
            if(value.getDouble("sim_time")!=1 || !value.getBoolean("running")
                || value.getJSONArray("velocity_command").getDouble(0)!=.3
                || value.getJSONArray("root_quaternion").length()!=4) throw new AssertionError("Captured values changed");
            JSONObject end=report.submit(completed::materialize).get();
            if(!end.getString("mode").equals("realtime_render_test") || end.getInt("test_video_frames_published")!=298
                || end.getBoolean("running")) throw new AssertionError("Completed report lost archive fields");
            CapturedReport bad=new CapturedReport("broken",data->data.put("ignored",true));
            try { bad.materialize(); throw new AssertionError("Invalid native JSON accepted"); }
            catch(org.json.JSONException expected) { /* Failure occurs during reporting, not capture. */ }
        } finally { report.shutdownNow(); }
        System.out.println("{\"captured_values_survive_deferred_parse\":true,\"completed_archive_fields_preserved\":true,\"invalid_native_json_rejected\":true,"
            +"\"metadata_json_built_only_on_report_thread\":true,\"command_changes_do_not_change_captured_velocity\":true}");
    }
}
