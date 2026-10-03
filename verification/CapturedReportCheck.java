package com.microduck.sim;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class CapturedReportCheck {
    public static void main(String[] args) throws Exception {
        // A queued native snapshot must retain its values while later simulation reports change.
        CapturedReport first=new CapturedReport("{\"sim_time\":1,\"mode\":\"running\",\"root_quaternion\":[1,0,0,0]}",
            new JSONObject().put("running",true).put("velocity_command",new JSONArray(new double[]{.3,0,0})));
        CapturedReport completed=new CapturedReport("{\"sim_time\":30.02,\"mode\":\"realtime_render_test\",\"test_video_frames_published\":298}",
            new JSONObject().put("running",false).put("app_stage","M5.1"));
        ExecutorService report=Executors.newSingleThreadExecutor();
        try {
            Future<JSONObject> result=report.submit(first::materialize); JSONObject value=result.get();
            if(value.getDouble("sim_time")!=1 || !value.getBoolean("running")
                || value.getJSONArray("velocity_command").getDouble(0)!=.3
                || value.getJSONArray("root_quaternion").length()!=4) throw new AssertionError("Captured values changed");
            JSONObject end=report.submit(completed::materialize).get();
            if(!end.getString("mode").equals("realtime_render_test") || end.getInt("test_video_frames_published")!=298
                || end.getBoolean("running")) throw new AssertionError("Completed report lost archive fields");
            CapturedReport bad=new CapturedReport("broken",new JSONObject());
            try { bad.materialize(); throw new AssertionError("Invalid native JSON accepted"); }
            catch(org.json.JSONException expected) { /* Failure occurs during reporting, not capture. */ }
        } finally { report.shutdownNow(); }
        System.out.println("{\"captured_values_survive_deferred_parse\":true,\"completed_archive_fields_preserved\":true,\"invalid_native_json_rejected\":true}");
    }
}
