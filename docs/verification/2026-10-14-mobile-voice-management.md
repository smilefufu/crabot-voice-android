# 手机日常界面与声纹管理候选验证

依据：用户确认的 2026-10-14 两端 spec；正式 protocol-voice v0.5.0。

候选 APK 0.5.0 / versionCode 11 / wire 5。保留原应用 ID、签名、Keystore alias 和有效 v3/v4 配对；加密存储升级至 2，后台元数据升级至 3。使用“回答为主”界面，首次连接、一次开启本地唤醒、设置及声纹管理入口分开。

已完成：APK、指定仪器测试 APK 编译及 14 项 JVM；后台 Shared 38、Voice Admin/Store 30、Channel 10、Agent 64、Web 9、既有好友/onboarding 13 项定向回归，以及完整模块/Web构建。Channel 测试通过真实回环 WS/HTTP 验证提交后联系人可查、目录推送、重复原请求/回执和最新目录确认门禁；不是物理手机的 2 秒实测。新增 Agent 装配回归验证 wire 5 capability 同时启用专用提示词和标签输出。

后端 main 冲突解决后完整构建再通过；Core174、Shared141、Voice16、CLI193、安装库88、升级库36全量通过。Admin1324通过/2个旧断言失败、Web352通过/1个旧断言失败均在未修改 main 复现。Agent已报告的39个失败在main复核，并发bootstrap资源波动定向复跑通过，旧连接恢复文件在240秒后停止（main60秒也未收敛），其余文件已补跑；不声称整仓全量绿色。新旧APK公开签名证书一致。

2026-10-14 16:44–16:45（Asia/Shanghai）OnePlus 7 Pro 已保留数据覆盖安装 0.5.0/code11，并完成指定 PairingUpgradeTest 与 VoiceCatalogStorageTest 的 5 项真机回归。这些只使用自己的 cache 文件及回环握手；测试前后真实 terminal.enc 摘要一致。启动新版后原密文 v1 备份与升级前逐字节一致，加密存储迁移至 2。没有触发真实麦克风。

本机从 source 部署分支 10222def 完整构建并按既有授权 crabot restart；实际 Voice capability 5、Admin/Agent/MM 健康。后台 store2→3 的原文件备份与升级前摘要一致，迁移时0个正式联系人、FuFu单独保留在unconfirmed_contacts，原身份与已有好友绑定仍保留，不作为已识别样本。

独立 API36 无音频模拟器启动失败不再阻塞指定真机存储测试。

用户随后确认声纹录入与唤醒通过，但对话拒绝。实际手机首页已显示已连接/本机唤醒，后台存在一个新的正式FuFu联系人及active登记，目录revision6；旧待核验FuFu仍保留原好友绑定，新联系人尚未绑定好友。该轮持久拒绝原因为“语音群未满足现有群启用条件”，不是因unknown/uncertain发言拒绝，也不是ASR、目录同步或Agent生成失败。

通过已登录Admin的正常“绑定已有好友”将新登记联系人关联至已有FuFu好友；没有自动按名字合并身份，没有修改群权限、好友角色或旧登记。UI已显示“已绑定FuFu”，实际Channel get_session返回唯一语音群、1位成员并满足Master名册条件。原拒绝轮次保留，不重传或伪造新消息。等待用户发起新轮，再核验接纳、voice_reply、TTS/completed及实际speaker；上一轮手机显示“身份未确认”与群准入是不同问题。

当前原配对实际重连、手机首页、真实登记及本地唤醒已经得到设备状态/用户报告支持；成功对话、身份匹配准确率和三轮连续接话仍未验收，不以绑定成功替代真人播报。

连接手机后的验证顺序：

1. 保留数据覆盖安装，核对原配对凭据、有效声纹仍可读；两端一起切换 wire 5，首次/重连保持关麦。
2. 只运行 PairingUpgradeTest 和 VoiceCatalogStorageTest。这些用独立 cache 文件，不清产品数据或 Keystore；禁止在已配对手机执行完整 connectedAndroidTest。
3. 查看日常/设置/联系人/编辑/录入页面，核对系统栏、字号、输入、静音入口及真实空状态。后台任务下发不自动开始录音。
4. 用户现场录三段完成首次登记；核对后台正式联系人、改名/重录保稳定 ID、删除保好友绑定。测手机与已打开 Admin 页实际同步时间。
5. 用户一次开启本地唤醒，通过唤醒及接话完成三轮；核对真实回答、completed、8 秒接话和静音/退后台/断线关闭。误唤醒、远场、多位真人、功耗及 50 轮延迟分别待验收。

Android 产品 main 发布需完成相关设备回归及 diff review；本候选先保存在实施 worktree。后端继续现有 PR #206，不自行合并。
