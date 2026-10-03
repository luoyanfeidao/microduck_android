# Android Studio 施工入口（M4：3D + 物理 + ONNX + 局域网控制）

在 Android Studio 中打开当前目录 `android-sim`，不要打开外层 Microduck 目录。由你完成 Sync、编译、安装和真机运行。

首次从 GitHub 获取时，先按 [DEPENDENCIES.md](DEPENDENCIES.md) 准备外部源码和已验证策略；固定提交及资源摘要见 [UPSTREAM.lock.json](UPSTREAM.lock.json)。

当前实现是 **Android arm64 上的 MuJoCo + ONNX + 3D 测试应用**：加载桌面 duck-body 使用的 `scene.xml → robot_groundcontact.xml`，保留 M1/M2 测试、真实 CAD 网格视图和按墙钟运行的30秒测试。M4增加局域网浏览器控制，另一台Android可发站立/步行/暂停/复位命令并读状态；操作和架构见 [NETWORK.md](NETWORK.md)。当前3D显示在仿真手机，视频链路后续接入。

**M4浏览器连接及按钮已实测通过：** 用户已用Studio构建运行 `0.4-m4` / versionCode=6，并确认浏览器连接和按钮正常；ADB读取到15条远程控制命令、远程运行和网页暂停，数值及渲染正常。证据见 [verification/device-M4-lan-acceptance.json](verification/device-M4-lan-acceptance.json)。下一步补测控制端Wi-Fi断开3秒自动暂停、应用前后台关闭服务，以及保持网页连接时的“实时测试30s”；当前独立实时归档仍为M3.2。操作见 [NETWORK.md](NETWORK.md)。

## 在 Android Studio 编译

1. File → Open，选择 `/Users/navinfo/AndroidStudioProjects/deepLearning/microduck/android-sim`。
2. Settings → Build, Execution, Deployment → Build Tools → Gradle，使用工程自带 Gradle Wrapper；Gradle JDK 选择 **JDK 11**。当前机器可选择 `/Applications/Android Studio.app/Contents/jbr/Contents/Home`（本机这个目录是 JDK 11；其他机器请核实版本）。
3. SDK Manager 安装 Android SDK Platform 34、NDK **25.1.8937393**、CMake **3.30.3**。当前 `local.properties` 指向 `/Users/navinfo/Android/sdk`；换机器应让 Studio 更新 SDK 路径。
4. 执行 Gradle Sync，首次同步保持网络可用。配置为 AGP 7.2.2 / Gradle 7.5，不必在首次验收时顺带升级构建链。
5. 选择 `app` 和你的 ARM64 Android 设备，点击 Run。最低 Android API 28；本次目标设备为 V2185A / Android 16 / 骁龙 SM8450。

构建会自动执行 `scripts/prepare_assets.py`，从旁边的 `Pollen/microduck_rl` 收集 XML 与 38 个 STL，从 `Pollen/microduck/policies` 收集 `alpha_stand.onnx / alpha_walking.onnx`，并生成 SHA-256 清单。因此需要保留当前目录与 `Pollen` 的相对位置，以及 PATH 中可用的 **Python 3.9+**、Git。若 Studio 报找不到 `python3`，从终端运行 `python3 scripts/prepare_assets.py` 确认解释器可用，并修正启动 Studio 时的 PATH。

M2 新增 Maven 依赖 `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`，再次 Sync 会下载它。已检查实际 AAR 的 Java 类版本兼容 JDK 11，因此构建链维持不变。当前目标手机的系统页大小为 4096 字节；该 AAR 的 JNI 库仅有 4KB ELF 对齐，16KB 页设备尚不支持当前组合，不能凭 MuJoCo 库的 16KB 对齐宣称整个 APK 已支持。

CMake 会从源码构建 MuJoCo 和 JNI 库，不需要手动复制 `.so`。MuJoCo 固定为 3.10.0，提交 `28009f9105cd92784b7b0b30c0605a5e29107a77`。本机已准备 `.native-cache` 源码缓存；缓存缺失时构建配置会下载官方源码及上游固定版本依赖。缓存和生成模型不提交到版本控制。

## 手机上怎么验收

**保留的 M3 实时验收：**

1. 在 Android Studio 重新 Sync、Build、Run；当前M4沿用已跑通的M3.2物理/策略/渲染及构建配置，本次没有新增Maven依赖。
2. 上方应出现真实机器人 CAD 外观及地面网格。单指拖动旋转视角，双指缩放；“复位视角”恢复默认角度和距离。“跟随机器人”保持机器人在中心，取消后可以观察它在地面上的位移。
3. 点击“策略站立”观察平衡；点击“前进 0.30m/s”观察脚步。橙线是最近约 512 个绘制快照的地面轨迹，红点是 MuJoCo 当前接触点，可以关闭接触点显示。
4. 等 3D 正常显示后，点击“实时测试 30s”。从 HOME 重置，发送 vx=.30，按 50Hz 运行 30 秒墙钟，然后自动暂停。保持应用在前台且不再点击重置；告诉 Codex 完成，以便经 ADB 获取报告。
5. 报告 `mode=realtime_render_test`，包含墙钟/仿真时间比例、控制周期与快照的 p95/p99/max、超过 20ms 的周期数、调度迟到及绘制帧率。本版画面目标约 **30FPS**，物理与推理仍是 **50Hz**；不要用原 M3 的 53.74FPS 作为新版本帧率目标。
6. 先导出开启 3D 的报告，再关闭“显示3D”重复实时测试并另存。关闭时绘制回调应接近零。同类新测试会覆盖独立归档；暂停后转动相机也会请求额外绘制，因此测速期间勿操作相机。

```bash
# 开启 3D 的测试结束后，先执行第一条；关闭 3D 再测后，执行第二条。
adb shell run-as com.microduck.sim cat files/last-realtime-test.json > /tmp/microduck-m32-3d-on.json
adb shell run-as com.microduck.sim cat files/last-realtime-test.json > /tmp/microduck-m32-3d-off.json
```

保留默认动作过滤设置，关注数值有限、高度、最终位移，以及 `control_with_visual_p99_ms`、`worker_tick_p99_ms`、`worker_over_20ms_ticks`、`late_ticks`。M3.2手机工作线程p99=18.085ms/max=19.923ms，最终x=3.501m/y=-1.225m/z=.1198m；M4网络开启时的结果仍需复测。

原来的“策略测试 10s”仍是不等待墙钟的 M2 快速计算基准，不能用来观看完整动画：循环中不逐步更新画面，只显示开始和结束姿态。看步行应使用“前进”或“实时测试30s”。

**保留的 M2 操作：**

1. 加载两份策略时会核验 SHA-256、float32 输入 `[1,61]`、输出 `[1,14]`，并执行四组桌面 ONNX 对照；最大绝对误差超过 0.001 会拒绝加载策略。M1 操作仍可用。
2. 点击“策略站立”：从 HOME 放置开始（躯干 0.125m、关节 home、速度/原始动作反馈归零），50Hz 推理。点击暂停后查看高度、位移与数值。
3. 点击“前进 0.30m/s”：选择步行策略。已启用策略时只改变速度命令，保留机器人状态；尚未启用时先重置 HOME。
4. 点击“策略测试 10s”：总是从 HOME 重置，发送 vx=0.30m/s，执行 500 个控制周期，每次推理后推进四个物理步。结束保持暂停，读取位移、姿态和周期 p95/p99/max；这测的是 10 秒**仿真时间**，不等待 10 秒墙钟。
5. 完成后告诉 Codex，可经 ADB 读取 `last-report.json`。不要在读取前再重置姿态，否则会覆盖测试报告。

**原有 M1 操作：**

1. 等待模型校验完成，初始为 `SIT` 且暂停。应显示 `body=16 / nq=21 / nv=20 / actuator=14 / mesh=38 / dt=0.005s`。
2. 点击“步进 20ms”：仿真时间每次增加 0.020 秒，即执行四次 `mj_step`。
3. 点击“运行”，观察仿真时间持续增加，有限数值和脚底匹配均为 `true`；“暂停”停止推进。离开 Activity 会暂停，返回后需点击运行。
4. 点击“基准测试 2000 步”：重置当前姿态，暖机 100 步，再测 2000 步。结束时仿真时间约 10.5 秒，显示物理实时倍率及单步 p50/p95/p99。
5. 可以分别重置到 `SIT`、`STAND`、`INIT` 重复测试。没有平衡策略时 `STAND` 可能倒下；这不等同于步行策略失败。M1 验收关注模型加载、推进、数值稳定和物理性能。

左右脚接触数来自 MuJoCo 接触列表中 `left_foot_collision / right_foot_collision` 与 `floor` 的 geom 配对，表示接触点数量，不表示力或训练奖励。

性能数字只覆盖当前位置伺服的物理测试。`last_batch_ms` 包含四次物理推进及数值检查，不包含最终 `mj_forward`、JNI 返回、JSON、文件写入和 UI 更新；基准也不包含 ONNX、渲染、视频。不能用这个数字宣称整套系统已达到实时要求。

策略模式另报 `inference_ms` 和 `control_ms`。M3.1 的后者包括观测 JNI、ONNX、动作 JNI、物理推进及 `mj_forward`，不再包含原生状态 JSON（原 M3 包含），也不包含报告落盘与 UI 更新。策略基准没有墙钟节拍，仍需另做实时运行、温升和持续性测试。

M3 的 `control_with_visual_ms` 及实时测试分位数包含额外的场景快照构造/JNI 拷贝/请求绘制。GPU 在独立 GL 线程工作，不在该计时范围；`render_cpu_ms` 是 GL 回调内的 CPU 提交时间，既不含之后的 EGL swap，也不是 GPU 完成耗时。`test_render_fps` 是实际执行的绘制回调数量/测试墙钟时间，不等于屏幕面板刷新率。

M3.1 起增加各项 p95/p99/max：`policy_tick_*`（观测→推理→动作）、`physics_advance_*`（4 步及 forward）、`visual_snapshot_*`（未取快照的周期为零）、`worker_tick_*`（整个工作线程回调）。M3.1 的 `worker_tick_*` 包含当次文件、日志和 UI 准备；M3.2 把这些移到报告线程，仅包含状态捕获和队列提交。`publish_mean_ms / publish_max_ms` 也改为捕获/提交成本。原 `control_over_20ms_ticks` 不包含发布成本，新的 `worker_over_20ms_ticks` 包含当版在控制线程上的发布成本；两者均不包含 GPU 完成、EGL swap 或 UI 实际绘制。

## M3 渲染机制

MuJoCo 传统渲染器调用桌面 OpenGL 的兼容接口，Android 提供 OpenGL ES，因此本工程没有直接调用 `mjr_render`。JNI 从已编译的模型导出真实 mesh 顶点/法线；Java `GLSurfaceView` 用 GLES3 绘制。当前模型有 38 个独立网格（431,802 个三角形），被 70 个可视 geom 引用。M3.1 按“顶点索引+法线索引”共享渲染顶点，保留棱角、全部三角形及原顺序，使用 `glDrawElements / GL_UNSIGNED_INT`。215,317 个共享顶点及索引合计 10,349,232 字节（9.87MiB），原逐三角形展开为 31,089,744 字节（29.65MiB），数据量减少约 66.71%；这不等同于 GPU 内存总量或实测性能降幅。静态数据每个 GL context 上传一次，丢失 context 后用保留的 CPU 数据重建。

所有 `mjModel/mjData` 访问仍在仿真工作线程；物理/策略保持 50Hz，通过墙钟门限平均 30Hz 拷贝 geom 的世界位置、旋转、颜色和接触点。50Hz 控制周期下相邻快照通常间隔 20ms 或 40ms，未做姿态插值；相机操作、重置、测试完成仍可主动刷新。GL 线程只读不可变快照，没有原生仿真句柄。仅保留最新快照并按需绘制，慢画面不会积累无界队列；场景导出与画面线程都不推进物理。

连续控制使用 `NativeSim.advance`，保留原四步 `mj_step`、数值检查及最终 `mj_forward`，每约 250ms 捕获一次状态 JSON。定期发布复用已有画面，不再重复取快照；单步、重置及完成测试提交新状态。

M3.2 在工作线程上捕获原生状态、策略及渲染元数据后，交给独立 `ReportDispatcher` 线程进行 JSON 格式化、文件写入、日志和 UI 文案准备。报告线程只读这份独占的捕获对象，不访问 `mjModel/mjData`、策略 session 或工作线程的可变字段。普通状态只保留最新一份待处理任务，完成的测试按序保存，销毁 Activity 时排空已接受任务；UI 投递仍检查 Activity 是否已销毁。若报告保存失败，错误被送回控制线程暂停仿真。

M3.1 真机已完成一次开启 3D 的 30 秒测试，报告见 `verification/device-M31-realtime-render-test.json`：画面 30.05FPS、控制加快照 p99=16.252ms/max=18.083ms，位姿与原 M3 报告相同。新增完整工作线程计时 p99=25.791ms/max=28.381ms、超20ms 76周期；发布平均9.243ms/max=13.206ms。因此下一步是把报告格式化、文件及日志移出控制线程。原 M3 未测这项总耗时，不能把76周期解释为新引入的退化。

以上瓶颈对应的 M3.2 已由用户重新构建并完成一次开启3D的30秒测试，报告见 `verification/device-M32-realtime-render-test.json`，对比见 `verification/device-M32-comparison.json`；源码检查见 `verification/m32-checks.json`。

| 指标 | M3.1 | M3.2 |
| --- | ---: | ---: |
| 工作线程 p99 | 25.791ms | 18.085ms |
| 工作线程最大耗时 | 28.381ms | 19.923ms |
| 工作线程超过20ms周期 | 76 | 0 |
| 工作线程上的发布平均耗时 | 9.243ms | 3.254ms |
| 测试绘制回调帧率 | 30.05FPS | 30.02FPS |

M3.2 仿真30.02s/墙钟30.013s，调度迟到超过20ms为0，全部数值正常、无GL错误；最终位置/姿态与前两版报告相同。这次完整工作线程p99下降约29.88%，30秒控制预算验收通过，但最大周期距离20ms余量约0.077ms，且两次运行未控制温度和CPU频率，不能据此保证长期稳定。仍需3D关闭对照及后续持续运行测试。M3.2发布耗时只计控制线程的捕获/提交，异步报告I/O耗时不在其中。

## 源码检查（不打包或安装 APK）

已验证的检查见 `verification/m31-checks.json` 和当前 `verification/m32-checks.json`：Java/JNI 编译检查、全部三角形展开后的位置和法线一致、棱角/平面法线保留、500周期物理推进与旧流程完全一致、30FPS门限在50Hz及抖动时钟下无累计漂移或补帧队列。M3.2 增加慢报告不阻塞提交、普通状态合并、测试归档不丢、关闭排空和错误后继续处理的队列测试。

Mac 上可重复执行；下面路径对应本机环境，其他机器按实际位置修改。ORT AAR 可使用 Gradle 已下载的1.20.0文件；MuJoCo目录是桌面Python包，不是Android源码缓存。

```bash
# 在 android-sim 目录执行；先准备好模型 assets。
python3 scripts/check_source.py \
  --java-home /Library/Java/JavaVirtualMachines/jdk-11.jdk/Contents/Home \
  --android-sdk /Users/navinfo/Android/sdk \
  --ort-aar /private/tmp/microduck-onnxruntime-android-1.20.0.aar \
  --mujoco-dir ../Pollen/microduck_rl/.venv/lib/python3.12/site-packages/mujoco
```

当前绘制视觉 mesh（group<3），不显示碰撞几何；采用模型材质颜色和简化光照。地面是与当前场景水平 floor 对齐的程序网格，不复制桌面纹理、阴影或完整材质。它是观察真实模型姿态的 3D 视图，尚不是桌面画面效果的完全移植，也没有视频编码/传输。

## 61D / 14D 接线约定

观测：`gyro[3] + gravity[3] + (q-home)[14] + qvel[14] + previous_raw_action[14] + command[13]`。Free joint 的角速度直接作为躯干局部 gyro；投影重力为 `Rᵀ × [0,0,-1]`。关节按名字定位 qpos/qvel/actuator，不假设数组偏移。命令依次为 twist(3)、head(4)、body(6)，body x/y/yaw 强制零。

命令模长 `<0.05` 用 stand，其余用 walk；目标为 `HOME + scale × raw_action`，stand scale=1.0/gain=160，walk scale=0.9/gain=200。XML 增益按 gain/200 缩放。原始 action 写回下一帧观测，不把目标角或滤波结果写回。

过滤复选框默认采用 Rust `Tuning::default` 的目标 EMA：头 alpha=0.5、腿 alpha=0.7，第一帧不滤波；可以关闭做 A/B。训练仓库要求无 action low-pass，与运行时存在差异，所以这里明确记录设置，不声称与训练物理完全一致。没有对头目标再加一次命令，也没有额外观测归一化；现有 ONNX 自带 normalizer。

这是桌面 duck-body 的 **XML 位置伺服**路径，不是训练用 BAM M6。未移植 Rust 的完整 Safety、调度、skill、command EMA 或参数覆盖，仅在仿真内测试，不能直接控制实物机器人。

换 ONNX 后要重新生成桌面对照，不能只更新 manifest：

```bash
# 在工作区外层目录执行
python3 android-sim/scripts/prepare_assets.py
Pollen/microduck_rl/.venv/bin/python android-sim/scripts/policy_rehearsal.py
```

脚本会重建 `policies/smoke.json`、倾斜运动状态的观测测试夹具及桌面闭环报告。

## 导出测试结果

调试版安装后，在 Mac 终端执行：

```bash
adb shell run-as com.microduck.sim cat files/last-report.json
adb logcat -s MicroduckM1
```

`last-report.json` 每次显示状态都会更新；要保留基准结果，在测试完成后立即导出，或保持暂停。连接两台设备时给 adb 增加 `-s 设备序列号`。

后续修订增加独立测试归档，下一次构建起生效：`last-realtime-test.json`、`last-policy-benchmark.json`、`last-physics-benchmark.json`。普通状态、姿态重置及 Activity 重建不再覆盖这些文件，仅同类新测试覆盖旧结果。例如 `adb shell run-as com.microduck.sim cat files/last-realtime-test.json`。

也可以直接启动指定姿态的基准测试：

```bash
adb shell am force-stop com.microduck.sim
adb shell am start -n com.microduck.sim/.MainActivity --es keyframe SIT --ei benchmark_steps 2000
```

如需命令行构建，已有 `scripts/build.sh`；你使用 Studio 时不需要执行它。Debug APK 的标准输出位置为 `app/build/outputs/apk/debug/app-debug.apk`。

## 代码入口与构建边界

```mermaid
flowchart TD
  Source[Pollen 模型 XML / STL] --> Assets[prepare_assets.py + SHA256 清单]
  Assets --> APK[Android assets]
  APK --> Files[ModelAssets 解包并校验]
  UI[MainActivity 操作 / 状态] --> Worker[单个工作线程 / 20ms 周期]
  Worker --> JNI[NativeSim / native_sim.cpp]
  Worker --> Obs[61D observation]
  Obs --> ONNX[PolicyRunner / stand or walk]
  ONNX --> Action[14D raw action / scale / optional filter]
  Action --> JNI
  SourcePolicy[Local alpha ONNX + SHA256] --> ONNX
  Files --> JNI
  JNI --> Physics[MuJoCo CPU mj_step / dt 5ms]
  Physics --> Contact[状态 / 接触点 / 性能]
  Contact --> Capture[工作线程捕获状态与元数据]
  Capture --> Reports[报告线程 / 格式化与落盘]
  Reports --> UI
  Worker --> Snapshot[世界变换 / 接触点快照]
  Snapshot --> Latest[仅保留最新快照]
  Latest --> GL[独立 GLES3 线程]
  Mesh[编译后的 CAD 网格] --> GL
  GL --> Screen[3D 画面]
```

| 文件 | 作用 |
| --- | --- |
| `app/build.gradle` | SDK/NDK、CMake 构建和模型生成任务 |
| `app/src/main/cpp/CMakeLists.txt` | 固定 MuJoCo 版本、依赖、兼容补丁、16KB ELF 段对齐 |
| `patches/mujoco-3.10.0-ndk25.patch` | 将 XML 解析处的 string_view 迭代器构造改为同范围的指针+长度构造，适配 NDK25 libc++ |
| `app/src/main/cpp/native_sim.cpp` | 模型生命周期、关键帧、推进、有限数检查、接触配对和计时 |
| `app/src/main/cpp/visual_snapshot.h` | 编译网格导出、世界坐标变换/颜色/接触点快照 |
| `app/src/main/java/com/microduck/sim/NativeSim.java` | JNI 接口及库加载 |
| `app/src/main/java/com/microduck/sim/MainActivity.java` | 工作线程调度、操作按钮、JSON 结果和日志 |
| `app/src/main/java/com/microduck/sim/ModelAssets.java` | 保留模型相对路径、校验并解包资源 |
| `app/src/main/java/com/microduck/sim/PolicyRunner.java` | CPU 单线程 ONNX、尺寸/校验/桌面对照、网络选择和 raw action 反馈 |
| `app/src/main/java/com/microduck/sim/SceneFrame.java` | 快照校验与 MuJoCo/GLES 矩阵转换 |
| `app/src/main/java/com/microduck/sim/RobotSurfaceView.java` | GLES3、相机手势、轨迹、接触点、GPU context 生命周期 |
| `app/src/main/java/com/microduck/sim/VisualMesh.java` | 索引网格包、长度/索引/有限数校验 |
| `app/src/main/java/com/microduck/sim/VisualCadence.java` | 50Hz控制下的30Hz墙钟快照门限 |
| `app/src/main/java/com/microduck/sim/ReportDispatcher.java` | 独立报告线程、状态合并、完成测试保留 |
| `app/src/main/java/com/microduck/sim/LanControlServer.java` | 局域网HTTP页面、访问码、请求边界、状态读取 |
| `app/src/main/java/com/microduck/sim/RemoteCommand.java` | 命令和速度范围校验 |
| `app/src/main/java/com/microduck/sim/RemoteMailbox.java` | 单个等待确认命令、排队超时取消 |
| `app/src/main/java/com/microduck/sim/RemoteLease.java` | 3秒心跳与本地接管 |
| `app/src/main/assets/controller.html` | 第二台Android的浏览器控制页面 |
| `scripts/check_source.py` | Mac源码检查及带摘要的报告；不运行Gradle或安装APK |
| `scripts/host_baseline.py` | 桌面物理基线，结果见 `verification/host-baseline.json` |
| `scripts/policy_rehearsal.py` | ONNX 对照、观测夹具、过滤开关与速度命令的闭环预演 |
| `verification/native-contract-check.cpp` | 直接测试 Android JNI 调用的 C++ 观测/动作函数 |
| `scripts/visual_reference.py` | 使用 CPU mjv_updateScene 生成渲染桥参考，不需要 GL |
| `verification/visual-bridge-check.cpp` | 与 MuJoCo 可视化场景核对快照及网格顶点 |
| `verification/SceneFrameCheck.java` | Java 矩阵映射及非法快照拒绝测试 |

## 已验证与待验证

- 已完成 Android arm64 原生库的编译；现有 ELF 检查确认 JNI 库为 AArch64，依赖 `libmujoco.so`，LOAD 段采用 16KB 对齐。
- 桌面 MuJoCo 3.10.0 对三种关键帧各推进 2100 步，数值有限；模型维度符合上述预期。桌面速度不能代表手机速度。
- M1 已由用户 Studio 编译并在 V2185A 运行，三组物理基准为 22.27–23.67×，结果见 `verification/device-benchmark-summary.json`。
- M2 Java 源码对实际 ORT 1.20.0 AAR 通过 JDK 11 编译检查，JNI 通过 Android ARM64 编译器语法检查；未执行 M2 Gradle 打包、安装或启动，交由用户 Studio 完成。
- C++ 观测在包含倾斜、角速度、关节速度及非零命令的夹具上与 Python MuJoCo 参考逐项一致；动作缩放、目标过滤、增益和 NaN 拒绝通过测试，见 `verification/m2-checks.json`。
- 桌面闭环：站立 10 秒保持约 0.116m 高；vx=0.15 几乎未前进，vx=0.30/过滤开前进约 1.20m、横向漂移约 -0.22m、最终高度约 0.120m。过滤关闭也完成预演。详见 `verification/host-policy-rehearsal.json`；尚不代表手机步行已通过。
- M2 手机短基准已完成：500 周期/10 秒仿真，耗时 0.540 秒（18.51×），控制周期 p99=1.727ms、max=3.023ms；ONNX 对照误差约 6.71e-8。最终 x=1.207m、y=-0.230m、z=0.1193m，数值有限。与桌面最终坐标差约 +2.7/-8.8/-0.84mm，不能据此宣称整条轨迹完全一致。报告见 `verification/device-M2-policy-benchmark.json`，比较见 `verification/device-M2-comparison.json`。
- M3 静态与接口检查通过：Java 对 Android34/ORT AAR 编译检查通过，Android ARM64 JNI 语法检查通过。倾斜运动状态下，38 网格/70 部件的快照与 CPU `mjv_updateScene` 参考误差为 0；法线/顶点有限，Java 矩阵转换和非法快照拒绝测试通过。记录见 `verification/m3-checks.json`。
- M3 尚未由代理打包、安装或运行：用户用 Studio 构建；手机上的 GLES 着色器、外观、帧率、30秒控制节拍、GPU/温升与生命周期需要验收。没有把 M2 的速度当作 M3 的性能证据。
- M3 后续手机实测：日志找回的 30 秒完整报告见 `verification/device-M3-realtime-render-test.json`。墙钟30.013s/仿真30.02s，绘制回调53.74FPS，控制+快照p99=16.616ms/max=19.442ms，超过20ms周期0、迟到超过20ms周期0。最终x=3.501m/y=-1.225m/z=.1198m，数值正常；GLES ready、38网格已加载且无GL错误。结果说明这次短实时运行可行，余量较小且侧向漂移明显；不代表长时间温升/姿态切换或视频链路已经通过。用户随后重建 Activity 导致普通 last-report 被覆盖，故新增独立归档。
- 后续施工是手机 M3 验收、双设备命令通信和视频。当前 APK 尚不能替代桌面 `scripts/duck-sim up` 的完整系统。
- M3.1 用户已编译并运行：索引网格和30FPS真机验证通过，完整工作线程的报告发布成本偏高，见 `verification/device-M31-realtime-render-test.json`。
- M3.2 报告线程改造源码及队列测试通过；用户完成30秒开启3D的真机测试，工作线程p99=18.085ms/max=19.923ms，超20ms周期0，画面30.02FPS，位姿与之前一致。报告见 `verification/device-M32-realtime-render-test.json`。3D关闭对照、持续运行与生命周期尚未测，再推进双设备通信和视频。
- M4局域网浏览器控制源码/编译和Mac回环检查通过，见 `verification/m4-checks.json`。2026-10-04用户确认浏览器连接与按钮功能正常，ADB核对版本及远程运行/暂停，见 `verification/device-M4-lan-acceptance.json`；断连、生命周期与网络开启后的30秒预算待补测。操作见 `NETWORK.md`。

ONNX Java API 和资源关闭方式依据 [官方 Java 文档](https://onnxruntime.ai/docs/get-started/with-java.html)。
GL context/绘制线程的生命周期依据 [Android GLSurfaceView 文档](https://developer.android.com/reference/android/opengl/GLSurfaceView)；场景桥依据 [MuJoCo 可视化文档](https://mujoco.readthedocs.io/en/stable/programming/visualization.html) 和固定的本地 3.10.0 源码。
