# 2026-10-12 手机未检测到人声：诊断进度

OnePlus 7 Pro / 产品 0.3.3 的两次真人试说均提示“未检测到有效人声”。系统 RECORD_AUDIO 权限已授予，AppOps 和 AudioService 均记录约三秒采音；没有进入 turn_end/ASR/正式回答路径。系统同时记录 AudioRecord 重新建立底层输入的提示，但这本身不能证明麦克风无声或是根因。

新增 `VadStreamTest` 只使用现有固定公开音频，不调用麦克风、播放器、配对或声纹存储。仅运行此指定类，未传 disposable_voice_test，未运行会清数据的 NativeModelsTest。真机 1 项通过：完整处理所有 320/512 样本块，320 首次人声位于样本 7360（音频位置约 0.46 秒）、76 个检测帧；512 为样本 7168、47 个检测帧；重置后的零输入未误报。此证据验证 JNI/VAD 分块路径，不证明真实麦克风正常或真人识别准确率。

产品 0.3.4 / versionCode 7 保留数据安装，界面及协议保持，原采音来源 VOICE_COMMUNICATION、VAD 阈值、三秒无语音预算和业务准入均未改。结束一轮时仅在 Android CrabotVoiceInput 日志记录 samples/peak/rms/vad_seen 与 source/silenced/route 数字；不保存/输出原音频、文本、声纹或凭据。读取系统诊断异常被隔离，继续原停止/释放路径。

11 项 JVM、产品构建和签名验证通过，APK SHA-256：a7396d01fa73b5481dd81bcf33bbc952de8f8046a4a226a2131383df655acedd。安装后实际手机显示已连接、麦克风关闭，等待用户现场触发诊断轮。当前根因与修复均未确认，完整录放音仍未验收。

参考：固定库 [sherpa-onnx v1.13.8 VAD 实现](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/voice-activity-detector.cc)会累积不足窗口的输入；[Android isClientSilenced](https://developer.android.com/reference/android/media/AudioRecordingConfiguration#isClientSilenced())表示系统并发采音策略是否将输入静音。后一项需实际读取，不能从普通 AudioRecord 日志推断。
