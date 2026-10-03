# M5.4：浏览器实时画面与报告定时读取

当前源码 `0.5.4-m54` / versionCode=11，手机标题 `Microduck · Android M5.4`。保留连续JPEG画面，报告线程定时自行取状态，控制周期里不再直接唤醒报告线程。用户通过Android Studio构建/安装/运行；源码检查不打包或安装APK。

**M5.4视频开启30秒验收通过：** 1501周期/6004步，墙钟30.007秒/仿真30.02秒；完整工作线程p99=10.648ms/max=19.772ms、超20ms周期0，调度迟到超过20ms周期0（最大迟到13.818ms）。手机30.03FPS、视频298发布帧/9.93FPS、265帧提供HTTP、576×480、采集错误0；最终位姿与M5.3一致。报告捕获平均0.285ms/max=0.519ms，队列提交max=0.087ms。原始报告 [device-M54-video-on-realtime-test.json](verification/device-M54-video-on-realtime-test.json)，对比 [device-M54-video-acceptance.json](verification/device-M54-video-acceptance.json)。单次最大余量0.228ms、温度/频率未控制，不证明长期或硬实时保证。

**同版本video-off对照也通过：** 1501周期/6004步、墙钟30.006秒/仿真30.02秒，LAN和3D保持开启，新增视频发布/HTTP帧均为0、观看需求及在途复制均为false，工作线程超20ms周期0，最终位置/四元数与视频开启时完全一致。原始报告 [device-M54-video-off-realtime-test.json](verification/device-M54-video-off-realtime-test.json)，对比 [device-M54-video-off-acceptance.json](verification/device-M54-video-off-acceptance.json)。

| 指标 | 视频开启 | 视频关闭 |
| --- | --- | --- |
| 完整工作线程p99 / max | 10.648 / 19.772ms | 15.587 / 19.699ms |
| 超20ms周期 | 0 | 0 |
| 手机测试FPS | 30.03 | 29.99 |
| 测试新增发布 / HTTP帧 | 298 / 265 | 0 / 0 |

关闭后的p99较高，物理推进p99也从7.087变为12.818ms。两次温度/CPU频率未控制，这不证明视频降低CPU耗时，也不能直接归因于温升。关闭后video_enabled仍为true、累计视频计数仍非零、缓存帧龄增长是预期行为；零测试增量表明这30秒没有新发布/提供的帧。下一步验证前后台行为。

M5.3复测：30秒发布297帧/9.90FPS、HTTP提供263帧、错误0、手机30.03FPS。工作线程p99=12.557ms/max=20.369ms，2周期超20ms、迟到周期0。sample65/325的唤醒墙钟耗时11.348/14.834ms，提交区间线程CPU仅0.137/0.167ms；准入与发布约0.01ms。原始报告 [device-M53-video-on-realtime-test.json](verification/device-M53-video-on-realtime-test.json)，对比 [device-M53-video-acceptance.json](verification/device-M53-video-acceptance.json)。CPU和墙钟区间边界不同，不能相减当精确等待时长；记录无法区分抢占、GC或内核等待。

M5.4移除submit里的unpark，消费者空闲时parkNanos等待50ms再检查队列；任务到达时不通知消费者。状态显示增加名义0–50ms等待，调度和I/O仍可能更久；每秒空闲检查约20次，不忙等。正常报告捕获约4次/秒。命令确认仍由仿真线程生成，不等待报告线程；物理、策略和视频实现未改。关闭仍会unpark以尽快排空；若关闭时存在已准入但未发布的生产者，定时检查可在发布后继续处理。最新状态合并、完成测试保留和错误恢复契约继续生效，真机30秒结果见上文。

M5.2复测：30秒发布297帧/9.90FPS、HTTP提供269帧、错误0、手机约30FPS。完整工作线程p99=13.130ms/max=21.270ms、超20ms周期1；配对sample1365的控制+快照8.404ms、原生状态0.152ms、四项元数据捕获合计0.144ms，队列提交区间12.545ms。原始报告 [device-M52-video-on-realtime-test.json](verification/device-M52-video-on-realtime-test.json)，对比 [device-M52-video-acceptance.json](verification/device-M52-video-acceptance.json)。区间包含对象构造、提交及唤醒，仍不足以证明锁等待或GC/调度的具体原因。

M5.3用常驻报告线程替换按需Executor drain：普通状态通过AtomicReference交换，只保留最新待处理项；完成测试通过ConcurrentLinkedQueue保留。提交不再与消费者共享monitor。原子准入计数保证close拒绝新任务，同时等待已准入生产者发布后再退出；close不等待报告I/O，已接受任务仍落盘。已捕获对象直接实现Runnable，写入回调只初始化一次。新增准入、发布、唤醒的墙钟耗时和提交区间线程CPU耗时，真机结果见上文。

M5.1复测：30秒发布298帧/9.93FPS、HTTP提供271帧、错误0；报告捕获平均1.312ms，但仍有1个22.613ms周期，配对记录显示控制+快照4.533ms、原生状态0.162ms、元数据捕获/提交17.879ms。原始报告 [device-M51-video-on-realtime-test.json](verification/device-M51-video-on-realtime-test.json)，对比 [device-M51-video-acceptance.json](verification/device-M51-video-acceptance.json)。该区间可能包含分配、调度及锁等待，记录不直接证明具体原因。

M5.2将应用、渲染、视频、策略元数据的JSONObject/JSONArray构造移入报告线程。仿真线程先复制标量和不可变VideoFrames.Status，报告回调只读这些副本；原生state仍由仿真线程读取。JPEG副本在编码线程、状态锁之外分配；单个采集任务仍串行，关闭期间产生的副本不再发布。五项捕获/队列计时已在真机定位上述区间，完整预算尚未通过。

M5已在真机采集并由用户完成浏览器画面/按钮测试：576×480、30秒发布298帧/9.93FPS、提供给HTTP268帧，采集错误0、手机30FPS。完整工作线程仍有3周期超过20ms、max=22.422ms，报告捕获max=16.246ms；两个最大值没有配对，不能确定同周期或直接归因。原始证据 [device-M5-video-on-realtime-test.json](verification/device-M5-video-on-realtime-test.json)，对比 [device-M5-video-acceptance.json](verification/device-M5-video-acceptance.json)。

代码检查发现原周期性报告在控制线程解析原生JSON、转字符串后再解析；M5.1保留原生JSON字符串，单独捕获独占元数据，在报告线程解析/合并。原生state调用仍在仿真线程，报告线程不访问模型、策略或工作线程可变字段。完成测试显式进入保留队列。以上版本记录保留用于比较，M5.4当前30秒结果见开头。

## 使用及真机验收

1. Studio重新编译运行，确认标题M5.4，保持“显示3D”开启，手机应用在前台。
2. 点“开启局域网控制”，控制设备使用同一Wi-Fi，重新打开应用显示的地址并连接。旧M4页面需要刷新；访问码依然每次开启服务时更新。
3. 浏览器勾选“开启实时画面”。首次可能短暂显示等待，随后显示仿真手机的3D视图及尺寸、接收FPS、采集帧龄。目标10FPS，最大640×480，JPEG质量70。它显示机器人、地面、轨迹和接触点，跟随仿真手机的当前相机视角。
4. 在网页点站立、前进、暂停，确认控制及时、画面方向正确且不黑屏。暂停时机器人不动，画面仍可更新相机视角；开启画面本身不会启动仿真。
5. 保持网页画面开启，在仿真手机点“实时测试30s”，等自动暂停后告诉代理读取报告。测试期间不要操作相机/速度/复位。核对M5.4、LAN开启、`test_video_frames_published`和`test_video_frames_served`均大于0、控制周期与完整工作线程预算。没有视频帧的测试不能算视频开启测试。
6. 已完成的关闭对照：取消画面开关并等待至少3秒，让2秒观看需求及在途采集结束；保持网页连接、仿真手机应用在前台、“显示3D”开启，再做一次30秒对照。两份归档已保存。关闭测试新增发布帧/HTTP帧均为0；累计视频帧数不会归零。
7. 验证浏览器切后台后暂停、回前台后画面恢复但仿真不自行运行；仿真手机离开应用后关闭服务，回前台需手动开启并使用新访问码。

```bash
# 每次测试后导出，避免下一次覆盖；设备序列号可按实际修改。
adb -s 10AC710LRV001H8 shell run-as com.microduck.sim cat files/last-realtime-test.json > /tmp/microduck-m5-video-on.json
# 网页关闭画面、再次测试后执行：
adb -s 10AC710LRV001H8 shell run-as com.microduck.sim cat files/last-realtime-test.json > /tmp/microduck-m5-video-off.json
```

## 当前下一项：前后台验收

继续使用已安装的M5.4，分开测试控制端浏览器和仿真应用，前一项结束后先读取证据。

1. 浏览器：保持仿真手机应用前台；控制端打开画面并点“前进”，确认开始运动，把浏览器切到其他应用5秒，观察仿真手机暂停。回浏览器后画面应恢复、仿真保持暂停；先告知代理读取 `last-report.json` 和相关暂停日志。此处使用远程前进，避免本地30秒测试提前退出掩盖结果。页面隐藏会尝试发送pause，发送失败则由3秒租期保护；返回页面的心跳不重新启动仿真。入口 `app/src/main/assets/controller.html:128`、`app/src/main/java/com/microduck/sim/MainActivity.java:183`、`:466`。
2. 仿真应用：重新通过浏览器启动运动，再把仿真手机应用切后台5秒，旧网页应断开；回来后应保持暂停且显示LAN关闭，手动开启服务并用新访问码重新连接，验证旧会话不能继续控制。访问码不要粘贴到聊天、日志或Git。入口 `app/src/main/java/com/microduck/sim/MainActivity.java:499`、`:488`、`:436`。这项onStop当前不主动写状态，旧 `last-report.json` 可能仍描述先前状态，不能单凭旧文件判定是否关闭；结合现场观察、服务连接结果及重新开启后的报告核对。

持续运行/温升、3D关闭对照及端到端延迟后续单独验证，不能由以上两次30秒结果代替。

## 架构与边界

```mermaid
flowchart LR
  Worker[仿真线程 50Hz] --> Snapshot[最新场景快照 30Hz]
  Worker -->|原生JSON字符串和元数据副本| ReportSlots[原子最新状态槽 / 完成测试队列]
  ReportSlots -->|空闲50ms定时检查| ReportThread[常驻报告线程]
  ReportThread --> Reports[JSON组装 / 落盘 / 状态与日志]
  Snapshot --> GLES[手机 GLES3 SurfaceView]
  Browser[控制端浏览器] -->|Bearer GET /api/frame| HTTP[两个HTTP收发线程]
  HTTP --> Demand[最近2秒是否有观看请求]
  Demand --> UI[UI线程最多每100ms发起一次采集]
  GLES --> Copy[异步 PixelCopy 缩放至640×480以内]
  UI --> Copy
  Copy --> Encoder[独立JPEG线程 质量70]
  Encoder --> Latest[仅保留最新JPEG]
  Latest --> HTTP
  HTTP -->|image/jpeg| Browser
  Browser -->|独立命令与心跳| Mailbox[原M4命令队列]
  Mailbox --> Worker
```

手机采集的是现有SurfaceView，不二次绘制机器人，不访问原生模型或ONNX session。PixelCopy复制最近排入Surface的缓冲区并缩放到目标Bitmap，回调在指定Handler执行，依据 [Android PixelCopy官方文档](https://developer.android.com/reference/android/view/PixelCopy)。JPEG编码使用 [Bitmap.compress](https://developer.android.com/reference/android/graphics/Bitmap#compress(android.graphics.Bitmap.CompressFormat,%20int,%20java.io.OutputStream))，在独立线程执行；UI只调度采集，仿真工作线程只捕获少量视频统计。

每个LAN会话拥有独立采集器和缓存；没有观看请求不采集，最后请求2秒后不再发起新采集。最多一个PixelCopy/编码任务，慢编码跳过后续采集机会，最新帧覆盖旧帧；单帧上限512KiB，超限或2秒以上旧帧不返回。关闭服务清空缓存、停止调度及编码线程，旧回调不会发布到新会话。关闭3D后返回503等待画面。视频不会延长远程运行租期，3秒心跳暂停逻辑仍由控制链路负责。

浏览器默认关闭画面，开启后最多10次/秒拉取最新JPEG、同一页面最多一个画面请求；视频请求独立于串行命令/心跳。单次画面请求1.5秒超时，503等错误稍后重试；切后台/关闭画面取消请求，旧会话迟到响应不能恢复画面，使用后的Blob URL会释放。HTTP没有持续流连接：每次请求返回一帧后关闭，沿用2个收发线程/4个等待连接，服务器从开始处理请求起3秒后关闭仍未完成的socket，包含写出阶段；排队等待不计入这3秒。

`GET /api/frame`需要相同Bearer访问码，成功返回`image/jpeg`及`X-Frame-Sequence`、`X-Frame-Age-Ms`，未采到可用画面返回503、访问码错误401。访问码不放在图片URL中，也不写入状态/日志/验证文件。此版为单个控制页面在局域网观看设计；JPEG独立帧带宽高于视频编解码，尚无音频、WebRTC/H.264或桌面webrtcsink协议。

## 报告与解释

| 字段 | 含义 |
| --- | --- |
| `video_enabled` / `video_demand_active` | 采集会话仍开启 / 最近2秒有观看请求；前者不表示正在编码 |
| `video_frames_published` / `video_frames_served` | 本次会话累计编码发布 / 提供给HTTP发送器的帧数；后者不证明浏览器已收到 |
| `video_copy_ms` / `video_encode_ms` | 最近一次异步复制回调耗时 / JPEG编码耗时，不含网络传输 |
| `video_width` / `video_height` / `video_jpeg_bytes` | 最新帧的尺寸与大小 |
| `video_frame_age_ms` / `video_error` | 最近一次状态捕获时帧龄 / 最近采集错误；暂停时缓存状态不持续刷新 |
| `test_video_frames_published` / `test_video_capture_fps` | 本次30秒测试新增的发布帧数及平均采集FPS |
| `test_video_frames_served` | 本次30秒测试提供给HTTP发送器的帧数 |
| `worker_tick_p99_ms` / `worker_tick_max_ms` | 完整控制线程周期耗时；CPU/GPU/内存争用仍可能影响它 |
| `native_state_capture_*_ms` / `report_metadata_submit_*_ms` | M5.1新增分项的p95/p99/max；无报告周期记0，因此分位数覆盖全部控制周期 |
| `worker_over_budget_samples` | 最多16个超20ms周期，配对记录sample_index、worker_tick、control_with_visual、native_state_capture、report_metadata_submit |
| `worker_over_budget_samples_truncated` | 超预算周期超过16时为true，计数和整体最大值仍覆盖全部周期 |
| `app_metadata_capture_*_ms` / `render_metadata_capture_*_ms` | M5.2应用标量捕获 / 渲染标量捕获的p95/p99/max |
| `video_metadata_capture_*_ms` / `policy_metadata_capture_*_ms` / `report_queue_submit_*_ms` | 视频不可变状态捕获 / 策略标量捕获 / 报告队列提交；同样无报告周期记0，配对样本包含这5项 |
| `report_admission_*_ms` / `report_mailbox_*_ms` | 准入CAS / 原子发布和准入计数递减；均为墙钟耗时，包含被抢占的时间 |
| `report_poll_interval_ms` | M5.4报告线程空闲等待设置50ms，不是状态显示或落盘的硬延迟上限 |
| `report_wakeup_*_ms` | 仅M5.3历史报告包含；M5.4提交不再唤醒，因此不再记录该项 |
| `report_submit_cpu_*_ms` | 由Debug.threadCpuTimeNanos记录的当前线程CPU差值，区间覆盖报告对象构造及提交；与上述队列墙钟区间边界略有差异，不能直接相减作为精确等待时间 |

以上提交分段也进入超预算配对样本，无报告周期仍记0。若提交墙钟尖峰而线程CPU很低，说明该线程在区间内大部分时间没有运行，但不能据此区分抢占、GC暂停或内核等待；若CPU也高，再针对计算路径调查。它们不是整周期线程CPU测量。

网页“接收FPS”统计本次开启以来成功解码的新序列；“采集帧龄”是服务器响应时距采集发起的时间，不是端到端显示延迟。手机30FPS渲染、10FPS采集目标和浏览器实际接收FPS是不同指标。

M4无视频基线：工作线程p99=16.618ms/max=19.129ms、30秒超20ms周期0；见 [device-M4-realtime-acceptance.json](verification/device-M4-realtime-acceptance.json)。不同次运行受频率/温度影响。M5/M5.1/M5.2/M5.3/M5.4视频开启分别有3/1/1/2/0个工作线程周期超预算；M5.4视频关闭也为0。短实时测试不能替代长期温升和网络中断验证。

## 检查与代码入口

[verification/m54-checks.json](verification/m54-checks.json)包含Java对Android34/ORT的JDK11编译、Android ARM64 JNI语法、原物理观测/动作及渲染桥、视频缓存/会话、HTTP图片与心跳并发、网页模拟DOM/网络检查；报告测试验证阻塞I/O不阻塞提交/关闭、状态合并、完成测试保留、错误恢复、无提交信号仍能交付报告、并发关闭保留已接收任务、直接提交报告对象。延后字段检查验证JSON组装发生在报告线程、捕获后命令改变不影响报告速度及非法JSON拒绝。HTTP夹具JPEG不解码图像，网页夹具不是真实浏览器。M5.4实际视频开启/关闭的两次30秒完整预算已通过；长期温升、状态延迟及生命周期待测。

| file:line | 作用 |
| --- | --- |
| `app/src/main/java/com/microduck/sim/VideoCapture.java:12` | UI调度及独立编码线程 |
| `app/src/main/java/com/microduck/sim/VideoCapture.java:28` | 按需复制与复用Bitmap |
| `app/src/main/java/com/microduck/sim/VideoFrames.java:7` | 缓存、观看需求、单任务及时限 |
| `app/src/main/java/com/microduck/sim/LanControlServer.java:76` | 包含写出的socket截止时间 |
| `app/src/main/java/com/microduck/sim/LanControlServer.java:111` | 已授权的最新帧响应 |
| `app/src/main/java/com/microduck/sim/MainActivity.java:273` | 测试开始计数 |
| `app/src/main/java/com/microduck/sim/MainActivity.java:285` | 30秒视频/控制统计及配对记录 |
| `app/src/main/java/com/microduck/sim/MainActivity.java:348` | 标量捕获、报告回调与分段计时 |
| `app/src/main/java/com/microduck/sim/CapturedReport.java:7` | 报告线程解析原生JSON并合并 |
| `app/src/main/java/com/microduck/sim/ReportDispatcher.java:27` | 原子准入、发布，不唤醒消费者 |
| `app/src/main/java/com/microduck/sim/ReportDispatcher.java:50` | 消费者定时检查及关闭后任务保留 |
| `app/src/main/java/com/microduck/sim/RobotSurfaceView.java:40` | 渲染元数据副本 |
| `app/src/main/java/com/microduck/sim/PolicyRunner.java:85` | 策略/速度元数据副本 |
| `app/src/main/java/com/microduck/sim/VideoCapture.java:54` | 不可变视频状态副本 |
| `app/src/main/java/com/microduck/sim/VideoFrames.java:54` | JPEG副本分配移出状态锁 |
| `app/src/main/java/com/microduck/sim/MainActivity.java:436` | LAN会话创建采集器 |
| `app/src/main/java/com/microduck/sim/MainActivity.java:488` | 服务及采集关闭 |
| `app/src/main/assets/controller.html:55` | 独立画面轮询、解码及Blob释放 |
| `verification/VideoFramesCheck.java:1` | 缓存、旧帧、超限和关闭测试 |
| `verification/controller-check.js:1` | 模拟网页异步/生命周期测试 |
