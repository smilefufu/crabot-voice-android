# 连续长回答与首声速度实施

依据：用户已确认[安卓设计](../specs/2026-10-19-continuous-long-voice-playback-design.md)及后端配套，正式wire只引用[protocol-voice v0.9](https://github.com/smilefufu/crabot-docs/blob/main/protocols/protocol-voice.md)。

| 步骤 | 验证方式 |
|---|---|
| Long进度与回执兼容 | 三分钟单播放、错误序号/尾帧、旧v1回执备份后迁移v2，超过Int累计数可恢复；错误不改原文件。 |
| 连续播放器 | 原AudioTrack消费及真实游标，500ms进度、只报告实际完成；有界队列、等待后段与卡住分开，管理读句仍有限。 |
| wire9与正文展示 | 有序文字及最终段数核对、旧配对保留、开始不等全文，原60秒不截断活跃回答。 |
| 构建与设备 | 定向JVM及生产/测试APK构建、diff审查后按本仓main提交；联合升级前不安装、不清数据。再做真实首声、长答、停止和额外空档，真人测试需配合时才邀请。 |

Playback是共用播放层，只提供音频消费与回执，不决定任务、身份、采样或投递目标；GuidedEnrollment仍核对有限管理读句完成后才开麦。文件/虚拟时钟、实际AudioTrack和真人听感分别记录。

## 当前验证记录

- 2026-10-19：JVM 30项全部通过；生产与instrumentation APK构建通过。OnePlus7Pro上的单一AudioTrack合成播放用例实际181.094秒通过，4344000样本完整消费，gap_count=0、gap_ms=0；只运行PlaybackReceiptTest指定方法，没有采音或重置数据。
- 后端真实模型输出649字长故事，现有Kokoro API分40段、175.75秒音频按实时速率完整消费，首PCM约265毫秒、在途峰值24000样本、传输缓冲耗尽额外空档0。消费端为替身，不是真机听感或首声验收。
- Android main210a251 / 0.8.0/code17与后端132d696a / wire9已联合运行；新旧APK公钥签名一致，加密配对/声音文件覆盖安装前后SHA-256一致，8字节旧回执及旧APK已单独保留。首页已连接、麦克风关闭，等待用户正常唤醒测试长回答、停止及50轮终端首声目标。本轮未更换密钥或重启ADB。
