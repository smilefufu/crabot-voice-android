# 中文自然停顿：候选资格与下一步取舍

2026-10-28。只读调研，不是候选实测或产品选型通过记录。承接[Smart Turn 与八秒核实](context-and-alternatives.md)、[XTurnix 固定集失败](xturnix-findings.md)。本轮未下载新权重、运行新候选、调用付费 API、开麦/播音、操作 ADB、修改部署、提示词、已确认 spec 或正式协议。

后续进展：[TurnSense实测已完成，未通过](turnsense-findings.md)。下文保留预测前的候选资格与取舍，不将当时的研究优先级冒充实测通过或产品选定。

## 结论与验证顺序

成熟的工程做法是区分“暂时没声音”和“人已经让出话轮”，并把误抢话、漏接话与等待成本一起评估；不是把普通 ASR 的句子结束事件直接当成整轮结束。当前没有经过 Crabot 验证、可以直接替换上线的中文组件。现有两个失败候选也不能证明八秒截断是唯一根因，或证明纯音频路线必然不可行。

1. **下一项隔离验证优先 TurnSense 音频模型。** 它有中文评测、ONNX 和 CPU 推理入口，对现有“端侧音频判停”的架构影响最小。但官方示例同样只取末尾八秒，绝不宣称它靠更长上下文解决原误判。先验证中文判断能力，再谈产品替换。
2. **Easy Turn 留作声学与语言联合判断的质量对照。** 它能够同时利用说话方式和语义，但现成工程更重，公开时延来自 4090；不能承诺本机或手机速度。若轻量音频候选仍失败，先核实这条路线的独立部署和完整计时，再决定是否值得改变架构。
3. **云接口是参照路线，不立即接入。** 已有成熟接口能够提供语义判停，不需要因此替换 Crabot Agent；但接口自带等待上限、定稿行为、费用和网络依赖。不能靠把 eagerness 调低、延长最大等待满足验收，也不能假定所有 `<end>` 都与现有提交语义一致。

以上是有限候选的研究优先级，不是批准安装或部署任何新产品组件。暂不堆叠多个检测器、训练新模型、增加通用模型管理或迁入整套语音 Agent 框架。

## 候选与工程代价

| 候选 | 本轮核实 | 取舍与剩余证据 |
|---|---|---|
| **TurnSense（百融，47M）** | [模型卡](https://huggingface.co/brgroup/TurnSense)发布中文/英文及 INT8/FP32 ONNX；[实际代码](https://github.com/Bairong-Xdynamics/TurnSense/blob/c6d83e5b0d1355125b05eaf1b4b5c6c1acabb338/infer.py)输入音频特征，不是 ASR 文本。16kHz、Fbank/LFR/CMVN，`feats` / `feat_lengths`，三类 argmax；示例默认裁末八秒。GitHub LICENSE 是 Apache-2.0。 | 首选隔离对照。模型卡的文字示例不能代替实际输入契约；其中文分数属于作者报告，且中文表主要对应初版，不能套成 1.1 本机成绩。权重具体版本、SHA、特征一致性、ONNX 实际输入容量及 ARM 性能须另验；示例八秒不是已经实证的图维度硬上限。`invalid` 不能被拿来悄悄屏蔽“好/嗯/停”或修改现有打断含义。 |
| **Easy Turn（ASLP，约850M）** | [论文](https://arxiv.org/html/2509.23938v1)与[工程](https://github.com/ASLP-lab/Easy-Turn)联合声学和语言信息；[权重卡](https://huggingface.co/ASLP-lab/Easy-Turn)标 Apache-2.0。Whisper-Medium 编码器与 Qwen2.5-0.5B；官方任务先生成转写，再输出 complete/incomplete/backchannel/wait。 | 质量备选，尚不是可直接放手机的成熟 ONNX 包。作者报告 4090 平均263ms、GPU占用2559MB，不是本机P95或长句整链时延；反复对增长音频生成转写的成本须实测。不能拿训练过滤的2900帧当成已证实的推理硬上限；不能把 backchannel/wait 自动映射为忽略用户、取消Worker或静音。 |
| **FireRedChat turn detector** | [独立权重卡](https://huggingface.co/FireRedTeam/FireRedChat-turn-detector)提供中文/中英 q8 ONNX、CPU 示例、Apache-2.0；[插件代码](https://github.com/fireredchat-submodules/livekit-plugins-fireredchat-turn-detector)实际读最后一条用户文本，左裁到128 tokens，清理部分标点。 | 可作为轻量文本基线，但缺少语调信息且依赖 ASR。先核对当前轮次是否超长；不默认截句首。插件异常吞掉后返回“不完整”的行为不能复制，否则可能无声卡住。全框架 pVAD 过滤非主说话人的策略也不符合 Crabot 群聊允许陌生人发言的边界。 |
| **TEN Turn Detection（7B）** | [官方模型](https://huggingface.co/TEN-framework/TEN_Turn_Detection)和[代码](https://github.com/TEN-framework/ten-turn-detection)是中文/英文文本分类，finished/unfinished/wait，示例 CUDA/BF16，Apache-2.0。 | 可借鉴语义划分，但不是低成本优先候选；没有本机证据证明比小模型更值得额外资源。wait 只是检测标签，不获得业务取消权限。 |
| **LiveKit audio v1 / v1-mini** | [当前文档](https://docs.livekit.io/agents/logic/turns/turn-detector/)支持中文音频路线，mini 是本地 CPU。已核实 0.2.7 原生接口只收最多1.2秒音频。 | 不能仅因窗口短就断言准确率差。独立使用的许可目前有矛盾：文档称 mini 可用于任何场景，同页资源又指向限制只能配合 LiveKit Agents 的[Model License](https://raw.githubusercontent.com/livekit/agents/main/MODEL_LICENSE)，PyPI 也列复合许可；未澄清前不选为独立 Crabot 组件。SDK 检测超时/出错仍可能完成当前轮的回退也不能照搬。无需为此整体迁入 AgentSession。 |

TurnSense 的公开声明能够说明“值得复现”，不能证明它已经适合家庭语音，也没有核实大规模线上运行证据。Easy Turn 当前主要是研究工程和实验室演示；成熟架构与候选模型的工程成熟度分别评价，不把论文结果写成上线保证。

## 云端：真正的话轮信号与句子结束分开看

- **OpenAI Semantic VAD**：[当前指南](https://developers.openai.com/api/docs/guides/realtime-vad)说明使用语义分类判断继续等待，在支持 VAD 的转写会话中也可配置；对话会话可禁止自动生成回答而仍收 VAD 事件，因此技术上不必让它替换 Crabot Agent。具体模型/会话资格仍须固定，旧 transcription_sessions 入口与 GA session 字段不能混用。[API](https://developers.openai.com/api/reference/resources/realtime/client-events)写明 eagerness low/medium/high 最大等待分别8/4/2秒；它不是“完全没有等待上限”的组件，也没有本项目中文质量和线路时延证据。仅调成low不作为修法。
- **Soniox Semantic Endpointing**：[官方语义说明](https://soniox.com/docs/stt/rt/endpoint-detection)结合停顿、语调与上下文，WebSocket 返回 `<end>`；[语言表](https://soniox.com/docs/stt/concepts/supported-languages)含中文。它是可接入自有 Agent 的 ASR API，不要求 RTC 平台。但文档默认最大 endpoint delay 为2000ms、范围500–3000ms，未核实是否可关闭或能严格区分业务所需的“仍未说完”；提前定稿还会降低说话人分段准确性。因此不直接把它替换现有多人 ASR，也不宣称其默认事件符合本项目的提交边界。
- **腾讯 TRTC**：[语义断句](https://cloud.tencent.com/document/product/647/117799)确有声学/语义联合模式，`TurnDetectionMode=3`；当前公开入口属于 StartAIConversation 整体 RTC 对话流程，尚未核实独立判停 API。火山 RTC/Coze 同类语义判停也有平台接入边界，不能只为端点引入另一个业务 Agent。
- **阿里实时 ASR 的智能断句**：[接口](https://help.aliyun.com/zh/isi/user-guide/api-reference)有 `enable_semantic_sentence_detection`，但 SentenceEnd 与一整轮人类意图结束不同；不能收到转写句子结束就让 Manager 接单。普通 server VAD 依赖静音长度，也不等同语义判停。

上述仅文档核查，未调用服务、申请试用、读取产品密钥或发生评测费用。若后续确实需要云端对照，先固定提供方、具体模型、可访问性、预计费用和停止条件，不使用本机业务账号无上限试错。

## 评测方式：检验真实停顿，不改旧失败集

本轮找到 [LiveKit eot-bench](https://github.com/livekit/eot-bench)：公开真实人机对话，含中文，按每轮的中间静音与最后静音标注。它比“人工给一句合成音频补几秒静音”更直接对应当前问题。[数据集页面](https://huggingface.co/datasets/livekit/eot-bench-data)当前标 CC-BY-4.0，评测代码 Apache-2.0；代码 README 将数据也写作 Apache，存在不一致，下载/归属以固定数据 revision 的实际许可再次核实，不能把模型许可、代码许可和数据许可混为一谈。

后续验证分两关，均在隔离目录，候选未通过不进入部署：

1. **固定回归关**：原20条音频/标签/hash完全保留，同配置同时报告原误判、新抢答、完整句漏判和失败原因。先核对官方输入/特征/版本，默认决策不调参；不更改文字、追加等待或重新标注来通过。原固定门槛仍有效，不拿大集平均分覆盖其中失败。
2. **真实中文逐停顿重放关**：在预测前固定中文 revision、全部符合现有容量的样本 ID、原标注及选择规则；超出容量的样本单列，不截尾后冒充整轮通过。按时间重放，只使用当时已有音频/文本，每个中间停顿都检查是否提前结束，最后停顿检查漏接与延迟。原文/未来 words 不能替代在线 ASR 输入获得虚高成绩；需要额外历史上下文的路线分开列，不默默扩大产品输入。

复用的是公开数据 schema、因果回放与逐停顿评价方法，不把其默认策略当成 Crabot 的行为：[现有 benchmark](https://github.com/livekit/eot-bench/blob/b1cc6abceca916d994c4d044aa8285cba7fab9b2/eot_harness/metrics.py)默认扫描 threshold/action_delay/timeout，并可能按超时强制结束。这里固定官方原始判据，不扫描调优、不采用其超时兜底、不把5%抢答预算替换原验收门槛。若现成 runner 的语义不符，只复用无场景含义的读取/对齐部分并明确差异，避免“用了同一库”却改变提交含义。

每次报告至少列：中间停顿抢答数/分母、完整结束漏判数/分母、错误/容量拒绝数、最长等待、P50/P95/max及单条失败。时延包含候选所需特征、转写（如有）、推理与调度；冷启动/预热另列。模型算一次几十毫秒不等于用户结束到 Crabot 首声几十毫秒。公开重放不能替代后续 AEC、多人、手机耗电和真实连续对话验收。

**停止条件**：先跑一个轻量候选。原固定关失败即记录并停止其产品接入，不在同一失败集上选阈值；要转向更重路线先报告证据和代价，不无限扩展模型名单或让用户反复测试。下载依赖不能满足明确版本/许可/摘要，也先报告，不绕过证书校验或更换本机全局网络配置。

## 架构与 spec 边界

建议保持收音/判停与业务 Agent 分离：本地唤醒后才进入当前输入；VAD 发现停顿只触发结束判断；未结束继续接收，同轮恢复说话使旧判定失效；确认结束后才按现有声纹→联系人、分段 ASR、权限规则形成业务消息。播报期间的人声候选先按已确认设计让出声音，不能等判完整句才停止播报；业务任务不随播放取消而取消。这些是既有边界，不是本轮已经实现的功能。

- **只做候选离线研究**：不需改 spec 或正式协议，本轮到此为止。
- **采用 TurnSense 产品替换**：如实测通过，须给出模型/版本、特征流程、容量/invalid语义、手机完整时延与资源的最小书面修订，确认后替换已写明的 Smart Turn 首选；不能把“同为 ONNX”当作产品集成已获确认。
- **改用服务端联合/文本/云判停**：超出现有端侧纯音频设计，先确认位置、准入前音频/转写、配置归属和迟到/故障处理，再改正式协议和产品。Crabot 仍仅接外部 API，不负责下载、运行或管理服务端模型；中间内容不交 Manager/Worker，不生成半句话任务、不改变身份/群权限/投递目标。
- 未确认结束不能通过超时把半句交给 Agent；原容量、静音/后台/断连、本地唤醒、待机意图、播报与任务分离边界不在本轮放宽。检测故障要明确显示并安全收口，不能无限无声等待。

任何模型都无法保证从一段已说完的话中读出人接下来是否还在思考。目标是在固定中文停顿下减少可复现错误并保持响应速度，不能承诺任意停顿永不抢话，也不能因概率判断存在就把当前抢话视为正常。若两条可行路线仍达不到原门槛，应报告需要人类取舍的事实，而不是偷偷改变体验标准。

## 本轮核对的固定源码

仅核对公开源码/元数据，未复制第三方实现进产品或运行新模型：

- TurnSense GitHub commit `c6d83e5b0d1355125b05eaf1b4b5c6c1acabb338`；infer.py blob `ee39d33f59bae90e45605976d4154cc78a8459da`，frontend/audio_frontend.py blob `c53c033235cf5ee2fadab24ef9a485b4aac2c8b8`，LICENSE blob `261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64`。权重未下载，不能声称上述源码 commit 固定了某个 ONNX 摘要。
- Easy Turn commit `fe175eaa8442c9a0834acf29f8640ee74c2aceca`；prompt.yaml blob `71f5328e71948e4b91707f150eac3c0f32043a99`，train.yaml blob `f4ab3fe4578a75ba823cc697ba15fb1a58bbfeaf`。
- FireRed plugin commit `c978f6172571a82875471eddc317b8cb3a870efa`；base.py blob `63812bc82ef4ea4eb1ea239b03a0aafb23d23f2a`。
- eot-bench commit `b1cc6abceca916d994c4d044aa8285cba7fab9b2`；soniox_adapter.py blob `b6c7dbfee1f723a2282391e459431d584f984534`，metrics.py blob `03507eb8787def3ace7d64688ef9eb3f230491ff`。本轮未下载数据音频，未把其默认 timeout sweep 带入产品。
