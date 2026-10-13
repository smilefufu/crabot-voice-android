# Android 0.4.0 / wire v4 增量播放验证

日期：2026-10-13。实现依据为已确认的[端侧 spec](../specs/2026-10-13-incremental-voice-playback-design.md)及正式 protocol-voice v0.4.0。

## 已验证

- JDK 17、SDK 35、NDK 28.2.13676358；APK versionCode 9 / versionName 0.4.0。官方模型与 native archive 重新按固定 SHA-256 校验，未改变模型或声纹阈值。
- 13 项 JVM 测试通过，包含未知初始播放长度、最终样本数、连续序号、短尾帧及非法帧拒绝。产品 APK 与仪器 APK 构建通过。
- 用户重新连接并解锁 OnePlus 7 Pro（Android 10）后，用 `adb install -r` 从 0.3.5 覆盖升级；未卸载、未清数据。升级前后以及指定仪器测试后的 `files/terminal.enc` SHA-256 相同。
- 仅执行 `PlaybackReceiptTest`，1 项真机测试通过：合成静音 PCM 在最终长度尚未提供时已发生实际播放；提供最终 count 后，实际 AudioTrack 游标达到 7200 才返回 completed。测试不录音、不加载声纹模型、不操作存储；没有执行会清数据的 `NativeModelsTest`。
- 同标签块各句共用播放器；每块 completed 后等待并保持关麦，只有当前 turn 的 response_end 才进入一次原有 8 秒接话窗口。取消、失败和旧 turn 不能自动开启窗口。

## 验证边界

后端配置模型 gpt-6.1-sol 的七个合成场景通过：问答、查询、跨渠道投递、旧 send_message 历史、澄清、失败与互聊沉默。该实验的工具和播放回执均为本地模拟，仅验证模型格式和运行时交付判定，不代表真实音频延迟。

后端已实现精确标签提取、逐句完整 WAV 合成与连续 PCM 发送；当前 TTS 服务仍是逐句请求，并非 Provider 原生流式 TTS。外发后不整请求重播，持久事实与实际完整交付分开。

真人短问答、工具问答及播放中按钮停止仍需本版现场验证；不能用静音播放器测试代替人类听到回答。自然抢话/AEC、声纹准确率、多成员、功耗、50 轮 median/P95 和 Manager 调度抢占不作为本次已完成项。
