# 手机语音界面及声纹管理实施计划

依据：已确认 2026-10-14 两端 spec，正式 [protocol-voice v0.5.0](https://github.com/smilefufu/crabot-docs/blob/main/protocols/protocol-voice.md)。实施“回答为主”布局。

1. wire 5、配对 3/4 读取和加密 storage_version 2 → JVM 版本/帧回归，隔离存储迁移验证原密文/credential/向量保留。
2. 目录原子缓存、候选向量及待确认请求 → 模拟首次/重录/撤销/重复/丢回执/重连，最新目录确认前业务不可开麦；同 task 截止时间不延长。
3. VoiceController 主动管理和已有登记链路 → 现场点按才采样，三段完成仅保存候选与回执，后台确认后才认人；更新目录和重连不自动开麦。
4. MainActivity 日常/设置/声纹列表/录入页面 → 原真实状态投影、48dp/小屏/系统栏/长文案；静音始终可达，无伪造转写或好友。
5. 定向测试、构建、简化检查及 diff review → main 提交；保留数据覆盖安装，真机 UI/同步及用户唤醒三轮分别验收，记录 APK/协议/后端版本。

复用语义：toggleWake/recordButton/mute 分别负责现场本地唤醒、有限采样或按钮打断、关闭语音；开页面/重连不调用开麦入口。原后台 enroll 只准备现场任务，主动 terminal 管理单独鉴权并按 request 结果执行；putVector 不代表后台联系人成功。完整 connectedAndroidTest 会清产品数据，因此禁止在已配对手机跑全套，只执行确认无清数据行为的特定类。
