# 2026-10-12 手机未检测到人声：定位与采音修复

OnePlus 7 Pro / 产品 0.3.3 的两次真人试说均提示“未检测到有效人声”。系统 RECORD_AUDIO 权限已授予，AppOps 和 AudioService 均记录约三秒采音；没有进入 turn_end/ASR/正式回答路径。系统同时记录 AudioRecord 重新建立底层输入的提示，但这本身不能证明麦克风无声或是根因。

新增 `VadStreamTest` 只使用现有固定公开音频，不调用麦克风、播放器、配对或声纹存储。仅运行此指定类，未传 disposable_voice_test，未运行会清数据的 NativeModelsTest。真机 1 项通过：完整处理所有 320/512 样本块，320 首次人声位于样本 7360（音频位置约 0.46 秒）、76 个检测帧；512 为样本 7168、47 个检测帧；重置后的零输入未误报。此证据验证 JNI/VAD 分块路径，不证明真实麦克风正常或真人识别准确率。

产品 0.3.4 / versionCode 7 保留数据安装，界面及协议保持，原采音来源 VOICE_COMMUNICATION、VAD 阈值、三秒无语音预算和业务准入均未改。结束一轮时仅在 Android CrabotVoiceInput 日志记录 samples/peak/rms/vad_seen 与 source/silenced/route 数字；不保存/输出原音频、文本、声纹或凭据。读取系统诊断异常被隔离，继续原停止/释放路径。

0.3.4 的 11 项 JVM、产品构建和签名验证通过，APK SHA-256：a7396d01fa73b5481dd81bcf33bbc952de8f8046a4a226a2131383df655acedd。该版本仅用于取得真人采音数字，当时根因与修复均未确认。

20:34 三次真人重试的数字日志已取得：source=7、silenced=false、route=15；样本数分别 45760、45760、42240，三轮 peak=7383、rms=5080、vad_seen=false。系统没有将输入静音，但这些相同音量值提示采音信号异常，不能据此认定是有效人声。HAL 日志实际使用 audio-record-voip / voice-speaker-voip-mic；原官方 Speaker ID 使用 MIC 来源。仍不能仅凭这些数据宣称来源切换已修复。

21:26 用户确认准备好后，仅执行 MicrophoneSourceTest.compareCaptureSources，两段各 3.5 秒；正常结束、1 项通过。只在明确 live_microphone_diagnostic=true 时允许现场采音，未设置此参数会跳过。相同手机、16kHz/mono/PCM16、内置麦克风、AEC 尝试和 VAD，仅更换来源：

| 来源 | 样本数 | peak / rms / mean | 不同帧数量 | 检测到人声的帧 |
|---|---:|---|---:|---:|
| VOICE_COMMUNICATION（7） | 54400 | 7383 / 5081 / 0 | 7 | 0 |
| MIC（1） | 55040 | 828 / 128 / -1 | 171 | 28 |

两者 silenced=false、route=15。当前产品选择的 VoIP 来源持续返回高度重复信号，人声检测失败；MIC 取得变化的真实输入并触发原 VAD。测试采用非阻塞读取以保证时长有界，人声帧数不能换算为准确语音时长或概率。临时采样在手机 RAM 中处理后清零；仅输出汇总数字，不输出帧哈希、音频、声纹或凭据，不上传，不清产品存储。

## 0.3.5 修复与验证

局部适配修复：AudioRecord 来源改为 MIC，沿用原采样格式、内置路由、AEC 尝试、前台/关麦、播放与生命周期。没有降低 VAD 阈值、修改声纹门槛或业务权限。将原 builder 原样提取为内部 createRecorder，供不录音的实际平台配置回归使用。

productRecorderUsesTheWorkingMicrophoneSource 只构造/释放 AudioRecord，不 start/read，不启动界面或访问配对/声纹存储；旧配置先实测失败 expected 1 / actual 7。修改来源后指定此方法及公开音频 VadStreamTest，真机 2 项通过（2.681 秒）。原 11 项 JVM、产品/仪器包构建及 apksigner verify 通过；未运行完整 instrumentation 或 NativeModelsTest。

产品 0.3.5 / versionCode 8，APK SHA-256：7c8c464b3db46eb10f412840b4827c4a034ec0c1e6ddf68504d2d6c8d36f531b。使用 install -r 保留数据升级，安装后、启动前加密 terminal.enc 摘要与升级前一致；未卸载、清空或重置真实登记。整数协议仍为 3，模型与 Keystore 不变。

实际手机已核实 versionName=0.3.5、versionCode=8，模型加载后显示“已连接 · 麦克风关闭”，日常“开始说话”入口恢复。上述证据验证来源选择和 MIC 真人采音/VAD，不等于完整产品问答验收。新 APK 按钮输入、ASR、Agent 接纳和真实回答播放待用户现场触发逐项核实。

参考：固定库 [sherpa-onnx v1.13.8 VAD 实现](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/voice-activity-detector.cc)会累积不足窗口的输入；[Android isClientSilenced](https://developer.android.com/reference/android/media/AudioRecordingConfiguration#isClientSilenced())表示系统并发采音策略是否将输入静音。后一项需实际读取，不能从普通 AudioRecord 日志推断。
