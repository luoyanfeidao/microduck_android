# 从 Git 恢复工程

本仓库管理 Android 源码、构建脚本、兼容补丁、推理对照和验证记录。构建产物、SDK 本机路径、MuJoCo 源码缓存、自动生成的 XML/STL/ONNX 副本不上传。它需要两个外部 Pollen 仓库和三份固定摘要的 ONNX，不能只 clone 本仓库就离线构建。

建议目录布局：

```text
microduck-workspace/
├── android-sim/                  # 本仓库
└── Pollen/
    ├── microduck/                # Rust runtime，含本地 policies/
    └── microduck_rl/             # XML/STL 模型来源
```

获取源码并固定到已验证提交：

```bash
mkdir microduck-workspace
cd microduck-workspace
git clone git@github.com:luoyanfeidao/microduck_android.git android-sim
git clone https://github.com/pollen-robotics/microduck.git Pollen/microduck
git clone https://github.com/pollen-robotics/microduck_rl.git Pollen/microduck_rl
git -C Pollen/microduck checkout a9ec4b2079ef8ee7904014089c885bb07d57d63c
git -C Pollen/microduck_rl checkout cb70b792312d559a4da09064d92009079671815f
```

三份策略在原工作区是 **本地未跟踪文件**，不随上面的 Git clone 获取。将 `alpha_stand.onnx`、`alpha_walking.onnx`、`roulade.onnx` 放到 `Pollen/microduck/policies/`。其 SHA-256 和模型资源清单见 [UPSTREAM.lock.json](UPSTREAM.lock.json)。站立/步行已有真机基线，M7.1整圈翻滚也已用户确认；抬头慢和整体僵硬尚未修复，见 [SKILLS.md](SKILLS.md)。不要用未经核对的最新版替代；启动时的 `policies/smoke.json` 会拒绝不匹配的策略。

随后打开 `android-sim`，让 Studio 生成当前设备的 `local.properties`，按 [README](README.md) 设置 SDK/NDK/CMake/JDK。构建前的模型生成任务会收集资源；MuJoCo 3.10.0 和固定版本依赖在缓存缺失时由 CMake 下载。首次同步和原生配置需要网络、Git、Python 3.9+。

如果替换了 ONNX，应在包含 onnxruntime/numpy/mujoco 的训练仓库 Python 环境中重新执行 `scripts/policy_rehearsal.py`，更新推理对照并重新验收，不能只修改摘要。

构建复用关系详见 [技术说明PDF](docs/Android_Microduck_方案架构与编译复用说明.pdf) / [Markdown](docs/Android_Microduck_方案架构与编译复用说明.md)：已有资源时Android打包无需Cargo或训练仓库.venv；只用标准库Python复制资源，NDK另行编译MuJoCo源码。`UPSTREAM.lock.json`是来源记录，资源脚本不会按它自动校验上游Git提交。

M8视频增加 `io.getstream:stream-webrtc-android:1.3.9` 与它的Kotlin stdlib1.9.25、AndroidX annotation1.9.1传递依赖。它是预编译Android Java/native AAR，由Gradle解析，不从两个Pollen仓库或mediad构建；不需要Android版GStreamer或Cargo。固定AAR摘要和来源见 [VIDEO_DEPENDENCIES.lock.json](VIDEO_DEPENDENCIES.lock.json)，原AGP/JDK的依赖DEX兼容检查见 [verification/m8-video-dependencies.json](verification/m8-video-dependencies.json)。整个APK的Studio构建仍待确认。
