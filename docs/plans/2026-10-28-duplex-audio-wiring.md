# 真实音频参考、时钟与候选让声接线

依据已确认的自然轮次设计及正式 protocol-voice §5、§8；不改协议、提示词、身份输入或模型阈值，不部署、不连接设备。此次实现音频底座，未通过的端点模型和真机AEC不因接线完成而变为可用。

| 步骤 | 验证方式 |
|---|---|
| 原生I/O提供真实时序 | AudioRecord累计已读样本与MONOTONIC硬件timestamp映射；AudioTrack仅将成功write的范围交给参考。无timestamp时明确未同步，不用回调时间假装采样时间。回归覆盖部分写、零写、采音部分读、时钟回退/重启。 |
| 对接10ms APM输入 | 保持24k render/16k capture，由APM转换，不另写重采样器。固定APM源码/依赖摘要；参考与capture分别分帧，使用共同时间基计算delay。捕获未同步/错序/超界明确失效，原业务音频与登记不改。 |
| 同一播放器暂停与恢复 | 只pause/play，不flush、不创建新ID；并发写入串行核验。候选最多1秒，保留1秒总在途上限，不改5秒游标看门狗。拒绝恢复同一游标；确认才原interrupted，不产生completed或伪进度。 |
| 自动验证及交付 | 可控设备适配器运行真实播放器循环，30轮暂停/恢复连续PCM相等；过期/取消/旧token不影响后续播放；生产native核心host重放与Android交叉编译、全部JVM及两类APK编译。本次diff审查后提交推送Android main。 |

语义复用：现有Playback.stop会flush并报告终态，不能用于候选暂停；新候选API必须保留同一AudioTrack和真实队列。AudioTrack.timestamp用于声学时间映射，不代替playbackHead的实际消费回执。采音启停仍由原Controller许可控制，音频底座不能自行开麦，后台/失联等仍走原清理。自然插话判断入口在组件资格通过前不启用；不将软件重放冒充硬件AEC或中文端点验收。

## 本轮收口

组件接线、APM JNI与播放器机制已实现；72项JVM、30轮候选暂停恢复、原60轮待机恢复、4组/12000块真实产品host JNI差分及两类APK构建通过。见[验证结果及明确未完成项](../verification/2026-10-28-duplex-audio-wiring.md)。现有Controller仍为wire9；暂停期间可靠人声判定、自然候选与Controller衔接、实际声学与中文判停未完成，不将组件测试记为整套完成。未改现场、未连接设备，无新增spec偏差。
