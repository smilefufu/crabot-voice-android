# 原生推理测试样本

`voice-service-test.wav` 只进入仪器测试 APK，不随产品 APK 分发。它是固定公开文本“你好，我是螃蟹。”的合成语音，来自 2026-10-10 MLX-Audio 0.5.8 / Kokoro v1.0 的中文接口实验，使用 `zf_xiaobei`、`lang_code=z`，经 FFmpeg 转为 16kHz/mono/PCM16。Kokoro 模型许可为 Apache-2.0，权重不随样本分发。

测试验证 JNI/VAD/分段/嵌入接口以及相同向量的原生比对，不能证明真人身份或多人归属准确率。无用户录音、声纹或凭据。

`NativeModelsTest` 同时要求 `ranchu` 模拟器和显式参数 `disposable_voice_test=true`，其中 Keystore 测试会清除该测试 App 的存储。不得把此参数用于保留有用数据的模拟器。
