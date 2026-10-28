# APM 隔离构建、软件回声和原 VAD 对照

2026-10-28。按已确认自然语音设计继续 A3，执行[预先固定的计划](../../plans/2026-10-28-apm-isolated-verification.md)。本轮完成组件构建和文件回放，没有接入 App、安装 APK、连接 ADB、开麦/播音、修改本机部署或调用付费模型。

**构建与固定软件回声链路已实际跑通；真实声学、自然打断和中文判停仍未验收。** 不能把软件回声中的零 VAD 阳性当作家庭环境零误打断。

## 构建和输入

沿用此前固定的 APM 1.3 发行源码 SHA，不声称它是最新版本或效果最优版本。依据上游[独立包构建说明](https://freedesktop.org/software/pulseaudio/webrtc-audio-processing/)和该固定源码内 `audio_processing.h`，使用 10ms 帧、float reverse 24kHz、capture/output 16kHz，先 reverse 再 capture；原 App 20ms 帧的拆分、真实 AudioTrack 参考和时钟尚未接入。

- 原源码及其 wrap 内 Abseil 源码/补丁摘要均通过。WrapDB TLS 下载失败，使用 Meson 官方 GitHub 同摘要附件；未禁证书校验或修改算法。来源见[provenance](apm-provenance.json)。
- macOS ARM64、Android ARM64/API29 两侧各 462 个构建任务完成，四并发；有缓存依赖、全新构建目录分别约 39.88／37.24 秒，另加链接 0.60／0.52 秒。不是新机器从下载开始的总耗时。
- Android 探针约 1.11MB，静态归档合计约 8.61MB；这不是 APK 增量。79 个 C++ 符号由已有构建 APK 的 `libc++_shared` 提供，另一个 `__cxa_atexit` 由 API29 系统 libc 提供，未发现动态符号缺口；没有执行手机或完成 JNI 验收。
- [10 项探针检查](test-apm-probe.py)通过，覆盖混合采样率的关闭 AEC 原样透传、长度/非法输入拒绝、对齐偏移保留及线性导出配置。分发前还须完成上游/Abseil/所带第三方许可告知核对，本轮不分发第三方二进制。

两份已有公开中文音频按原来源 SHA 校验，只做固定 RMS 和重采样，生成预测前冻结的 30 秒、7 组[输入清单](apm-input-manifest.json)。远端含一次 0.6 秒暂停，回声为已知 80/95/110ms 三径，近端有两次六秒输入。没有使用用户录音或声纹。正常 APM 默认配置仅启用 AEC3，未启用 AGC 或降噪，不调阈值；软件混音不模拟真实房间。

## 实际结果

| 对照 | 测量结果 | 能得出的结论 |
|---|---|---|
| 关闭 AEC | 三组均逐样本保留 capture，render 差异为0 | 探针没有提前处理或丢弃输入。 |
| 只有回声，正常 AEC | 前0–5秒衰减30.42dB，5–10秒48.41dB，15–20／25–30秒60.83／60.33dB | 这个固定路径中确实消费了参考并削弱回声，包含起始适应阶段。 |
| 只有回声，参考为零 | 仅约0.09–0.11dB变化 | 不能把无参考时的固定滤波当成有效消回声。 |
| 只有近端，正常 AEC | 约8ms对齐偏移；电平约−0.01dB，对齐相似度0.969 | 处理引入固定延迟/滤波，未把这条近端输入整体压低。 |
| 双讲，正常 AEC | 两段电平约−0.81／−1.24dB，对齐相似度0.911／0.874；对干净原始参考的SI-SDR为6.90／5.10dB | 波形有额外变化，不能仅以回声衰减称双讲通过，也不能把这些数直接解释成识别错误率。 |

开 AEC 的每10ms reverse+capture处理，宿主 P95 约0.071–0.098ms，所测最大1.446ms；不含 JNI、设备队列和手机性能，也不等于端到端首声。独立进程首个case墙钟3.34秒，后续约0.03–0.23秒，单独保留，不混进逐帧时间，也不将该差异未经核实归因于 APM。完整[首次对照](apm-first-controls-results.json)和[重新构建对照](apm-controls-results.json)保留，七项音频输出摘要一致。

## 双讲归因及原 VAD

追加诊断只开放标准线性输出观察，不改变最终输出：三组普通输出摘要与原结果完全一致。线性双讲相似度0.958／0.961，电平约−0.04／−0.01dB；线性延迟4ms，最终输出8ms。证据支持额外变化出现在后续抑制处理，不能据此把线性旁路直接选为产品路径。

诊断初次 SIGSEGV 是探针仅设置 APM 外层导出开关，遗漏了此版本 AEC3 factory 的配套导出配置；崩溃位于 `BlockFramer::InsertBlockAndExtractSubFrame`。修正两处配置并统一 Release 编译定义后，新增回归通过，三份正常输出仍与原摘要相同。未改上游算法，没有把这个探针错误记成产品或组件故障。[验证记录](apm-verification.json)、[线性结果](apm-linear-results.json)。

再将7份普通输出与3份线性输出量化回PCM16，按原320样本喂给 **sherpa-onnx1.13.8、原silero SHA、原0.5/0.05秒/16秒配置**，不重新归一化残留、不改变阈值或时长：

| 只有回声的输出 | VAD阳性帧 / 1500帧 |
|---|---|
| 关闭 AEC | 963 |
| 正常 AEC | 0 |
| 正常 AEC，参考为零 | 963 |
| AEC线性旁路 | 1464 |

这排除了“线性波形更像原声，所以直接用于插话检测”的推断。线性旁路在本批中仍会把残留当成人声；保持原正常 AEC 输出方向，不据波形分数换路径。

双讲正常输出两次首次阳性出现在片段开始后600／640ms，干净参考为560／560ms，相对增加40／80ms。片段自身含开头静音；这些不是从真实开口计量的交接延迟，更不是P95验收。整段阳性435帧，干净参考554帧；两份VAD状态不是人工人声标注，不能机械解释为漏掉119帧内容。仍需验证短词、VAD候选确认、语音保留、身份一致性与真实声学。[逐项区间和时间](apm-vad-results.json)。

## 复现及下一步

使用独立工具环境，版本见[依赖记录](apm-dependencies.json)。先从 `apm-provenance.json` URL获取并校验源码及其 wrap 依赖，安全解压到隔离目录。构建脚本禁止自行下载，不安装库：

```sh
python build-apm-probe.py --root "$APM_WORK" --source "$APM_SOURCE" --ndk "$ANDROID_NDK_HOME"
python probe-apm.py --root "$AEC_CONTROLS" --public-audio "$PUBLIC_AUDIO" --sources "$SOURCE_MANIFEST" --prepare-only
python probe-apm.py --root "$AEC_CONTROLS" --public-audio "$PUBLIC_AUDIO" --sources "$SOURCE_MANIFEST" --probe "$APM_WORK/probe-apm-host-verified"
APM_PROBE="$APM_WORK/probe-apm-host-verified" python test-apm-probe.py
python probe-apm-linear.py --controls "$AEC_CONTROLS" --probe "$APM_WORK/probe-apm-host-verified" --output "$AEC_LINEAR"
python probe-apm-vad.py --controls "$AEC_CONTROLS" --linear "$AEC_LINEAR" --model "$SILERO_MODEL" --output "$AEC_VAD_RESULTS"
```

采样/模型文件和原始数值日志只留隔离目录，不入库。构建脚本生成临时工具链配置，仓库不写本机路径。未验范围是实际 render 参考/播放游标、采音时钟、系统AEC单一路径、假候选暂停恢复、端侧处理/功耗与身份兼容；随后才具备真实录放音和自然打断验收条件。中文端点候选 A2 仍未通过，持续待机已有源码修复也尚未升级手机；这次 A3 组件进展不替代它们。
