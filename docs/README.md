# Android Microduck 技术说明

- [详细PDF](Android_Microduck_方案架构与编译复用说明.pdf)：32页，28个章节书签，可检索正文。
- [Markdown正文](Android_Microduck_方案架构与编译复用说明.md)：与PDF使用同一份内容生成。
- 6张可单独使用的[矢量架构图](diagrams/)、14段逐字源码摘录、98处真实file:line引用。
- [源码快照与摘录](sources.json)、[文档检查结果](validation.json)。

重点回答Android项目如何复用`microduck`与`microduck_rl`：资源复制、Java/NDK编译、ORT AAR引入、APK组成；Rust/Python运行环境与Android的边界；同为ARM64时二进制ABI的差异；训练BAM与手机XML伺服的差异。

另覆盖运行时线程、61D/14D契约、独立机器人相机、硬件WebRTC与HTTP控制、单次翻滚及抬头诊断、Studio使用、新模型接入、变更传递关系、检查证据及后续方案。当前应用版本为M8 / `0.8.0-m8` / versionCode15。

## 内容与来源

核对日期2026-10-04，Android运行源码基线`6c572c5`，两个上游HEAD及每个引用文件的SHA-256见`sources.json`。引用包含当前本地文件状态，不仅是Git提交内容；ONNX精确训练run/checkpoint来源尚未补齐。

本文没有执行新的Gradle/APK构建、安装、启动、训练或真机测试。关于摄像头/整圈翻滚的结果来自已有用户反馈和设备报告；头部恢复诊断来自已保存的主机对照。当前抬头慢/整体僵硬尚未修复，性能、温升和前后台验收按用户要求延期。

文件路径缩写：`A/ = android-sim/`、`R/ = Pollen/microduck/`、`T/ = Pollen/microduck_rl/`、`M/ = android-sim/.native-cache/mujoco-3.10.0/`。完整路径与行号在PDF/Markdown末尾索引及`sources.json`中。

## 维护和重建

- `content.py`：正文、表格、关键代码的选择与解释。修改这里后重新生成，不直接修改生成的Markdown。
- `diagrams.py`：架构图绘制与SVG导出。
- `build_guide.py`：依据当前真实源码提取代码、定位引用、生成PDF/Markdown和源码快照。
- `validate_guide.py`：检查引用摘要、逐字摘录、SVG、PDF书签/文本与页面越界，并生成本地预览。

在完整工作区根目录执行；需要保留Android与两个Pollen仓库的相对位置，但文档导出不要求训练仓库`.venv`、CUDA或现成ONNX文件：

```bash
python3 -m venv /private/tmp/microduck-android-docs-venv
/private/tmp/microduck-android-docs-venv/bin/pip install -r android-sim/docs/requirements.txt
/private/tmp/microduck-android-docs-venv/bin/python android-sim/docs/build_guide.py
/private/tmp/microduck-android-docs-venv/bin/python android-sim/docs/validate_guide.py
```

本次工具为reportlab5.0.1、PyMuPDF1.28.2、Pillow12.3.0，均为文档工具，不加入APK。字体默认使用macOS的`/Library/Fonts/Arial Unicode.ttf`与`/System/Library/Fonts/Supplemental/Andale Mono.ttf`；其他机器通过`MICRODUCK_DOC_FONT`、`MICRODUCK_DOC_MONO`指定可用的TTF文件，正文字体需包含中文。

源码更新后，生成器检查定位锚点是否唯一，校验器检查文件SHA-256和代码摘录是否仍一致；这些检查不能代替对解释文字和架构关系的人工核对。整数行号引用也须复核。

预览位于`previews/`，不提交Git；PDF、Markdown、SVG、生成器及检查结果提交。当前检查通过：32页、28书签、98处引用、14段摘录、6个SVG，未发现正文越界。封面、编译链、线程与相机图已进行视觉检查。
