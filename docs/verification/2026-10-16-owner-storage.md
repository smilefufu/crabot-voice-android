# 设备所有者加密存储验证

2026-10-16；实施计划 A2，依据已确认激活 spec 与 protocol-voice §13。这是端侧存储基础，尚未接通首次引导、wire6 后端或自动 Master 绑定；未升级真实手机。

内部密文格式升为 storage_version3，与 wire 版本分开。可保存无 pairing 的 owner metadata + 本地候选；后台分配后，pairing、稳定 activation、登记向量和 pending_owner_saved 一次原子保存。completed 只接受原操作的后台结果。所有者引用与当前样本独立，删除样本不清所有者或认主完成依据。

v1/v2 在原密文逐字节备份核验后迁移，保留 credential、向量、目录和待回执；备份冲突/保存失败保留原文件并拒绝使用。既有设备不从首条样本或名字推断所有者，只能采用后台给定稳定登记、已同步 active 联系人及本机兼容有效样本；未提供这份依据时不建立 owner。

先添加 5 项存储回归，未实现入口时编译失败；实现后增加到 8 项，覆盖无后台保存、重启、先落盘后回执、同操作重试、操作/身份篡改、终态幂等、删样本保人物、无效 metadata/vector、v2 备份、冲突备份失败保旧、旧设备稳定 ID 恢复及写盘失败不更新内存。完整 JVM18 项通过，产品和仪器 APK 构建成功。

在只读隔离模拟器 emulator-5584 运行 DeviceOwnerStorageTest、VoiceCatalogStorageTest、PairingUpgradeTest，13 项通过。只用各用例缓存目录，不打开麦克风、连接云服务、读取真实用户 terminal.enc 或删除 Keystore key。所有者档案不会显示在普通联系人目录；本地保存或 credential 不代表后台好友已建立。

最终 diff review：字段严格对应正式 DTO，存储额外字段仅内部使用；未增加配置项或权限入口。当前新存储入口尚未接 UI/网络，后续 A3/A4 接通再验收整条流程。没有安装用户手机、重启后端或声称真人首次激活通过。
