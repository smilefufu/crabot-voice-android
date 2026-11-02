# Namo 文本判停固定集参考

2026-11-02。两个版本均不通过原固定集，不接入产品。这是使用既有 ASR 文本的质量参考，不是已确认端侧纯音频判停方案的替换，也没有使用新的 ASR、用户录音或付费接口。

## 输入与实现核对

预测前固定[计划](namo-evaluation-plan.json)：原 20 个公开音频 ID、原标签、整段和末 8 秒各一份既有 ASR 文本，共 40 条；不修正转写，不附加提示词、标点、词尾规则或改阈值。`whole-turn-asr.json` 的 SHA 为 `098cdc49754ad747d3b901aa69f0c47331523903087ac14d5a6fbf27e03a598e`。

使用官方量化 ONNX、原分词器与两类 argmax（0 未完成、1 完成），CPU 单线程。两个模型的 revision、权重和其他文件 SHA 见[完整 240 项结果](namo-results.json)。[分词器对照](namo-tokenizer-parity.json)确认全部 80 份 input_ids/attention_mask 与官方 AutoTokenizer 精确相同；探针和核对都显式关闭截断与补齐。初版独立核对忘记关闭 JSON 自带补齐，修正核对前置条件后逐项相同；实际预测探针原本已正确关闭补齐，没有重跑挑选结果。

## 结果与结论

每个模型固定顺序重复三遍，动作变化均为 0。

| 模型 | 整段：提前结束 / 未完成 10 条 | 整段：漏判 / 完成 10 条 | 末 8 秒：提前 / 漏判 | 热分类墙钟 P50 / P95 |
|---|---|---|---|---|
| Namo Chinese | 1 | 0 | 1 / 0 | 10.17 / 14.82 ms |
| Namo Multilingual | 6 | 4 | 5 / 3 | 24.54 / 34.84 ms |

Chinese 仍将原失败 `0384164c-d1e3-4881-861b-1bf5494cd90e` 判为完成。Multilingual 在整段也误判该项；末 8 秒这一项虽然正确，但其余错误更多，不能单独拿这一格宣布改进。两者均未满足原“不新增提前结束”的门槛，停止作为当前产品候选，不继续用阈值扫描或扩大样本均值掩盖固定集失败。

首次分类分别 17.31 / 40.96 ms，模型加载分别 168.16 / 1196.93 ms；热统计包含后两轮 80 次调用。它们仅包含文本分词和分类，不含 ASR、采音、VAD、网络、Agent 或 TTS，不能作为手机端点延迟。这批转写也不是人工真值，不能由分类器失败推导整个自然轮次架构不可行。

## 来源与复现

主来源：[官方用法](https://github.com/videosdk-live/NAMO-Turn-Detector-v1)、[Chinese](https://huggingface.co/videosdk-live/Namo-Turn-Detector-v1-Chinese)、[Multilingual](https://huggingface.co/videosdk-live/Namo-Turn-Detector-v1-Multilingual)。两者许可 Apache-2.0，不提交权重。

独立 Python 3.12 环境：numpy 2.2.6、onnxruntime 1.23.2、tokenizers 0.22.1；官方分词器核对另用 transformers 4.57.1。

```sh
python probe-namo.py --cache <独立模型缓存>
USE_TORCH=0 USE_TF=0 python verify-namo-tokenizer.py --cache <相同模型缓存>
```

未改 App、默认 wire9 入口、协议、提示词、部署、ADB、身份记录或产品判停参数。后续质量参考继续保留原失败样本；更换为预先 ASR 或服务器判停涉及架构选择，必须另行说明并确认。
