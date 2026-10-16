# 登记质量层验证

2026-10-16；依据已确认设备所有者与语音引导登记 spec，实施计划 A1。尚未完成所有者首次激活、请求级授权或全语音管理，不发布 wire6 或升级真实手机。

新增共用 Identity.enrollmentSample / enrollmentCandidate，当前已配对登记调用同一质量层：逐段时长/单人/分段/特征检查，三对现成模型 matches 全部达到现有 0.50 后再合成。模型维度、有限值、范数及 matches 结构无效均拒绝；失败不保存候选，不替换旧 active。日常短句 report、匹配阈值与有效样本下限保持原规则。

先运行新增回归，未实现方法导致编译失败；实现后 `:app:testDebugUnitTest` 18 项全部通过（新增 4 项含多组非法输入）。`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 成功。最终 diff 检查未发现需新增抽象的重复结构，逐行 review 保持当前改动范围。

在只读隔离模拟器 emulator-5584（已有 AVD，未连接真实手机）运行 NativeEnrollmentIdentityTest、NativeShortIdentityTest、IdentityReportTest，18 项通过。公开 fangjun 三句不同音频的真实 JNI embedding 能通过三对一致性；第三段替换为另一位公开说话人被拒绝。原短句真实比对、单档案未知者及身份分段回归通过。原音频仅存在于测试资源，不进入产品包。

该证据不证明三段现场自动采音、幼儿/家庭误认率、真人 Thea、端到端激活或流畅性已验收；公开登记文件时长与现场录制流程分别验证，未把短文件当作 3–5 秒现场登记通过。没有调用云服务、麦克风、好友接口或真实 SecureStore；未安装用户手机、重启后端或迁移真实数据。当前产品版本与 wire5 未升级，后续按整组完成后统一发布。
