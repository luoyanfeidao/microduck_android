# Android Microduck 技术说明

- [详细PDF](Android_Microduck_方案架构与编译复用说明.pdf)：44页，36个章节书签，可检索正文。
- [Markdown正文](Android_Microduck_方案架构与编译复用说明.md)：与PDF使用同一份内容生成。
- 10张可单独使用的[矢量架构图](diagrams/)、20段逐字源码摘录、118处真实file:line引用。
- [源码快照与摘录](sources.json)、[文档检查结果](validation.json)。

重点回答Android项目如何复用`microduck`与`microduck_rl`：资源复制、Rust源码复用/Cargo交叉编译、Java/NDK链接、ORT AAR与APK组成；训练环境与Android的边界；BAM执行器及剩余训练差异；内嵌Rust与外部SimIo两种控制模式。

另覆盖运行时线程、61D/14D契约、独立机器人相机、WebRTC与HTTP控制、两种Rust模式的命令、单次翻滚/低速/抬头问题、JBR17/release11构建边界、新模型接入、阶段性交付价值及后续开发优先级。当前应用版本为M10 / `0.10.0-m10` / versionCode18，局域网访问码为4位数字。

## 内容与来源

核对日期2026-10-05，Android运行源码基线`be5fea0`，两个上游HEAD及每个引用文件的SHA-256见`sources.json`。引用包含当前本地文件状态，不仅是Git提交内容；ONNX精确训练run/checkpoint来源尚未补齐。

本次只生成文档，没有新增Gradle/APK构建、训练或设备操作。用户已确认M10实机验证通过，记录在 `verification/user-M10-feedback.json`；这是用户功能确认，未附性能/温升/codec或细分动作测量。BAM CPU数值对照、真实Rust/Java/JNI/ORT闭环及ARM64链接均使用已有M9/M10检查证据。低速不走、抬头慢/僵硬、任意姿态成功和完整训练DR/延迟仍有明确未验证项；性能、温升和生命周期专项继续延期。

文件路径缩写：`A/ = android-sim/`、`R/ = Pollen/microduck/`、`T/ = Pollen/microduck_rl/`、`M/ = android-sim/.native-cache/mujoco-3.10.0/`。完整路径与行号在PDF/Markdown末尾索引及`sources.json`中。

## 维护和重建

- `content.py`：正文、表格、关键代码的选择与解释。修改这里后重新生成，不直接修改生成的Markdown。
- `diagrams.py`：架构图绘制与SVG导出。
- `build_guide.py`：依据当前真实源码提取代码、定位引用、生成PDF/Markdown和源码快照。
- `validate_guide.py`：检查引用摘要、逐字摘录、SVG、PDF书签/文本与页面越界，并生成本地预览。

在完整工作区根目录执行；需要保留Android与两个Pollen仓库的相对位置，但文档导出不执行Cargo或训练，不要求训练仓库`.venv`、CUDA或现成ONNX文件：

```bash
python3 -m venv /private/tmp/microduck-android-docs-venv
/private/tmp/microduck-android-docs-venv/bin/pip install -r android-sim/docs/requirements.txt
/private/tmp/microduck-android-docs-venv/bin/python android-sim/docs/build_guide.py
/private/tmp/microduck-android-docs-venv/bin/python android-sim/docs/validate_guide.py
```

本次工具为reportlab5.0.1、PyMuPDF1.28.2、Pillow12.3.0，均为文档工具，不加入APK。字体默认使用macOS的`/Library/Fonts/Arial Unicode.ttf`与`/System/Library/Fonts/Supplemental/Andale Mono.ttf`；其他机器通过`MICRODUCK_DOC_FONT`、`MICRODUCK_DOC_MONO`指定可用的TTF文件，正文字体需包含中文。

源码更新后，生成器检查定位锚点是否唯一，校验器检查文件SHA-256和代码摘录是否仍一致；这些检查不能代替对解释文字和架构关系的人工核对。整数行号引用也须复核。

预览位于`previews/`，不提交Git；PDF、Markdown、SVG、生成器及检查结果提交。当前检查通过：44页、28书签、118处引用、20段摘录、10个SVG，未发现正文越界。封面、BAM、内嵌Rust、Cargo链接、SimIo、ORT解释与项目价值页面已进行视觉检查。
