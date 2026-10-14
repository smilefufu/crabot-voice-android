# 短句识别 0.5.2 验证

依据：用户于2026-10-14确认[修订稿](../specs/2026-10-14-short-utterance-speaker-identification-design.md)，共同协议v0.5.0/wire5未改变。

APK0.5.2/code13已保留数据安装到OnePlus 7 Pro/Android10。日常识别不再先比较1.5/1/0.3秒；原生embedding沿已有IsReady决定能否计算。新增当前模型维度及特征/匹配结果有效性校验，分数0.50与候选差值0.08保持。登记仍是三段3–5秒和至少24000有效人声samples。手机显示本轮不能确认的原因，设置诊断及本地日志记录必要数值；原因不进入VoiceSegment或后台字段，未保存用户音频/向量。

## 先复现与修复

三个独立公开文件回归在已安装0.5.1上均失败：正式登记A、未登记B、未登记C的有效短句都变为uncertain，没有比对分数。登记与测试来自不同官方文件，SHA256与[来源清单](../research/2026-10-14-short-speaker-evidence/sources.json)一致。

首次紧贴语音尾裁剪的公开输入产生不合法分段，已排除这次失败作为门槛证据；没有修改产品区间校验。测试轮次调整为1.18秒公开语音裁剪加0.8秒结束静音，与有限轮次的停顿结束一致。测试明确确认分段时间轴合法且有效人声小于24000 samples；静音不计入身份匹配证据，没有借它跨过旧门槛。

修复后指定24项真机回归通过：IdentityReportTest13（模型结果替身）、NativeShortIdentityTest3（实际JNI/冻结权重、独立公开文件）、VoiceWaitTimeoutTest3、PairingUpgradeTest2、VoiceCatalogStorageTest3。真实文件中登记A匹配为A，两位未知者都unknown，不因只有A一个档案强配。替身覆盖未就绪、错误维度/零范数/非有限值、畸形/重复/无序匹配结果、单候选低分、相近候选、A→B→A时间轴/同簇汇总、下一轮不继承、SDK异常及重叠整轮失败。

首轮24项通过后用code-simplifier整理本次匹配分支，改为独立早退出识别函数，保持结果及异常语义；重新编译及重复同24项真机回归全部通过。14项JVM两次通过，产品/仪器APK均构建成功。新增窄模型接口只是明确原调用与测试边界，不新增模型选择或运行配置。

测试前、首轮后及最终回归后真实terminal.enc逐字节一致；只安装-r，没有卸载、清数据、换Keystore或重录FuFu。上述测试未开真实麦克风、扬声器或连接真实业务服务；只运行审查过的特定类，没有运行完整connectedAndroidTest。

公开fixture只保存在忽略的.model-cache目录，经androidTest source set打包进测试APK；产品APK经检查不含公开登记/查询录音。准备脚本[prepare-native-test-fixtures.py](../research/2026-10-14-short-speaker-evidence/prepare-native-test-fixtures.py)只接受清单中摘要一致的官方文件，不处理用户录音。

最终App已打开，实际包版本0.5.2/code13，首页显示已连接/麦克风关闭，原配对无需重新扫码。模型、好友绑定及后台权限配置未修改。

## 真人边界

2026-10-14 21:10（Asia/Shanghai），用户自行开启语音待机、唤醒后以正常语速问“你今天过得怎么样？”，随后确认“有回答了”。本轮3d4f4ae7的本地诊断为19980有效人声samples（1.24875秒）、1位候选、compared=true、score=0.5552635、margin=1.5552635、reason=null。实际进行了比对并达到原0.50分数条件，未靠只有一个档案或上一轮身份强配；单候选margin沿原算法以缺失第二候选的-1计算，不表示身份概率或真实两人差距。

后台持久轮次为accepted，ASR文本保持原问题，segment0为matched、发送人FuFu并解析到原好友；群权限仍按唯一群处理。对应播放5453b8b5为completed、played_samples=319800，发送记录时间为2026-10-14T13:10:32.337Z，用户确认实际听到回答。有效人声低于旧24000样本门槛，已完成本次短句修复的相关真人复核；按既有授权经diff review后直接发布Android main。

本轮只证明这次FuFu自然短句识别、好友归属和播报成功。短句未知者误识率、多位真人A/B/A、三轮接话、唤醒误触发、50轮整体延迟、AEC与功耗仍待现场验收，不宣称极短词一定能认人。
