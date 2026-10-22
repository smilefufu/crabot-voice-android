# 语音剩余验收：独立检查记录

日期：2026-10-22。依据已确认的任务反馈、所有者激活、声音管理及连续长回答设计。正式契约唯一见[protocol-voice v0.9](https://github.com/smilefufu/crabot-docs/blob/main/protocols/protocol-voice.md)，本文件不另定义协议。

## 本轮结果

- 声纹历史漏认按用户要求改为**待复现**，不降低阈值、不重新录制、不继续临时观察。当前产品仍Android0.8.0/code17、源码210a251，后端已部署132d696a；本轮没有产品实现或提示词修改。
- Android JVM30项使用`testDebugUnitTest --rerun-tasks`实际重跑通过；生产原包保持，新增instrumentation测试APK编译通过。
- 后端定向Agent71、Voice Channel40、Admin75、Shared28项通过；加JVM合计244项。Shared按node:test入口运行，前置误用Vitest/错误路径的执行不计通过。覆盖异步回报、静音与恢复、私发隔离、认主与登记持久化、旧样本保留、目录版本、断流和重复回执。

## 独立真机：停止后不误交付或污染下一次播放

新增`PlaybackReceiptTest.stoppingDiscardsBufferedAudioAndCannotCompleteOrAffectTheNextPlayback`，仅使用静音PCM和真实AudioTrack，不启动Activity、麦克风、模型、网络或SecureStore。

断言：实际started后stop在1秒内返回，重复stop仅有一次interrupted；保存实际部分游标，停止后的旧帧拒绝；下一playback能完成，旧playback不会completed。JUnit在OnePlus7Pro通过，0.532秒。

仅以原ADB服务器/密钥覆盖测试APK并执行这个指定方法；不运行会改变真人记录的完整connectedAndroidTest、不覆盖产品APK或清数据。安装和测试前后真实`terminal.enc`摘要相同；测试不保存音频、向量或凭据。该结果证明播放器停止行为，尚不证明“打断并说话”按钮到下一轮业务的整体体验。

## 现场播报的独立诊断

近期三轮入站accepted且模型有voice_reply，播放记录为unknown。两轮模型分别出现5次502重试，整个模型回合约70.2/85.8秒；最新一轮无该重试但仍无完整播放。不能拿识别成功、有tag或HTTP200当播放成功。

固定公开短句独立复现本机TTS在HTTP200后断流，日志指向原音色文件缺失。恢复同一Kokoro固定revision的522320字节音色并核验SHA-256后，三条API返回完整WAV（172/281/330毫秒）；产品音频适配器又一次核验有效PCM（270毫秒、104400样本）。没有通过手机发声、调用付费模型、修改配置或重启Crabot/ADB。外部服务其他依赖和模型文件的冷启动可靠性仍须单独准备，不以原进程仍在证明可重启。

## 后续顺序

整组优先级、各项退出条件及后端证据见[当前验收清单](https://github.com/smilefufu/crabot-docs/blob/main/superpowers/plans/2026-10-22-voice-remaining-acceptance.md)。仍缺50轮首声统计、真实按钮/三轮接话、异步任务在静音/离线恢复后的整体体验，以及全语音首次添加/重录、多位真人和首次激活体验。唤醒误触发、功耗及Linux双UID另有验收，不用公开文件代替。

本轮无需用户配合。后续确需真人时，先给出要验证的假设、最少动作、通过/失败判据与停止条件；历史漏认不因新成功而宣称已修复。
