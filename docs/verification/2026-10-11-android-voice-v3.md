# Android Voice v3 验证记录

2026-10-11，产品版本 0.3.0 / versionCode 3，终端协议整数 3。依据已确认的 Admin WS/WSS 接入 spec 与正式 protocol-voice.md v0.3.0。

## 已完成

- JDK 17、Android API 35、ARM64 构建：testDebugUnitTest、assembleDebug、assembleDebugAndroidTest 成功。11 项 JVM 测试通过，无失败/跳过。
- URL：同一 Admin 端口、WS/WSS、IPv6、编码渠道 ID、代理前缀；拒绝旧 /voice、公有查询/fragment、userinfo、路径穿越、非法端口与 link-local。
- TLS：实际 HTTPS 握手验证有效自签 pin 通过，错误/无 pin 和过期证书拒绝；隔离 JVM 信任库验证系统 CA 与 hostname 路径，包括代理证书与 QR pin 不同，以及 hostname 错误拒绝。
- 持久化：临时文件 fsync 后原子 rename；写入失败保留原文件、rename 失败清理临时文件，提交成功后才更新内存状态。
- 产品 APK SHA-256：ce2b38b1a794c9d511f089fdd49d1e6e294e1c10921f5bf986232acbbf612c03。
- 仪器 APK SHA-256：70b0d27ca8446e479a47cde90c6eec26234587c6475f9b007fa3294573740c17。
- apksigner verify 成功，Android Debug 签名；证书 SHA-256：8b20f31fe89d9cd746c9049cf6d8305ea79a3ebabb7aa4c852328550cdee0af0。私钥、APK 与模型不入 Git。

## 尚未执行

仪器包 7 项测试已编译，包括实际 JNI、Keystore、有效 v2 声纹保留与 v3 同渠道重配、未知存储保留、协议/配对心跳。当前没有可用的专用一次性模拟器，未执行本版本仪器测试。不要在保留用户数据的手机运行仪器测试。

ADB 当前没有检测到手机，尚未安装 0.3.0。本机 JVM TLS/文件测试不能代替 Android Keystore、清单 cleartext 策略、实际 WS/WSS 扫码或真机录放音。

## 现场验收顺序

1. 保留数据安装 0.3.0，验证初始关麦；有效 v2 提示同渠道重配，声纹/联系人不丢失。
2. 向导填写手机可达地址，分别检查 WS 和 WSS；核对一次性验证码，重连仍关麦。代理域名应验证系统 CA 和 hostname。
3. 确认 Master 渠道联系人与普通群权限，完成或撤销 pending 登记；完成前不能提交业务语音，测试通过后才启用。
4. 手动录音与播放、后台/断网关麦、点按打断；随后验证现场开启本地待机、唤醒后有限采音与误触发。
5. 真人 A/B/A、unknown、短句与电视背景，核对片段/文字/Friend 标注；50 轮延迟、AEC、功耗分别记录。当前均不作准确率或流畅性承诺。
