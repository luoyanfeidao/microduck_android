# 第三方来源

- `gradlew` 与 `gradle/wrapper/gradle-wrapper.jar`：Gradle Wrapper（Gradle 7.5），Apache-2.0；脚本保留原版权头。
- `patches/mujoco-3.10.0-ndk25.patch`：基于 google-deepmind/mujoco 3.10.0 的 XML 解析源码，Apache-2.0；指针+长度的兼容修改见补丁。
- 构建时下载的 MuJoCo、其依赖和 Maven ONNX Runtime 包保留各自的上游许可证；本仓库不提交其源码缓存或二进制库。
- 模型/策略的外部来源与固定版本见 `DEPENDENCIES.md` 和 `UPSTREAM.lock.json`；生成的模型与 ONNX 副本不提交。

Apache-2.0 正文见 `third_party_licenses/Apache-2.0.txt`。本文列出第三方组件，不对本仓库自写代码另行指定开源许可证。
