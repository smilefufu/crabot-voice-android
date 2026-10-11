# Android Admin 共用入口实施计划

依据：本仓 2026-10-11 已确认 WS/WSS spec，正式 protocol-voice v0.3.0。工作在现有隔离分支，测试/review 后直接合入并推送 main，不走 PR。

| 步骤 | 实现与验证 |
|---|---|
| 1 URL/版本 | v3 QR 与带前缀实例路径；WS/WSS、有效端口、中文 ID；旧协议/非法 URL/pin 拒绝 |
| 2 连接 | WS 明文、WSS 有效精确 pin 或系统 CA+hostname；独立受控服务测试错误证书/重定向/无降级及断连关麦 |
| 3 加密数据 | storage_version 与 wire 分离；独立 v2 fixture 同渠道重配保持向量，原子失败保留旧状态，不同渠道不覆盖 |
| 4 交付 | 构建配套 APK，定向回归与本次 diff review；真机保留数据原位升级，WS/WSS 配对/有限录放音及旧声纹验证，未验收条件明确记录 |

不复用 forget 作为迁移，不在真实手机跑会清数据的测试，不调整分段/嵌入模型、群权限或后台采音。
