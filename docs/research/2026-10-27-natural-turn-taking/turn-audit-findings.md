# 判停失败的归因复核

2026-10-28。本轮只诊断已有测试，不更换候选、不修改产品或已确认设计。**目前证据是可重复的模型组合误判，以及需要修正的评测解释；不是业务 LLM 随机输出，也尚未证明自然轮次架构有错。** 保留原20条、256轮、原标签、预测和所有失败，不通过重选样本、调阈值或延长等待改变结论。

## 1. 同一输入是否波动

固定原输入，每项连续调用三次，CPU单线程；原结果文件只读：

| 对象 | 输入与调用数 | 动作变化 | 最大分数变化 | 与原判断一致 |
|---|---|---:|---:|---:|
| Smart Turn v3.2 CPU | 原20条音频，60次 | 0 | 0 | 20/20 |
| TurnSense 1.0 INT8 | 同上，60次 | 0 | 0 | 20/20 |
| TurnSense 1.0 FP32 | 同上，60次 | 0 | 0 | 20/20 |
| XTurnix-ZH CPU float32 | 原20份full ASR文本，60次 | 0 | 0 | 20/20 |

XTurnix调用原 `TurnTakingPipeline(text, state='listening')`，比较两个动作的logits，不是采样生成；未重跑ASR。前三项是音频分类器，不调用Crabot业务LLM。这只说明本环境固定输入下的稳定性，不能外推成所有输入/硬件/云模型均稳定，也未分离XTurnix组合中的ASR错误与模型适配因素。

证据：[音频180次](turn-audit-audio-repeatability.json)、[XTurnix60次](turn-audit-xturnix-repeatability.json)。原固定集的错判仍然存在，不能用“这次随机答错了”解释，也不能因此宣布产品架构失败。

## 2. 旧回放的差异实际影响多大

[官方Pipecat说明](https://docs.pipecat.ai/api-reference/server/utilities/turn-detection/smart-turn-overview)与0.0.102发行包中 `TurnAnalyzerUserTurnStopStrategy` 一致：VAD停说事件触发一次模型判断；默认VAD静音参数本身就是0.2秒。旧回放使用原静音标签定位，每0.2秒重判，是另一种固定策略，不能称为官方完整对话流程。

但这个差异不是本批错误的主要解释：

| 原175个可评分中间停顿 | 第一次0.2秒已判结束 | 反复观察任一次判结束 |
|---|---:|---:|
| Smart Turn | 100 | 107 |
| TurnSense INT8 | 85 | 86 |

因此，不能把86处提前判断都归咎于重判；也不能靠改成单次观察就宣称已解决。[LiveKit上游指标](https://github.com/livekit/eot-bench/blob/b1cc6abceca916d994c4d044aa8285cba7fab9b2/eot_harness/metrics.py)亦明确区分固定score point与运行策略，其标称分数不等于我们这套“任一次判结束”的统计。当前复核没有重现供应商公布的F1，不能据差异断言模型发布结果错误。

旧“74条录音末尾未判结束”中41条末尾窗口不足1.5秒，另33条窗口充足；充足106条中另有1条超过1.5秒才结束，共34条未按时结束。原记录已列两种口径，但对外解释不能把74全部称为充分观察后的漏判。成功样本延迟不能代表这些失败的延迟。

## 3. 真实VAD触发后的隔离回放

沿用原256条，不增加音频或尾部静音。运行原Pipecat 0.0.102 `SileroVADAnalyzer`、发行包自带模型及默认参数：confidence=0.7、start/stop=0.2秒、min_volume=0.6。代码和模型逐字节核对原wheel；32ms PCM帧，按音频时间执行原五秒状态重置，没有拿标签生成触发事件。第一处VAD人声开始时按原500ms预留与start延迟选输入起点，再用各模型原八秒预处理。共530个实际VAD停说事件。

源码审查发现上游VAD会捕获异常后返回0，故额外只重放VAD，捕获ERROR日志并核对全部事件时间：256轮/530个停说事件与首次完全一致，错误0。没有重跑判停候选或扩大样本；[核查证据](turn-audit-vad-verification.json)单独保留。此项补的是诊断可见性，不是通过增加模型重试来选更好的结果。

**范围限制：这是原VAD加声学模型的触发对照，不是完整Pipecat、Android Controller或Crabot整链验收。** 没有复制上游三秒静音强制结束，也没有接转写交付门控；这两项不能暗中成为产品策略。首次提前提交之后的输出都是影子观测，不能用后面判对来补记整轮成功。

每轮只看第一次complete，并仅在原数据明确标注的静音区间内评分：

| 原256轮的首次complete归属 | Smart Turn | TurnSense INT8 |
|---|---:|---:|
| 落在标注的中间停顿 | 64 | 70 |
| 落在标注的最终停顿 | 125 | 99 |
| 落在所有标注静音区间之外，无法按原标签评分 | 40 | 42 |
| 原文件EOF前没有complete | 27 | 45 |

四类各自合计256，不能把40/42条未知算成成功或抢答，不能与旧表的“175个停顿”混用分母。真实VAD触发没有使本批失败消失，但这些数字仍不是线上抢答率。13轮没有在原最终静音区间内产生VAD停说事件；观察窗口至少1.5秒的106轮中，这类有4轮。另外，在最终静音区间首个VAD事件上，Smart Turn有16轮、TurnSense有22轮判未结束；这与“没有触发VAD”是不同原因，且可能与前面曾提前判结束重叠，不能相加当失败总数。

审计第一遍曾将“所有早于最终静音起点的complete”机械记为抢答，错误地覆盖了未标注区间。已补回归检查，用**已保存的同一份分数**重新归类，没有再跑模型、改标签或筛除事件。`scoring_correction`保留修正前计数、原结果SHA和理由。这个错误属于本轮审计评分，不能归到产品或模型。见[逐事件证据](turn-audit-results.json)。

## 4. 标签究竟要求什么

逐项查看旧新增17处和全部17个1–3秒停顿的原转写/词时间，不改变标签。17处新增中，10处涉及号码/编号继续口述，1处包含“等一下”，另6处前半句可能已形成完整表达、但录音中仍继续说话。这个分组是源文本检查，不是新的真值或独立听辨；[逐项ID与检查范围](turn-audit-label-review.json)保留。

例如源 `zh__2352` 有“暂时没有了，以上就是我今天的请求”，停顿后继续道谢；`zh__2516` 有“对的，那就这样”，后面继续补充。数据将这些中间停顿标为hold，应继续保留为困难用例，但不能据一次响应就断言模型不懂中文。反过来，号码读到一半、明确说“等一下”时接话仍值得查，不能因为存在歧义样本而抹掉整批失败。

词级时间还会跨越原静音区段，故不能用词时间擅自重定边界或“纠正”标签。未独立听辨的部分明确保留不确定性。原转写未上传为新的完整副本；报告不复述号码等内容。[数据来源](https://huggingface.co/datasets/livekit/eot-bench-data)与原revision保持一致。

## 结论与下一步边界

- 模型输出：这批固定输入的错误可重复，不是随机生成波动。当前模型组合尚不能宣布满足原验收；尚未唯一归因到训练域、八秒窗口、ASR或量化。
- 测试：旧原始分数及标签不变；收窄“漏判/抢答/整体延迟”的解释，补真实VAD触发与未知区间分类。评测差异不能被当作全部失败的借口。
- 产品方案：本轮没有证据要求推翻VAD加语义判停、独立待机许可和自然打断的职责划分。也没有验证产品已经正确接好这些能力；现场旧800ms收尾/播放关麦问题仍未解决。
- 已完成这轮有上限的归因复核，停止追加同类重复试验；不据单一总分继续换模型，不调门槛，不安排用户测试。后续模型适配与Controller测试分开推进，改变检测架构或验收边界前仍须明确说明并确认spec。

## 复现

[预测前计划](turn-audit-plan.json)、[已读上游摘要](turn-audit-upstream.json)。脚本只读取原缓存；Pipecat及少量依赖装在本轮独立target目录，原评测venv未改。两个推理作业各CPU单线程，旧证据SHA保持不变。5项审计回归及原5项回放回归通过。

```sh
python3 -B docs/research/2026-10-27-natural-turn-taking/test-turn-audit.py
python3 -B docs/research/2026-10-27-natural-turn-taking/test-turnsense-replay.py

# TURN_ROOT、AUDIT_OUT、AUDIT_DEPS、PIPECAT_WHEEL均由运行者指定。
PYTHONPATH="$AUDIT_DEPS" HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
  OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 NUMBA_NUM_THREADS=1 \
  "$TURN_ROOT/turnsense-20261028/venv/bin/python" \
  docs/research/2026-10-27-natural-turn-taking/audit-turn-pipeline.py \
  --root "$TURN_ROOT/turnsense-20261028" --out "$AUDIT_OUT/audio-vad" \
  --wheel "$PIPECAT_WHEEL" --dependencies "$AUDIT_DEPS"

HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 \
  "$TURN_ROOT/xturnix-venv/bin/python" \
  docs/research/2026-10-27-natural-turn-taking/audit-xturnix-repeatability.py \
  --root "$TURN_ROOT" --out "$AUDIT_OUT/xturnix-repeatability.json"
```

模型、音频及运行依赖不入库。没有付费API调用、开麦、播音、ADB、服务重启或部署修改；spec、协议、提示词、APK与声音登记保持。
