# 整轮 ASR 与 XTurnix-ZH：固定集实测未通过

2026-10-27。下载问题已绕过，完整权重及独立依赖已校验，40 项主对照、20 项 ASR 连续处理计时、40 项 CPU 参考均已运行。**原单点误判被纠正，但新增另一条抢答，10 条完整句漏判 8 条；当前 SenseVoice＋此检查点组合不满足接入门槛，不进入产品。** 模型能运行不代表自然停顿已解决。

## 同批样本的判断

沿用[预登记](xturnix-evaluation-plan.md)：发布者标注完整/未完整各 10 条，ID、音频 SHA、标签不变。全部为合成语音，时长 4.64–13.52 秒，在原 15 秒容量内。ASR 原文未人工纠正，末尾标点只按官方管线移除。full/tail8 的 40 份文本为 98–124 token，均完整保留，未触及官方 2048 上限。

| 组件及输入 | 未完被当结束 / 10 条 | 完整句被当未完 / 10 条 |
|---|---:|---:|
| 原 Smart Turn v3.2 CPU，原边界 | 1 | 2 |
| 原 Smart Turn v3.2 CPU，追加 0.8 秒静音 | 1 | 0 |
| XTurnix-ZH，整段 ASR，MPS BF16 | 1 | 8 |
| XTurnix-ZH，末尾八秒 ASR，MPS BF16 | 1 | 8 |
| 同权重 CPU float32，官方 pipe 直接调用，整段 ASR | 1 | 8 |

Smart Turn 两行来自已有固定基准，不是新增等待策略或真实 VAD 对照。模型输入形式不同；表格只比较同批源音频的固定标签判断，不证明现场准确率或整链优劣。XTurnix full 正确 11/20。

- **原失败纠正**：行 452，ID `0384164c-d1e3-4881-861b-1bf5494cd90e`，10.84 秒。“清初文学家金圣探把杜甫所作之诗……”这条不完整转写，MPS 输出 `keep`，双候选归一化分数 0.831143，CPU 参考 0.830442；原 Smart Turn 结束概率 0.881712。
- **新抢答**：行 64，ID `00914f31-592d-4e54-9f42-fcabc7cd9399`，6.76 秒。转写“沿海的岛屿共1404个，总面积约为1200多平方公里嗯。”，发布者标注未完；MPS 输出 `start=0.718594`，CPU 参考 0.715918。该音频不足八秒，full/tail8 文本相同，错误不能归因于八秒裁切。
- **完整句漏判**：行 186/202/254/268/305/473/556/684 共 8 条输出 `keep`；例如“正如我刚才提到的，这里有860亿神经元。”也没触发结束，会导致说完仍等不到处理。

CPU/MPS 的 40 项动作全部一致；同模型 full/tail8 的 20 项动作也全部一致。原失败的 tail8 仍正确等待（MPS `keep=0.607663`），因此本次不能证明“保留前文就是纠正原因”。复核排除了 MPS 或手工计时入口特有的动作差异；ASR 错误、标签适配与模型本身的影响尚未分别归因，结论只针对当前组合。

原始分数、错误 ID、旧基准与摘要见 `xturnix-comparison.json`；逐条结果见 `xturnix-results-mps.json`、`xturnix-results-cpu-float32.json`。分数只在两个动作间归一化，不是已校准的现实判断置信度。

## 整体处理耗时

本机 Apple M2 Ultra / 192 GiB；ASR 为原 SenseVoice int8、CPU 单线程、中文、ITN，检测走 MPS BF16。先用固定三个无标签短句预热，再跑 40 项主对照；随后同批 full 20 条由自有 ASR 子进程处理已校验/预载的公开音频，立即交检测器，实际测墙钟，不用旧日志相加代替。

| 阶段 | 中位数 | P95 | 最大 |
|---|---:|---:|---:|
| 主对照 full 单次检测，3 条短句预热后 | 84 ms | 334 ms | 366 ms |
| 连续重放 ASR | 340 ms | 465 ms | 530 ms |
| 连续重放检测 | 60 ms | 74 ms | 106 ms |
| 连续重放整体：ASR＋检测＋本机 IPC/调度 | **406 ms** | **543 ms** | **589 ms** |

连续重放在主对照之后，模型及输入形状已经热运行，不等同首次请求。冷阶段另保留：模型加载约 331 ms，第一次预热推理约 2446 ms，后两次约 116/93 ms。ASR 模型加载也单独记录。不能把主对照与重放计时混成一批，或只用热缓存承诺冷启动速度。

计时不含说话本身、手机采音/VAD 等待、网络上传、业务 Agent 或 TTS，不是说完到听到回复的首声延迟。20 次连续重放的文本、动作均与主对照一致；处理成本不能替代准确性门槛。

## 下载问题及复现

上一轮单次传输 180 秒只有 20 MiB，本轮按用户要求继续。只读检查发现系统将 `huggingface.co` 解析为 `108.160.163.106`；公开解析返回另一组地址。原 HTTPS 请求报 TLS EOF，单次 CONNECT 指定三个公开解析地址分别得到 HTTP/2 302，仍使用原主机名/证书校验。这定位到本机解析/连接路径异常，未追溯是哪层写入了该解析结果。

本轮用一次性地址取得官方 HTTPS 跳转，从 `us.aws.cdn.hf.co` 做最多 16 个并行 8 MiB 范围请求。复用原 20 MiB，约 229 秒补齐 1,192,135,096 字节，完整 SHA 匹配预先固定的 `e1826bcccfbb5f07c7e63c105da3bcf9138e6dafd6b933061039ec59d67161df`。每块核对 Content-Range/长度，最后才将 `.part` 更名。正式传输全程 HTTPS，未跳过证书校验；诊断的一次公开 HTTP 元数据跳转未作为正式下载来源。

PyTorch 2.9.1 / Transformers 4.57.3 独立安装完成，MPS 可用；25 份实际依赖与脱敏传输证据为 `xturnix-transport.json`。未修改系统 DNS、全局代理、证书、已有服务 venv 或部署。临时签名 URL、模型、音频、特征及私有数据不入库。

[官方模型](https://huggingface.co/xcczach/xturnix-zh-base/tree/b69ac8a4d98e93332ca3452c569a77dd499287a9)固定 revision `b69ac8a4d98e93332ca3452c569a77dd499287a9`，Qwen3 0.6B、Apache-2.0；摘要树及许可为 `xturnix-files.json`、`XTURNIX-LICENSE`。使用经校验的[官方 turn_pipeline.py](https://huggingface.co/xcczach/xturnix-zh-base/blob/b69ac8a4d98e93332ca3452c569a77dd499287a9/turn_pipeline.py)：固定状态说明、非思考模板、末尾用户标点移除，倾听时只在 `<|start|>`/`<|keep|>` 间取较大 logits。未改官方说明、动作或门槛。

1. 在隔离目录准备原固定音频、`xturnix-files.json`、`xturnix-model/`，核对原 ID/标签/摘要。
2. `probe-whole-turn-asr.py --root <研究目录> --service-root <固定服务副本> --corpus <labeled-corpus.json>` 生成 40 条转写；本次主对照使用上一轮原文件，SHA 不变。新增 `--serve` 仅为本轮自有子进程提供 stdio，不开网络端口、不连接业务服务。
3. `probe-xturnix-format.py --root <研究目录>` 本次用实际 4.57.3 环境重跑，确认完整权重和 40 份输入。
4. `probe-xturnix.py --root <研究目录> --device mps --asr-python <原ASR环境python> --service-root <固定服务副本>` 完成主对照及连续墙钟，写 `xturnix-results-mps.json`。
5. 同脚本用 `--device cpu --cpu-reference`，不带连续计时参数，无损上转 float32、单线程、官方 pipe 直接调用，写 `xturnix-results-cpu-float32.json`。参考计算与主结果分开，不替换不利结果。

## 当前决定

A2 仍未通过。当前组合不支持把已确认纯音频检测改为整轮 ASR＋XTurnix-ZH；不因下载解决或纠正一个样本就改 spec/协议/产品。保留固定失败集与完整句漏判供后续候选对照，不调门槛、改标签/文本、换样本或延长静音宣布通过。

本机实例、APK、提示词和协议未动，未开麦/播音、重启 ADB、调用付费服务或要求真人重测。后续提出其他架构时，仍先解释取舍并确认最小 spec 修订。
