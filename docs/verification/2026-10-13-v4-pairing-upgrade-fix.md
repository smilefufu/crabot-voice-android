# 0.4.1 升级后重连修复

日期：2026-10-13。用户反馈 0.4.0 显示“尚未连接”。这是已确认“保留有效 v3 配对覆盖升级”的局部实现偏差，不改协议或存储格式。

## 根因与修复

SecureStore 的 storage_version=1 读取校验和 TerminalConnection 的连接校验均要求保存的 pairing.protocol_version 等于当前 wire 版本。升级为 wire 4 后，有效 v3 保存记录因此被拒绝，不能进入网络握手。此前加密文件摘要相同只证明数据未变，没有证明仍可使用它连接。

读取及 hello 重连兼容有效的 v3/v4 保存记录；新二维码、新配对保存和所有网络握手仍只允许 v4。既有 URL、TLS pin、凭据、渠道和向量校验保持；未知旧版本仍拒绝，v2 显式修复路径不变。错误文案使用当前 wire 版本。

## 证据

- `PairingUpgradeTest.validV3PairingAndVectorsRemainReadableWithoutRewriting` 在修复前真机复现失败；夹具只写 App cache 的独立目录，没有操作用户 terminal.enc 或删除 Keystore key。
- 修复后同项以及 v3 保存记录发送 v4 WebSocket hello 的回环检查，两项真机测试通过；全套 JVM 14 项通过。新配对 v3 和未知版本仍拒绝。
- 产品/仪器 APK 构建通过，0.4.1 / versionCode 10 使用 install -r 覆盖安装。升级与指定测试前后，用户 terminal.enc SHA-256 相同；没有清数据、重新扫码、录音或运行完整 NativeModelsTest。
- 当前接线被 OnePlus 报告为 AC powered，因此仅 USB 的常亮设置无效。按既有调试不锁屏要求调整插电常亮；系统密码锁仍需用户现场解锁。

用户解锁后，本机真实网络重连已核验：App 显示“已连接 · 麦克风关闭”，开始说话按钮可用，后台 Voice capability 为 4；使用原有配对，没有重新扫码。语音回答、整体延迟和打断继续单独验收。
