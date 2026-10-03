package com.microduck.sim;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.graphics.Color;
import android.graphics.Typeface;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private TextView status, report;
    private volatile boolean destroyed;
    // Every native call, including deletion, runs on worker. No UI/render access to mjData.
    private long handle;
    private ScheduledFuture<?> ticker;
    private long startedAt, lastLog, expectedTick, lateTicks;
    private String keyframe = "SIT";
    private boolean ready;
    private PolicyRunner policies;
    private boolean policyEnabled;
    private double controlMs;
    private RobotSurfaceView preview;
    private boolean renderEnabled=true, realtimeTesting;
    private ScheduledFuture<?> realtimeStop;
    private double snapshotMs,controlWithVisualMs,maxLatenessMs;
    private double policyTickMs,physicsMs;
    private final VisualCadence visualCadence=new VisualCadence();
    private long realtimeFramesAtStart,realtimeStarted;
    private int realtimeSampleCount,realtimeOverBudget;
    private final double[] realtimeSamples=new double[2500];
    private final double[][] componentSamples=new double[4][2500];
    private final String[] componentNames={"policy_tick","physics_advance","visual_snapshot","worker_tick"};
    private int workerOverBudget,publishSamples;
    private double publishMaxMs,publishTotalMs;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 32, 24, 24);
        column.setBackgroundColor(Color.rgb(237, 244, 247));
        TextView title = new TextView(this);
        title.setText("Microduck · Android M3.1"); title.setTextSize(24);
        title.setTextColor(Color.rgb(23, 52, 75)); column.addView(title);
        TextView scope = new TextView(this);
        scope.setText("3D 物理仿真 + ONNX 站立 / 步行\n单指旋转、双指缩放；橙色轨迹、红色接触点。");
        scope.setTextSize(15); column.addView(scope);
        status = new TextView(this); status.setText("正在校验模型并加载 MuJoCo…");
        status.setTextSize(15); status.setTypeface(Typeface.MONOSPACE); column.addView(status);
        LinearLayout controls = row(column);
        button(controls,"运行",()->enqueue(this::start));
        button(controls,"暂停",()->enqueue(this::pause));
        button(controls,"步进 20ms",()->enqueue(()->{ pause(); controlStep(false); publish(NativeSim.state(handle)); }));
        LinearLayout resets = row(column);
        for(String name:new String[]{"SIT","STAND","INIT"}) {
            button(resets,name,()->enqueue(()->{ pause(); policyEnabled=false; if(policies!=null) policies.reset(); keyframe=name; publish(NativeSim.reset(handle,name)); }));
        }
        LinearLayout bench = row(column);
        button(bench,"基准测试 2000 步",()->enqueue(()->benchmark(2000)));
        LinearLayout policyButtons=row(column);
        button(policyButtons,"策略站立",()->enqueue(()->{ enablePolicy(0); start(); }));
        button(policyButtons,"前进 0.30m/s",()->enqueue(()->{ enablePolicy(.30f); start(); }));
        LinearLayout policyTests=row(column);
        button(policyTests,"策略测试 10s",()->enqueue(this::policyBenchmark));
        button(policyTests,"关闭策略",()->enqueue(()->{ pause(); policyEnabled=false; publish(NativeSim.state(handle)); }));
        preview=new RobotSurfaceView(this,error->main.post(()->{ if(!destroyed) report.setText("3D 渲染错误："+error); }));
        LinearLayout visualControls=row(column);
        button(visualControls,"复位视角",preview::resetCamera);
        button(visualControls,"实时测试 30s",()->enqueue(this::startRealtimeTest));
        CheckBox visual=new CheckBox(this); visual.setText("显示 3D"); visual.setChecked(true);
        visual.setOnCheckedChangeListener((v,checked)->{
            preview.setVisibility(checked?View.VISIBLE:View.GONE);
            worker.execute(()->{ renderEnabled=checked; if(checked) pushVisual(); });
        }); column.addView(visual);
        LinearLayout cameraControls=row(column);
        CheckBox follow=new CheckBox(this); follow.setText("跟随机器人"); follow.setChecked(true);
        follow.setOnCheckedChangeListener((v,checked)->preview.follow(checked)); cameraControls.addView(follow);
        CheckBox contacts=new CheckBox(this); contacts.setText("接触点"); contacts.setChecked(true);
        contacts.setOnCheckedChangeListener((v,checked)->preview.contacts(checked)); cameraControls.addView(contacts);
        CheckBox filter=new CheckBox(this); filter.setText("按桌面运行时过滤动作：头 .5 / 腿 .7"); filter.setChecked(true);
        filter.setOnCheckedChangeListener((v,checked)->enqueue(()->{ if(policies!=null) policies.filter=checked; })); column.addView(filter);
        TextView hint = new TextView(this);
        hint.setText("物理 dt=5ms；每 20ms 推理并推进 4 步。\n策略测试 10s 是快速计算基准；实时测试 30s 按墙钟运行，结束自动暂停。");
        hint.setTextSize(14); column.addView(hint);
        report = new TextView(this); report.setTextSize(13); report.setTypeface(Typeface.MONOSPACE);
        column.addView(report);
        ScrollView scroll = new ScrollView(this); scroll.addView(column);
        LinearLayout screen=new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL);
        column.removeView(title); screen.addView(title);
        int previewHeight=Math.round(Math.min(300*getResources().getDisplayMetrics().density,getResources().getDisplayMetrics().heightPixels*.4f));
        screen.addView(preview,new LinearLayout.LayoutParams(-1,previewHeight));
        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(screen);
        worker.execute(()->{
            try {
                File model = ModelAssets.prepare(this);
                String selected = getIntent().getStringExtra("keyframe");
                keyframe = selected == null ? "SIT" : selected;
                handle = NativeSim.open(new File(model,"scene.xml").getAbsolutePath(),keyframe);
                preview.meshes(NativeSim.visualMeshes(handle));
                ready = true; publish(NativeSim.state(handle));
                try { policies=new PolicyRunner(this); publish(NativeSim.state(handle)); }
                catch(Exception e) { Log.e("MicroduckM1","Policy load failed; M1 remains available",e); main.post(()->report.setText("策略加载失败，物理测试仍可用："+e.getMessage())); }
                int steps = getIntent().getIntExtra("benchmark_steps",0);
                if(steps > 0) benchmark(steps);
                else if(getIntent().getBooleanExtra("autostart",false)) start();
            } catch(Throwable e) { failed(e); }
        });
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this); parent.addView(r); return r;
    }
    private void button(LinearLayout row,String label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false);
        row.addView(b,new LinearLayout.LayoutParams(0, WRAP_CONTENT,1));
        b.setOnClickListener(v->action.run());
    }
    private void enqueue(Runnable action) {
        if(destroyed) return;
        worker.execute(()->{
            if(!ready) return;
            try { action.run(); } catch(Throwable e) { failed(e); }
        });
    }
    private void start() {
        if(ticker != null) return;
        startedAt=System.nanoTime(); lastLog=0; expectedTick=startedAt; lateTicks=0;
        // One bounded batch per control period; all model access remains serialized.
        ticker=worker.scheduleAtFixedRate(()->{
            try {
                long now=System.nanoTime();
                double lateness=Math.max(0,(now-expectedTick)/1e6); maxLatenessMs=Math.max(maxLatenessMs,lateness);
                if(now-expectedTick > 20_000_000L) lateTicks++;
                expectedTick+=20_000_000L;
                controlStep(true);
                if(now-lastLog >= 250_000_000L) {
                    long publishBegin=System.nanoTime();
                    JSONObject data=new JSONObject(NativeSim.state(handle));
                    data.put("wall_run_seconds",(now-startedAt)/1e9);
                    data.put("late_ticks",lateTicks); data.put("mode","running");
                    publish(data.toString(),false); lastLog=now;
                    if(realtimeTesting) {
                        double cost=(System.nanoTime()-publishBegin)/1e6;
                        publishSamples++; publishTotalMs+=cost; publishMaxMs=Math.max(publishMaxMs,cost);
                    }
                }
                if(realtimeTesting) {
                    double tickMs=(System.nanoTime()-now)/1e6;
                    if(realtimeSampleCount<realtimeSamples.length) {
                        int i=realtimeSampleCount++;
                        realtimeSamples[i]=controlWithVisualMs;
                        componentSamples[0][i]=policyTickMs; componentSamples[1][i]=physicsMs;
                        componentSamples[2][i]=snapshotMs; componentSamples[3][i]=tickMs;
                    }
                    if(controlWithVisualMs>20) realtimeOverBudget++;
                    if(tickMs>20) workerOverBudget++;
                }
            } catch(Throwable e) { failed(e); }
        },0,20,TimeUnit.MILLISECONDS);
    }
    private void pause() {
        if(ticker != null) { ticker.cancel(false); ticker=null; }
        if(realtimeStop!=null) { realtimeStop.cancel(false); realtimeStop=null; }
        realtimeTesting=false;
        visualCadence.reset();
    }
    private void benchmark(int count) {
        pause(); main.post(()->status.setText("基准测试中…"));
        policyEnabled=false;
        if("HOME".equals(keyframe)) { keyframe="SIT"; NativeSim.reset(handle,keyframe); }
        publish(NativeSim.benchmark(handle,count));
    }
    private void enablePolicy(float vx) {
        if(policies==null) throw new IllegalStateException("ONNX 未加载，请检查策略加载错误");
        if(!policyEnabled) {
            pause(); NativeSim.resetHome(handle); policies.reset(); keyframe="HOME";
            float[] obs=NativeSim.observation(handle,new float[14],new float[13]);
            for(int i=0;i<61;i++) if(Math.abs(obs[i]-(i==5?-1:0))>1e-5)
                throw new IllegalStateException("HOME observation mismatch at "+i);
            policyEnabled=true;
        }
        policies.velocity(vx,0,0);
    }
    private void controlStep(boolean withVisual) {
        long begin=System.nanoTime();
        try { if(policyEnabled) policies.tick(handle); }
        catch(Exception e) { throw new IllegalStateException("Policy inference failed",e); }
        long physicsBegin=System.nanoTime(); policyTickMs=(physicsBegin-begin)/1e6;
        NativeSim.advance(handle,4); physicsMs=(System.nanoTime()-physicsBegin)/1e6;
        controlMs=(System.nanoTime()-begin)/1e6;
        snapshotMs=0;
        if(withVisual && renderEnabled && visualCadence.due(System.nanoTime())) pushVisual();
        controlWithVisualMs=(System.nanoTime()-begin)/1e6;
    }
    private void pushVisual() {
        if(!renderEnabled || !ready || destroyed) return;
        long begin=System.nanoTime(); preview.frame(NativeSim.visualSnapshot(handle)); snapshotMs=(System.nanoTime()-begin)/1e6;
    }
    private void startRealtimeTest() {
        if(renderEnabled && !preview.ready()) throw new IllegalStateException("请等待 3D 画面加载完成；如有渲染错误，可关闭 3D 做对照测试");
        pause(); policyEnabled=false; enablePolicy(.30f);
        realtimeSampleCount=0; realtimeOverBudget=0; realtimeStarted=System.nanoTime();
        workerOverBudget=0; publishSamples=0; publishMaxMs=0; publishTotalMs=0;
        realtimeFramesAtStart=preview.frames(); maxLatenessMs=0; realtimeTesting=true; start();
        realtimeStop=worker.schedule(this::finishRealtimeTest,30,TimeUnit.SECONDS);
    }
    private void finishRealtimeTest() {
        double wall=(System.nanoTime()-realtimeStarted)/1e9; int samples=realtimeSampleCount;
        pause(); double[] sorted=Arrays.copyOf(realtimeSamples,samples); Arrays.sort(sorted);
        try {
            JSONObject data=new JSONObject(NativeSim.state(handle)); data.put("mode","realtime_render_test");
            data.put("realtime_wall_seconds",wall); data.put("realtime_samples",samples);
            data.put("realtime_sim_wall_ratio",data.getDouble("sim_time")/wall);
            data.put("late_ticks",lateTicks); data.put("scheduler_max_lateness_ms",maxLatenessMs);
            data.put("control_over_20ms_ticks",realtimeOverBudget);
            data.put("worker_over_20ms_ticks",workerOverBudget);
            data.put("publish_samples",publishSamples); data.put("publish_max_ms",publishMaxMs);
            data.put("publish_mean_ms",publishSamples>0?publishTotalMs/publishSamples:0);
            data.put("test_render_frames",preview.frames()-realtimeFramesAtStart);
            data.put("test_render_fps",(preview.frames()-realtimeFramesAtStart)/wall);
            if(samples>0) {
                data.put("control_with_visual_p95_ms",sorted[(int)((samples-1)*.95)]);
                data.put("control_with_visual_p99_ms",sorted[(int)((samples-1)*.99)]);
                data.put("control_with_visual_max_ms",sorted[samples-1]);
                for(int c=0;c<componentSamples.length;c++) {
                    double[] values=Arrays.copyOf(componentSamples[c],samples); Arrays.sort(values);
                    data.put(componentNames[c]+"_p95_ms",values[(int)((samples-1)*.95)]);
                    data.put(componentNames[c]+"_p99_ms",values[(int)((samples-1)*.99)]);
                    data.put(componentNames[c]+"_max_ms",values[samples-1]);
                }
            }
            publish(data.toString());
        } catch(Exception e) { failed(e); }
    }
    private void policyBenchmark() {
        pause(); policyEnabled=false; enablePolicy(.30f);
        main.post(()->status.setText("策略测试中：前进 0.30m/s，500 个控制周期…"));
        double[] ms=new double[500]; long begin=System.nanoTime();
        for(int i=0;i<ms.length;i++) { controlStep(false); ms[i]=controlMs; }
        double wall=(System.nanoTime()-begin)/1e9; Arrays.sort(ms);
        try {
            JSONObject data=new JSONObject(NativeSim.state(handle)); data.put("mode","policy_benchmark");
            data.put("policy_control_ticks",500); data.put("policy_wall_seconds",wall);
            data.put("policy_realtime_factor",10/wall); data.put("control_p50_ms",ms[249]);
            data.put("control_p95_ms",ms[474]); data.put("control_p99_ms",ms[494]);
            data.put("control_max_ms",ms[499]); publish(data.toString());
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void publish(String raw) {
        publish(raw,true);
    }
    private void publish(String raw,boolean refreshVisual) {
        try {
            if(refreshVisual) pushVisual();
            JSONObject data=new JSONObject(raw);
            data.put("model","scene.xml → robot_groundcontact.xml");
            data.put("stage",policyEnabled?"M2: ONNX + XML position servo":"M1: position servo only; no policy");
            data.put("policy_enabled",policyEnabled); data.put("control_ms",controlMs);
            data.put("app_stage","M3.1"); data.put("render_enabled",renderEnabled);
            data.put("visual_target_fps",VisualCadence.FPS);
            data.put("policy_tick_ms",policyTickMs); data.put("physics_advance_ms",physicsMs);
            data.put("visual_snapshot_ms",snapshotMs); data.put("control_with_visual_ms",controlWithVisualMs);
            preview.annotate(data);
            if(policies!=null) policies.annotate(data);
            data.put("device",android.os.Build.MODEL);
            data.put("android_api",android.os.Build.VERSION.SDK_INT);
            data.put("keyframe_requested",keyframe);
            byte[] reportBytes=data.toString(2).getBytes(StandardCharsets.UTF_8);
            Files.write(new File(getFilesDir(),"last-report.json").toPath(),reportBytes);
            // Preserve completed tests across pose resets and Activity recreation.
            String archive="realtime_render_test".equals(data.optString("mode")) ? "last-realtime-test.json"
                : data.has("policy_control_ticks") ? "last-policy-benchmark.json"
                : data.has("benchmark_steps") ? "last-physics-benchmark.json" : null;
            if(archive!=null) Files.write(new File(getFilesDir(),archive).toPath(),reportBytes);
            Log.i("MicroduckM1",data.toString());
            String text=String.format(Locale.ROOT,
                "MuJoCo %s / %s\nbody=%d  nq=%d  nv=%d  actuator=%d\nmesh=%d  dt=%.3f s\n仿真时间 %.3f s / %d 步\n躯干高度 %.4f m\n位移 x %.3f / y %.3f m\n接触 %d / 左脚 %d / 右脚 %d\n有限数值 %s / 脚底匹配 %s\n最近批次 %.3f ms / 20ms 预算",
                data.getString("mujoco"),data.getString("keyframe"),
                data.getInt("nbody"),data.getInt("nq"),data.getInt("nv"),data.getInt("nu"),
                data.getInt("nmesh"),data.getDouble("timestep"),data.getDouble("sim_time"),data.getLong("steps"),
                data.getDouble("trunk_z"),data.getDouble("root_x"),data.getDouble("root_y"),data.getInt("contacts"),data.getInt("left_contacts"),data.getInt("right_contacts"),
                data.getBoolean("finite"),data.getBoolean("foot_geoms_resolved"),data.getDouble("last_batch_ms"));
            String details=data.has("benchmark_steps") ? String.format(Locale.ROOT,
                "\n基准 %d 步（另暖机 100 步）\n墙钟 %.3f s / 物理实时倍率 %.2f×\n单步平均 %.2f μs\np50 %.2f / p95 %.2f / p99 %.2f μs\n最大单步 %.2f μs\n这是物理基准；不含 ONNX、渲染或视频。",
                data.getInt("benchmark_steps"),data.getDouble("wall_seconds"),data.getDouble("realtime_factor"),
                data.getDouble("step_mean_us"),data.getDouble("step_p50_us"),data.getDouble("step_p95_us"),
                data.getDouble("step_p99_us"),data.getDouble("step_max_us")) : "";
            if(policies!=null) details+=String.format(Locale.ROOT,
                "\n策略 %s / 启用 %s\nONNX %.3f ms / 控制周期 %.3f ms\n推理对照最大误差 %.7f\n%s",
                data.getString("policy"),policyEnabled,data.getDouble("inference_ms"),controlMs,
                data.getDouble("policy_smoke_max_abs_error"),data.getString("action_filter"));
            if(data.has("policy_control_ticks")) details+=String.format(Locale.ROOT,
                "\n策略 500 周期 / %.3f s / %.2f×\n控制 p95 %.3f / p99 %.3f / max %.3f ms",
                data.getDouble("policy_wall_seconds"),data.getDouble("policy_realtime_factor"),
                data.getDouble("control_p95_ms"),data.getDouble("control_p99_ms"),data.getDouble("control_max_ms"));
            details+=String.format(Locale.ROOT,"\n3D %s / %.1f FPS / CPU提交 %.3f ms\n快照 %.3f ms / GPU %s",
                data.getBoolean("render_ready"),data.getDouble("render_fps"),data.getDouble("render_cpu_ms"),snapshotMs,data.getString("gl_renderer"));
            if(data.has("realtime_wall_seconds")) details+=String.format(Locale.ROOT,
                "\n实时测试：墙钟 %.2fs / 仿真 %.2fs\n画面 %.1f FPS / 控制p99 %.3f ms\n超20ms周期 %d / 调度迟到 %d\n工作线程p99 %.3f ms / 超20ms %d",
                data.getDouble("realtime_wall_seconds"),data.getDouble("sim_time"),data.getDouble("test_render_fps"),
                data.optDouble("control_with_visual_p99_ms",0),data.getInt("control_over_20ms_ticks"),data.getLong("late_ticks"),
                data.optDouble("worker_tick_p99_ms",0),data.optInt("worker_over_20ms_ticks",0));
            final String shownDetails=details;
            main.post(()->{ if(!destroyed) { status.setText(text); report.setText(shownDetails); } });
        } catch(Exception e) { throw new IllegalStateException("Cannot publish simulation state",e); }
    }
    private void failed(Throwable e) {
        pause(); policyEnabled=false; Log.e("MicroduckM1","Simulation error",e);
        String text="暂停："+e.getClass().getSimpleName()+"\n"+e.getMessage();
        main.post(()->{ if(!destroyed) status.setText(text); });
    }
    @Override protected void onStop() {
        super.onStop();
        if(!destroyed) worker.execute(this::pause); // M1 runs only while its Activity is visible.
    }
    @Override protected void onResume() { super.onResume(); if(preview!=null) preview.onResume(); }
    @Override protected void onPause() { if(preview!=null) preview.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed=true;
        worker.execute(()->{ pause(); if(policies!=null) policies.close(); if(handle!=0) { NativeSim.close(handle); handle=0; } });
        worker.shutdown(); super.onDestroy();
    }
}
