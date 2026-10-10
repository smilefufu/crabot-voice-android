# 冻结组件与分发许可

2026-10-11 构建基线。所有下载 URL 与 archive SHA-256 在 `tools/prepare-models.py`；模型单文件 SHA-256 在 `app/src/main/assets/model-files.json`，启动时再次验证。许可/NOTICE 随产品 APK 放在 `assets/licenses/`，模型不入 Git。

| 组件 | 实际版本/文件 | 来源与许可 |
|---|---|---|
| sherpa-onnx C/C++ API | `v1.13.8-android-aarch64-termux-shared`；分段/嵌入各 2 threads | [官方发行](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，Apache-2.0；只打包 C/C++ API 与 ONNX Runtime 库 |
| pyannote segmentation | 官方 `speaker-segmentation-models` archive 的 FP32 `model.onnx`，SHA 起始 `220ad67c` | [原模型](https://huggingface.co/pyannote/segmentation-3.0)，MIT；转换 archive 的 CNRS MIT 文本保留。未使用 INT8 |
| CAM++ | `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`，SHA 起始 `f682b514` | [3D-Speaker](https://github.com/modelscope/3D-Speaker)，Apache-2.0 |
| Silero VAD | 官方 `asr-models/silero_vad.onnx`，SHA 起始 `9e2449e1` | [Silero VAD](https://github.com/snakers4/silero-vad)，MIT |
| 中文 KWS | `wenetspeech-3.3M-2024-01-01-mobile`；encoder/joiner INT8、decoder FP32 | [发布者模型卡](https://modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01)，Apache-2.0；archive 模型卡原文保留 |
| ONNX Runtime | sherpa 官方包内实际 `1.28.2` | [该版本许可](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE)，MIT；保留该版本 `ThirdPartyNotices.txt` |
| Android C++ runtime | NDK `28.2.13676358` 的 `libc++_shared` | AGP/CMake 打包；保留该 NDK 的 `NOTICE.toolchain`，不从其它机器复制运行库 |

Java/Kotlin 层版本在 `app/build.gradle.kts`：AndroidX Activity 1.10.1、OkHttp 4.12.0、Coroutines 1.10.2、ZXing Android Embedded 4.3.0，均为 Apache-2.0 组件；Gradle artifact verification 覆盖编译与测试依赖。JUnit、AndroidX Test、MockWebServer/okhttp-tls 只用于测试，测试 APK不作为产品发布。

许可按组件/模型实际来源核对，不能用推理框架许可替代权重许可。APK 不包含 SenseVoice、Whisper、Kokoro 等 ASR/TTS 权重，也不包含实验 App 的录音或声纹向量。公开合成测试样本仅进 AndroidTest APK，出处见 `app/src/androidTest/assets/README.md`。
