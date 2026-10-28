# 八秒上下文核实与替代方案评估

2026-10-27。仅隔离研究，不修改已确认 spec、正式协议、产品、阈值或超时，不安排真人测试。承接[固定集首次结果](README.md)。

## 结论

1. **八秒是模型的真实输入约束，不是 Crabot 的等待期限。** 三个官方 ONNX 变体均声明输入 `[-1, 80, 800]`；不截断原 10.84 秒样本得到 1,084 帧后，实际 ORT 调用均明确拒绝，要求 800 帧。[上游说明](https://github.com/pipecat-ai/smart-turn#notes-on-input-format)也只支持最近八秒。因此不能只改客户端缓存或 max_length 就让现有模型看到完整语音。
2. **不能将这条误判唯一归因于丢掉前 2.84 秒。** 该前文包含“把……”结构，删掉它确实可能损失未完成句的线索；但恢复句首的诊断拼接、保音高加速后的完整录音仍被判结束。它们改变了中间内容或时间特征，不能作为原音频的严格因果反事实，也不能排除合成录音语调、标注歧义与模型判断能力的影响。
3. **当前 Smart Turn 仍未过门槛，不能作为已经验证的唯一结束判据接入产品。** 不把重新选样本、加速语音、提高阈值或增加静音等待作为修复。原先固定集的一条失败仍保留，未用本批派生样本改写验收结果。

## 实际做了哪些对照

使用原文件 SHA、同一官方特征流程及原 0.5 判据；模型摘要均先核验。源音频、模型及个人声音均不入库。`context-boundary.json` 保存三模型共 72 个诊断结果、输入元数据和三次原生长输入拒绝；`probe-context-boundary.py` 可重放。增强后的 `probe-ort.cc` 保留默认 800 帧模式，只增加读取维度及研究用显式帧数；原固定集 120 个概率逐项不变，非法研究参数在分配/推理前失败。

| v3.2 CPU 对照 | 结束概率 | 能说明什么 |
|---|---:|---|
| 原音频末八秒 | 0.881712 | 原失败如实复现。 |
| 原音频前八秒 | 0.00722188 | 当时所有前文可见时判未结束；但判定时刻和尾音也变了，不能据此证明删头是唯一原因。 |
| 前四秒＋末四秒拼接 | 0.917027 | 保留句首“把……”仍判结束；中间删掉，属于辅助诊断。 |
| 整份录音用 atempo=1.4 保音高加速至约 7.75 秒，再左补零 | 0.974008 | 无按八秒删除句首仍判结束；语速/节奏已变，不能用作生产修法或严格反证。 |
| 完整 10.84 秒、不裁切直接输入 | 无概率，明确报错 | 模型导出维度本身拒绝，无法在原权重/原录音下取得完整上下文的直接对照。 |

另预先固定 4–8 秒间每隔 0.25 秒的全部 17 个前缀作诊断扫描：v3.2 CPU 均判未结束，v3.1 CPU 有 5 个、v3.2 非量化有 4 个判结束。它们没有独立的发布者端点标签，不能统计成新的准确率；原 CPU 模型在该范围没有新的提前结束证据，不能歪写成“所有模型八秒以内也都失败”。

`context-boundary-asr.json` 是原本地 ASR 对公开前后文的辅助转写，不是可信原文或重新标注。被删的句首包含“清初文学家……把杜……”，留下的尾部从“……所作之诗……”进入枚举。ASR 自己补的句号不能作为人已经说完的证据。

## 替代组件核查

本节是候选资格与工程边界比较，不是本机准确率/延迟对比。没有安装候选、下载大型权重、调用付费 API 或声称已选定新组件。

| 候选 | 已核实的输入与边界 | 本次判断 |
|---|---|---|
| LiveKit 音频 v1-mini | 官方 `livekit-local-inference` 0.2.7 的原生类型文档规定最多 19,200 个 16kHz 样本，即 1.2 秒；文档写明更长输入抛 ValueError，未作原生运行验证。Agents 主线也使用 1.2 秒固定滚动缓冲。 | 存在独立原生 EOT 接口，但**不能当作扩大上下文的替换**；这不代表其准确率一定较差。[后续核查](chinese-options-20261028.md)发现独立使用许可说明冲突和 SDK 失败回退边界，未澄清前不认定可独立集成。未运行准确率对照。 |
| LiveKit 旧版多语言文本模型 | 需要 ASR 文本，Qwen2.5-0.5B；官方描述限制为 128 tokens/最多六轮，当前 SDK 已将旧插件标记 deprecated。 | 不把八秒问题换成另一个会裁掉句首的默认文本窗口，不优先新增此依赖。 |
| XTurnix-ZH Base 0.6B | 中文文本话轮模型，输入转写历史；后续已确认权重 Apache-2.0、官方管线 2048 token 及固定检查点，完成 CPU/MPS 实测。 | [同批实测未通过](xturnix-findings.md)：原误判纠正，但新增一条抢答、完整句漏判 8/10，不能采用当前组合。只测试 listening，不扩展打断/附和含义；仍与现有纯音频接口不同。 |
| Easy Turn | 声学与语言联合模型，公开报告 850M 参数、4090 上约 263 ms/约 2.56GB GPU 占用；这些不是本机或手机数据。 | 作为更重的服务端对照候选；不优先上手机，也不承诺本机满足时延。后续已核实模型卡及工程标 Apache-2.0；具体版本、部署与性能仍未实测，见[后续比较](chinese-options-20261028.md)。 |
| Deepgram Flux Multilingual | 官方 Flux 语言表列十种语言，不含中文；Nova 支持中文不等于 Flux 支持中文端点。 | 本次中文场景不选，未调用服务。 |
| Krisp VIVA TTv3 | 商业 SDK 的音频/语调模型，官方定位服务端，需门户取得模型；说明扫描最近几秒，完整语义上下文长度没有核实。 | 不能仅凭“多语言/CPU”声称解决整句语义，暂不作为默认开源替代。没有注册、接受许可或调用试用 API。 |

LiveKit 原生接口通过官方 wheel 的 HTTP Range 读取核查，共约 9KB，只取目录、接口及许可元数据；未下载约 35MB 的原生运行库。文件/来源摘要见 `alternative-sources.json`。不能将公开 SDK 示例直接复用为 Crabot 的会话/超时/自动提交策略。

## 推荐与 spec 影响

原推荐的**完整当前轮次转写＋专用中文语义结束判断**已用 XTurnix-ZH 完成首轮固定集对照，当前组合不通过，不能凭纠正一条误判选定。后续候选仍保留相同失败集与完整句漏判作为底线；端侧音频线索可作为比较组成部分，但本次未确定如何组合。准确率、上下文、许可、冷启动及整体成本须实测，不能凭模型方榜单选定。

若要采用，该架构与已确认的“端侧纯音频判断、无需 ASR”不同，**须先修订并确认 spec，再改正式协议/产品**。最小需要说明三点：

- 端点语义判断允许经独立 API 在服务端完成；模型仍由外部服务部署，Crabot 不下载、启动或管理权重，不把整个产品迁入某个语音框架。
- 只在已唤醒的当前输入期间，允许准入前转写/结束判定；中间音频和文本不进入 Manager/Worker、不生成业务请求、不授予联系人或权限。身份、分段及整组接纳仍走最终原流程，不能把检测用的整段转写冒充每位说话人的正式消息。
- 明确中途恢复说话、迟到判定、断连及检测故障的收口；未确认结束不得靠超时强制提交。原 15 秒容量、待机/静音/后台边界、AEC 与本地唤醒保持，不以此扩大输入/监听范围。

当前记录资格调查及[后续不通过结果](xturnix-findings.md)，**未修改 spec 或协议来认可这个候选**。暂无可联合升级的替代品，也无需真人配合。

## 主要来源

- [Smart Turn 输入约定](https://github.com/pipecat-ai/smart-turn#notes-on-input-format)，实测模型 SHA 继承原记录。
- [LiveKit 本地推理固定包 0.2.7](https://pypi.org/project/livekit-local-inference/0.2.7/)，实际 `_native.pyi` 与 Agents [transports.py](https://github.com/livekit/agents/blob/main/livekit-agents/livekit/agents/inference/eot/transports.py)、[AudioArrayBuffer](https://github.com/livekit/agents/blob/main/livekit-agents/livekit/agents/utils/audio.py) 逐项核查；不能把 1.2 秒误解为可增长初始容量。
- [LiveKit 文本模型卡](https://huggingface.co/livekit/turn-detector)及[当前 SDK 弃用说明](https://github.com/livekit/agents/blob/main/livekit-plugins/livekit-plugins-turn-detector/README.md)。
- [XTurnix 官方代码](https://github.com/xcc-zach/xturnix)、[论文](https://arxiv.org/abs/2610.04400)。README blob 为 `0332698c54b09e6772dc014767274125e65129c1`。最初模型卡/config 读取失败，后续已在固定权重 revision 核实官方管线 2048 上限、许可及实际推理；来源与结果见[完整实测](xturnix-findings.md)，不把服务示例当作权重契约。
- [Easy Turn 官方代码与测量条件](https://github.com/ASLP-lab/Easy-Turn)，README blob `a7251c0d7bdcd96572006b74f5bae68ff1dad091`。
- [Deepgram 模型语言表](https://developers.deepgram.com/docs/models-languages-overview/)，只依 Flux 行判断；[Krisp TTv3 文档](https://sdk-docs.krisp.ai/docs/turn-taking)。
