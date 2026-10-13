# Android 增量语音播放实施计划

依据：[已确认 spec](../specs/2026-10-13-incremental-voice-playback-design.md)，正式接口唯一见 crabot-docs 的 protocol-voice v0.4.0。

1. wire v4、最终样本数与回应结束 → JVM 校验和旧版本拒绝；磁盘凭据格式不变。
2. 未知初始长度的 AudioTrack 播放与实际游标 → 公开 PCM 安全仪器测试、尾帧/缺帧/提前 end/取消；不采用户音频、不清数据。
3. 回应中各块关麦，response_end 后才接话 → 状态/迟到控制回归；保留前台、路由、失联释放规则。
4. JVM/指定安全仪器测试、APK 构建与本次 diff review → 保持签名，覆盖安装；不执行全套真机 instrumentation。
5. 与后端协调部署后，用户物理按钮短问答、工具问答和播报停止 → 记录实际听到、首音频、句间停顿与新输入等待；验收记录不存音频/声纹/凭据。

通过后直接提交推送 main，不走 PR。复用现有播放器须保留真实游标、帧校验及生命周期，不以接收完代替播放完。
