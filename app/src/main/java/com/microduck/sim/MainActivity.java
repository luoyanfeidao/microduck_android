package com.microduck.sim;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.os.Bundle;
import android.os.Debug;
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
import org.json.JSONArray;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ReportDispatcher reports=new ReportDispatcher(error->{
        Log.e("MicroduckM1","Report write failed",error);
        enqueue(()->failed(new IllegalStateException("Cannot save simulation report",error)));
    });
    private final CapturedReport.Writer reportWriter=this::writeReport;
    private TextView status, report;
    private TextView networkInfo;
    private LanControlServer lan;
    private VideoCapture video;
    private VisualMesh[] visualMeshes;
    private CameraScene cameraScene;
    private long lanGeneration,remoteCommandsApplied;
    private final RemoteLease remoteLease=new RemoteLease();
    private final RemoteMailbox remoteMailbox=new RemoteMailbox(worker);
    private volatile String remoteState="{\"ready\":false,\"running\":false}";
    private String remoteStopReason="";
    private volatile boolean destroyed;
    private volatile boolean foreground;
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
    private long videoPublishedAtStart,videoServedAtStart;
    private int realtimeSampleCount,realtimeOverBudget;
    private final double[] realtimeSamples=new double[2500];
    private final double[][] componentSamples=new double[14][2500];
    private final String[] componentNames={"policy_tick","physics_advance","visual_snapshot","worker_tick","native_state_capture","report_metadata_submit",
        "app_metadata_capture","render_metadata_capture","video_metadata_capture","policy_metadata_capture","report_queue_submit",
        "report_admission","report_mailbox","report_submit_cpu"};
    private final double[] metadataCaptureMs=new double[8];
    private final long[] reportSubmitTiming=new long[2];
    private final double[][] overBudgetSamples=new double[16][13];
    private int overBudgetSampleCount;
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
        title.setText("Microduck · Android M8"); title.setTextSize(24);
        title.setTextColor(Color.rgb(23, 52, 75)); column.addView(title);
        TextView scope = new TextView(this);
        scope.setText("3D 物理仿真 + ONNX 站立 / 步行 / 前滚翻\n单指旋转、双指缩放；橙色轨迹、红色接触点。");
        scope.setTextSize(15); column.addView(scope);
        status = new TextView(this); status.setText("正在校验模型并加载 MuJoCo…");
        status.setTextSize(15); status.setTypeface(Typeface.MONOSPACE); column.addView(status);
        LinearLayout controls = row(column);
        button(controls,"运行",()->enqueueLocal(this::start));
        button(controls,"暂停",()->enqueueLocal(()->{ pause(); publish(NativeSim.state(handle)); }));
        button(controls,"步进 20ms",()->enqueueLocal(()->{ pause(); controlStep(false); publish(NativeSim.state(handle)); }));
        LinearLayout resets = row(column);
        for(String name:new String[]{"SIT","STAND","INIT"}) {
            button(resets,name,()->enqueueLocal(()->{ pause(); policyEnabled=false; if(policies!=null) policies.reset(); keyframe=name; publish(NativeSim.reset(handle,name)); }));
        }
        LinearLayout bench = row(column);
        button(bench,"基准测试 2000 步",()->enqueueLocal(()->benchmark(2000)));
        LinearLayout policyButtons=row(column);
        button(policyButtons,"策略站立",()->enqueueLocal(()->{ enablePolicy(0); start(); }));
        button(policyButtons,"前进 0.30m/s",()->enqueueLocal(()->{ enablePolicy(.30f); start(); }));
        LinearLayout skillButtons=row(column);
        button(skillButtons,"向前翻滚一次",()->enqueueLocal(()->{ enableRoulade(); start(); }));
        LinearLayout policyTests=row(column);
        button(policyTests,"策略测试 10s",()->enqueueLocal(this::policyBenchmark));
        button(policyTests,"关闭策略",()->enqueueLocal(()->{ pause(); policyEnabled=false; publish(NativeSim.state(handle)); }));
        preview=new RobotSurfaceView(this,error->main.post(()->{ if(!destroyed) report.setText("3D 渲染错误："+error); }));
        LinearLayout visualControls=row(column);
        button(visualControls,"复位视角",preview::resetCamera);
        button(visualControls,"实时测试 30s",()->enqueueLocal(this::startRealtimeTest));
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
        filter.setOnCheckedChangeListener((v,checked)->enqueueLocal(()->{ if(policies!=null) policies.filter=checked; })); column.addView(filter);
        TextView hint = new TextView(this);
        hint.setText("物理 dt=5ms；每 20ms 推理并推进 4 步。\n策略测试 10s 是快速计算基准；实时测试 30s 按墙钟运行，结束自动暂停。");
        hint.setTextSize(14); column.addView(hint);
        report = new TextView(this); report.setTextSize(13); report.setTypeface(Typeface.MONOSPACE);
        column.addView(report);
        LinearLayout networkButtons=row(column);
        button(networkButtons,"开启局域网控制",()->enqueue(this::startLan));
        button(networkButtons,"关闭局域网控制",()->enqueue(()->{ pause(); stopLan(); publish(NativeSim.state(handle)); }));
        networkInfo=new TextView(this); networkInfo.setText("局域网控制未开启；另一台 Android 可用浏览器连接。");
        networkInfo.setTextIsSelectable(true); column.addView(networkInfo);
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
                visualMeshes=NativeSim.visualMeshes(handle);
                preview.meshes(visualMeshes);
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
    private void enqueueLocal(Runnable action) {
        enqueue(()->{ remoteLease.release(); action.run(); });
    }
    private void start() {
        if(ticker != null) return;
        startedAt=System.nanoTime(); lastLog=0; expectedTick=startedAt; lateTicks=0;
        // One bounded batch per control period; all model access remains serialized.
        ticker=worker.scheduleAtFixedRate(()->{
            try {
                long now=System.nanoTime();
                if(remoteLease.expired(now)) {
                    remoteStopReason="heartbeat_timeout"; pause(); publish(NativeSim.state(handle)); return;
                }
                double lateness=Math.max(0,(now-expectedTick)/1e6); maxLatenessMs=Math.max(maxLatenessMs,lateness);
                if(now-expectedTick > 20_000_000L) lateTicks++;
                expectedTick+=20_000_000L;
                controlStep(true);
                Arrays.fill(metadataCaptureMs,0);
                double nativeStateMs=0,reportSubmitMs=0;
                if(now-lastLog >= 250_000_000L) {
                    long publishBegin=System.nanoTime();
                    String raw=NativeSim.state(handle); long nativeDone=System.nanoTime();
                    nativeStateMs=(nativeDone-publishBegin)/1e6;
                    publish(raw,false,false,(now-startedAt)/1e9,lateTicks); lastLog=now;
                    reportSubmitMs=(System.nanoTime()-nativeDone)/1e6;
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
                        componentSamples[4][i]=nativeStateMs; componentSamples[5][i]=reportSubmitMs;
                        for(int part=0;part<metadataCaptureMs.length;part++) componentSamples[6+part][i]=metadataCaptureMs[part];
                    }
                    if(controlWithVisualMs>20) realtimeOverBudget++;
                    if(tickMs>20) {
                        workerOverBudget++;
                        if(overBudgetSampleCount<overBudgetSamples.length) {
                            double[] row=overBudgetSamples[overBudgetSampleCount++];
                            row[0]=realtimeSampleCount-1; row[1]=tickMs; row[2]=controlWithVisualMs;
                            row[3]=nativeStateMs; row[4]=reportSubmitMs;
                            System.arraycopy(metadataCaptureMs,0,row,5,metadataCaptureMs.length);
                        }
                    }
                }
            } catch(Throwable e) { failed(e); }
        },0,20,TimeUnit.MILLISECONDS);
    }
    private void pause() {
        if(ticker != null) { ticker.cancel(false); ticker=null; }
        if(realtimeStop!=null) { realtimeStop.cancel(false); realtimeStop=null; }
        realtimeTesting=false;
        remoteLease.release();
        visualCadence.reset();
    }
    private void benchmark(int count) {
        pause(); main.post(()->status.setText("基准测试中…"));
        policyEnabled=false;
        if("HOME".equals(keyframe)) { keyframe="SIT"; NativeSim.reset(handle,keyframe); }
        publishTest(NativeSim.benchmark(handle,count));
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
    private void enableRoulade() {
        if(policyEnabled && policies!=null && policies.rolling()) return;
        enablePolicy(0); policies.startRoulade();
    }
    private void controlStep(boolean withVisual) {
        long begin=System.nanoTime();
        try { if(policyEnabled) policies.tick(handle); }
        catch(Exception e) { throw new IllegalStateException("Policy inference failed",e); }
        long physicsBegin=System.nanoTime(); policyTickMs=(physicsBegin-begin)/1e6;
        NativeSim.advance(handle,4); physicsMs=(System.nanoTime()-physicsBegin)/1e6;
        if(policyEnabled) policies.afterStep();
        controlMs=(System.nanoTime()-begin)/1e6;
        snapshotMs=0;
        if(withVisual && (renderEnabled || lan!=null) && visualCadence.due(System.nanoTime())) pushVisual();
        controlWithVisualMs=(System.nanoTime()-begin)/1e6;
    }
    private void pushVisual() {
        if(!ready || destroyed || (!renderEnabled && lan==null)) return;
        long begin=System.nanoTime();
        SceneFrame frame=new SceneFrame(NativeSim.visualSnapshot(handle),System.nanoTime());
        cameraScene=new CameraScene(visualMeshes,frame);
        if(renderEnabled) preview.frame(frame);
        if(video!=null) video.scene(cameraScene);
        snapshotMs=(System.nanoTime()-begin)/1e6;
    }
    private void startRealtimeTest() {
        if(renderEnabled && !preview.ready()) throw new IllegalStateException("请等待 3D 画面加载完成；如有渲染错误，可关闭 3D 做对照测试");
        pause(); policyEnabled=false; enablePolicy(.30f);
        realtimeSampleCount=0; realtimeOverBudget=0; realtimeStarted=System.nanoTime();
        workerOverBudget=0; publishSamples=0; publishMaxMs=0; publishTotalMs=0;
        overBudgetSampleCount=0;
        realtimeFramesAtStart=preview.frames(); maxLatenessMs=0; realtimeTesting=true; start();
        VideoFrames.Status videoStart=video==null?null:video.status();
        videoPublishedAtStart=videoStart==null?0:videoStart.published;
        videoServedAtStart=videoStart==null?0:videoStart.served;
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
            VideoFrames.Status videoEnd=video==null?null:video.status();
            long videoPublished=videoEnd==null?0:videoEnd.published-videoPublishedAtStart;
            data.put("test_video_frames_published",videoPublished);
            data.put("test_video_capture_fps",videoPublished/wall);
            data.put("test_video_frames_served",videoEnd==null?0:videoEnd.served-videoServedAtStart);
            JSONArray slowTicks=new JSONArray();
            for(int i=0;i<overBudgetSampleCount;i++) {
                double[] row=overBudgetSamples[i];
                JSONObject slow=new JSONObject().put("sample_index",(long)row[0]).put("worker_tick_ms",row[1])
                    .put("control_with_visual_ms",row[2]).put("native_state_capture_ms",row[3]).put("report_metadata_submit_ms",row[4]);
                for(int part=0;part<metadataCaptureMs.length;part++) slow.put(componentNames[6+part]+"_ms",row[5+part]);
                slowTicks.put(slow);
            }
            data.put("worker_over_budget_samples",slowTicks);
            data.put("worker_over_budget_samples_truncated",workerOverBudget>overBudgetSamples.length);
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
            publishTest(data.toString());
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
            data.put("control_max_ms",ms[499]); publishTest(data.toString());
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void publish(String raw) {
        publish(raw,true,false,-1,0);
    }
    private void publishTest(String raw) {
        publish(raw,true,true,-1,0);
    }
    private void publish(String raw,boolean refreshVisual,boolean completedTest,double wallRun,long capturedLateTicks) {
        try {
            if(refreshVisual) pushVisual();
            long begin=System.nanoTime();
            boolean policy=policyEnabled,render=renderEnabled,loaded=ready,running=ticker!=null;
            boolean lanEnabled=lan!=null,remoteActive=remoteLease.active();
            double control=controlMs,inference=policyTickMs,physics=physicsMs,snapshot=snapshotMs,withVisual=controlWithVisualMs;
            long commands=remoteCommandsApplied,coalesced=reports.coalesced();
            String stopReason=remoteStopReason,pose=keyframe,device=android.os.Build.MODEL;
            int api=android.os.Build.VERSION.SDK_INT;
            CapturedReport.Fields app=data->{
                if(wallRun>=0) {
                    data.put("wall_run_seconds",wallRun); data.put("late_ticks",capturedLateTicks); data.put("mode","running");
                }
                data.put("model","scene.xml → robot_groundcontact.xml");
                data.put("stage",policy?"M2: ONNX + XML position servo":"M1: position servo only; no policy");
                data.put("policy_enabled",policy); data.put("control_ms",control);
                data.put("app_stage","M8"); data.put("render_enabled",render);
                data.put("ready",loaded); data.put("running",running);
                data.put("lan_enabled",lanEnabled); data.put("remote_control_active",remoteActive);
                data.put("remote_commands_applied",commands); data.put("remote_stop_reason",stopReason);
                data.put("report_pipeline","atomic latest status + completed queue; report thread polls without submit wake; captured scalar values only");
                data.put("report_poll_interval_ms",ReportDispatcher.POLL_INTERVAL_MS);
                data.put("report_coalesced_statuses",coalesced); data.put("visual_target_fps",VisualCadence.FPS);
                data.put("policy_tick_ms",inference); data.put("physics_advance_ms",physics);
                data.put("visual_snapshot_ms",snapshot); data.put("control_with_visual_ms",withVisual);
                data.put("device",device); data.put("android_api",api); data.put("keyframe_requested",pose);
            };
            long appDone=System.nanoTime(); metadataCaptureMs[0]=(appDone-begin)/1e6;
            CapturedReport.Fields visual=preview.captureReport();
            long visualDone=System.nanoTime(); metadataCaptureMs[1]=(visualDone-appDone)/1e6;
            CapturedReport.Fields videoFields=video==null?null:video.captureReport();
            long videoDone=System.nanoTime(); metadataCaptureMs[2]=(videoDone-visualDone)/1e6;
            CapturedReport.Fields policyFields=policies==null?null:policies.captureReport();
            long policyDone=System.nanoTime(); metadataCaptureMs[3]=(policyDone-videoDone)/1e6;
            long submitCpuBegin=Debug.threadCpuTimeNanos();
            // These callbacks own only scalar copies, strings and an immutable video status.
            CapturedReport captured=new CapturedReport(raw,data->{
                app.putInto(data); visual.putInto(data);
                if(videoFields!=null) videoFields.putInto(data);
                if(policyFields!=null) policyFields.putInto(data);
            },reportWriter);
            reports.submit(captured,completedTest,reportSubmitTiming);
            metadataCaptureMs[4]=(System.nanoTime()-policyDone)/1e6;
            metadataCaptureMs[5]=reportSubmitTiming[0]/1e6;
            metadataCaptureMs[6]=reportSubmitTiming[1]/1e6;
            metadataCaptureMs[7]=Math.max(0,Debug.threadCpuTimeNanos()-submitCpuBegin)/1e6;
        } catch(Exception e) { throw new IllegalStateException("Cannot capture simulation state",e); }
    }
    private void writeReport(JSONObject data) throws Exception {
        byte[] reportBytes=data.toString(2).getBytes(StandardCharsets.UTF_8);
        Files.write(new File(getFilesDir(),"last-report.json").toPath(),reportBytes);
        // Preserve completed tests across pose resets and Activity recreation.
        String archive="realtime_render_test".equals(data.optString("mode")) ? "last-realtime-test.json"
            : data.has("policy_control_ticks") ? "last-policy-benchmark.json"
            : data.has("benchmark_steps") ? "last-physics-benchmark.json" : null;
        if(archive!=null) Files.write(new File(getFilesDir(),archive).toPath(),reportBytes);
        String compact=data.toString(); remoteState=compact; Log.i("MicroduckM1",compact);
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
        if(data.has("policy")) details+=String.format(Locale.ROOT,
            "\n策略 %s / 启用 %s\nONNX %.3f ms / 控制周期 %.3f ms\n推理对照最大误差 %.7f\n%s",
            data.getString("policy"),data.getBoolean("policy_enabled"),data.getDouble("inference_ms"),data.getDouble("control_ms"),
            data.getDouble("policy_smoke_max_abs_error"),data.getString("action_filter"));
        if(data.has("policy_control_ticks")) details+=String.format(Locale.ROOT,
            "\n策略 500 周期 / %.3f s / %.2f×\n控制 p95 %.3f / p99 %.3f / max %.3f ms",
            data.getDouble("policy_wall_seconds"),data.getDouble("policy_realtime_factor"),
            data.getDouble("control_p95_ms"),data.getDouble("control_p99_ms"),data.getDouble("control_max_ms"));
        details+=String.format(Locale.ROOT,"\n3D %s / %.1f FPS / CPU提交 %.3f ms\n快照 %.3f ms / GPU %s",
            data.getBoolean("render_ready"),data.getDouble("render_fps"),data.getDouble("render_cpu_ms"),data.getDouble("visual_snapshot_ms"),data.getString("gl_renderer"));
        if(data.has("realtime_wall_seconds")) details+=String.format(Locale.ROOT,
            "\n实时测试：墙钟 %.2fs / 仿真 %.2fs\n画面 %.1f FPS / 控制p99 %.3f ms\n超20ms周期 %d / 调度迟到 %d\n工作线程p99 %.3f ms / 超20ms %d",
            data.getDouble("realtime_wall_seconds"),data.getDouble("sim_time"),data.getDouble("test_render_fps"),
            data.optDouble("control_with_visual_p99_ms",0),data.getInt("control_over_20ms_ticks"),data.getLong("late_ticks"),
            data.optDouble("worker_tick_p99_ms",0),data.optInt("worker_over_20ms_ticks",0));
        final String shownDetails=details;
        main.post(()->{ if(!destroyed) { status.setText(text); report.setText(shownDetails); } });
    }
    private void startLan() {
        if(lan!=null) return;
        if(realtimeTesting) throw new IllegalStateException("请先完成或暂停实时测试");
        try(InputStream input=getAssets().open("controller.html")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] chunk=new byte[4096]; int size;
            while((size=input.read(chunk))!=-1) bytes.write(chunk,0,size);
            byte[] page=bytes.toByteArray(); long generation=++lanGeneration;
            video=new VideoCapture(getApplicationContext());
            if(cameraScene!=null) video.scene(cameraScene);
            pushVisual();
            VideoCapture sessionVideo=video;
            lan=new LanControlServer(8080,page,()->remoteState,
                command->remoteMailbox.submit(command,value->{
                    try { return applyRemote(value,generation); }
                    catch(LanControlServer.CommandFailure e) { throw e; }
                    catch(Exception e) { failed(e); throw e; }
                }),sessionVideo::frame,sessionVideo::signal);
            StringBuilder links=new StringBuilder("另一台设备连接同一 Wi-Fi，在浏览器打开以下地址（可长按复制）：\n");
            for(NetworkInterface nic:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if(!nic.isUp() || nic.isLoopback()) continue;
                for(java.net.InetAddress address:Collections.list(nic.getInetAddresses()))
                    if(address instanceof Inet4Address && address.isSiteLocalAddress())
                        links.append(nic.getName()).append(": http://").append(address.getHostAddress()).append(':')
                            .append(lan.port()).append("/#token=").append(lan.token()).append('\n');
            }
            links.append("访问码：").append(lan.token()).append("\n浏览器断开超过3秒自动暂停；离开本应用会关闭服务。");
            String shown=links.toString(); main.post(()->{ if(!destroyed) networkInfo.setText(shown); });
            publish(NativeSim.state(handle));
        } catch(Exception e) {
            stopLan(); main.post(()->{ if(!destroyed) networkInfo.setText("局域网服务启动失败："+e.getClass().getSimpleName()+"，检查8080端口或网络权限。"); });
        }
    }
    private String applyRemote(RemoteCommand command,long generation) throws Exception {
        if(destroyed || !foreground || !ready || lan==null || lanGeneration!=generation)
            throw new LanControlServer.CommandFailure(503,"LAN session ended");
        if(realtimeTesting && !command.op.equals("heartbeat"))
            throw new LanControlServer.CommandFailure(409,"Local realtime test owns control");
        long now=System.nanoTime();
        switch(command.op) {
            case "heartbeat": remoteLease.heartbeat(now); break;
            case "pause": remoteStopReason="remote_pause"; pause(); break;
            case "stand": case "walk":
                enablePolicy(command.op.equals("walk")?command.vx:0);
                if(command.op.equals("walk")) policies.velocity(command.vx,command.vy,command.yaw);
                remoteStopReason=""; start(); remoteLease.claim(now); break;
            case "roulade":
                enableRoulade(); remoteStopReason=""; start(); remoteLease.claim(now); break;
            case "reset":
                pause(); policyEnabled=false; if(policies!=null) policies.reset(); keyframe=command.pose;
                NativeSim.reset(handle,keyframe); remoteStopReason="remote_reset"; break;
            default: throw new LanControlServer.CommandFailure(400,"Invalid command");
        }
        if(!command.op.equals("heartbeat")) { remoteCommandsApplied++; publish(NativeSim.state(handle)); }
        return new JSONObject().put("ok",true).put("op",command.op).put("running",ticker!=null)
            .put("remote_control_active",remoteLease.active()).put("command_count",remoteCommandsApplied).toString();
    }
    private void stopLan() {
        lanGeneration++; remoteLease.release();
        if(video!=null) video.close();
        if(lan!=null) { lan.close(); lan=null; }
        main.post(()->{ if(!destroyed && networkInfo!=null) networkInfo.setText("局域网控制已关闭"); });
    }
    private void failed(Throwable e) {
        pause(); stopLan(); policyEnabled=false; Log.e("MicroduckM1","Simulation error",e);
        String text="暂停："+e.getClass().getSimpleName()+"\n"+e.getMessage();
        main.post(()->{ if(!destroyed) status.setText(text); });
    }
    @Override protected void onStop() {
        super.onStop();
        foreground=false;
        if(!destroyed) worker.execute(()->{ pause(); stopLan(); }); // Local and LAN runs require a visible Activity.
    }
    @Override protected void onResume() { super.onResume(); foreground=true; if(preview!=null) preview.onResume(); }
    @Override protected void onPause() { if(preview!=null) preview.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed=true;
        worker.execute(()->{ pause(); stopLan(); if(policies!=null) policies.close(); if(handle!=0) { NativeSim.close(handle); handle=0; } reports.close(); });
        worker.shutdown(); super.onDestroy();
    }
}
