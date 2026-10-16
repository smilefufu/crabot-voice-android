# 所有者激活：端侧流程验证

日期：2026-10-16；候选 APK 0.6.0 / code14 / wire6。仅在隔离只读模拟器测试，不安装用户手机，不修改真实配对/FuFu 数据。

## 结果与证据范围

- JVM18通过，产品和仪器 APK 构建通过；候选均值计算去重后重跑。
- 隔离 DeviceOwnerStorage10、PairingUpgrade2、VoiceCatalogStorage3、TerminalConnection2、VoiceWaitTimeout3、PairingUi1，共21通过。
- DeviceOwnerFlow3通过：三段公开语音实际 JNI 质量处理后加密保存未配对所有者；倒计时退后台撤回候选，旧计时器不打开麦克风；loopback 配对验证映射/凭据原子保存先于 owner_saved，前台心跳先于回执，所有者完成仍等待 setup，setup 完成仍关麦。没有采样音频上传。
- NativeEnrollmentIdentity2通过：同一公开说话人三段真实特征一致，不同人替换第三段被拒绝。不是家庭或儿童准确率结论。
- 新增旧档案恢复两项先复现失败后通过：显式重录后的当前 active 样本可恢复原人物；保存回执后撤销原样本不使整个密文不兼容。历史 operation/profile/registration 保留，不复活旧样本。
- 实际模拟器登记页人工看图核验：名字、三步说明、一个开始按钮；空名字禁用开始。正常流程三段自动推进，失败才出现单段重录，完成进入扫码。保留取消与系统权限入口。

所有仪器存储使用独立 ContextWrapper/cache 文件；测试公开音频仅内存回放，不读取或清除用户 terminal.enc，不删除 Keystore。协议服务使用 loopback MockWebServer，不调用云服务。

## 待真人/整组验收

真实首次录制与新渠道配对、后台联系人/Master 可查、唤醒本人并回答；既有 FuFu 保留数据统一升级；权限拒绝/来电/真实焦点路由、多人/重叠与整体延迟。整组权限及语音管理完成前不部署半套 wire6。
