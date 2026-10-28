# 自然轮次组件的隔离验证

2026-10-27。配套设计已确认，正式协议已发布 wire10；产品、已安装 APK 和线上协议仍为 wire9。本记录是组件验证，不是自然停顿、AEC 或持续待机验收。

## 本轮结论

- Smart Turn v3.2 CPU 可与现有 ORT 1.28.2 共用，不引入第二个运行库。原 OnePlus 7 Pro 上公开特征文件的 30 次单线程推理：中位数 105.08 ms，P95 107.81 ms，最大 110.17 ms。它只测推理，不含 Android 特征提取、采音队列或完整端点延迟。
- 中文固定集没有通过“不提前提交”门槛：10 条发布者标注的未完整音频中，同一条被三个官方模型变体判为结束。没有调阈值、换样本或通过延长等待算作修复，因此暂停该组件的产品接入。
- WebRTC APM 1.3 的官方发行源、摘要及 COPYING 已固定，尚未编译、打包或验证真实回声消除。不能据此称自然打断已实现。

## 来源、选择和复核

模型来自 [Pipecat 0.0.102 发行包](https://pypi.org/project/pipecat-ai/0.0.102/) 的 `smart-turn-v3.2-cpu.onnx`，8,679,182 字节，SHA-256 为 `2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f`。与[官方模型仓](https://huggingface.co/pipecat-ai/smart-turn-v3/tree/main)的 LFS SHA 一致；许可 BSD-2-Clause。发行包及上游预处理文件摘要见 `model-provenance.json`，许可保留于 `PIPECAT-LICENSE`。

探针执行原模型约定：16 kHz 单声道、取末尾 8 秒、不足左补零、WhisperFeatureExtractor(chunk_length=8)，`do_normalize=True`，输入 1×80×800 float32，概率严格大于 0.5 为结束。未安装整套 Pipecat，也未更改现有语音服务的 Python 环境。

另提取发行包内原始 `_predict_endpoint` 函数，与本探针的 120 份输入特征精确比较，全部逐元素一致，阈值决定也一致，见 `publisher-preprocessing-parity.json`。这排除了本次特征转换/判定代码偏差，不能排除标注歧义或模型能力限制。

中文音频取自 [pipecat-ai/smart-turn-data-v3.2-test](https://huggingface.co/datasets/pipecat-ai/smart-turn-data-v3.2-test)，revision `dfc179241fee65b0efe81ca37786f41ce26f38ec`，default/train（数据集名虽是 test，实际 split 名为 train），许可 CC-BY-4.0，归属与贡献者见原数据集说明。先读前 1,000 行，再按源顺序选每种标签的前 10 条中文样本；选择在模型预测前完成。全部 20 条都是发布者标记的合成语音，不是自然家庭录音。ID、行号、标签、文件 SHA 见 `labeled-corpus.json`。

每条增加 0/0.2/0.8/1/2/3 秒静音，共 120 个用例。人工追加静音只作固定组件压力对照，不能冒充真人思考停顿；本次没有运行真实 VAD 调度，因此未测完整端点结束等待，也未执行原 Controller 的完整对照回放。原 800 ms 规则不判断句意；此次组件结果不能替代设计门槛或整链对照。

| 模型（统一以 CPU 单线程运行） | 原边界误判结束 / 未完成 10 条 | 追加 1 / 2 / 3 秒后误判结束 | 追加 0.8 秒后未结束 / 完成 10 条 |
|---|---|---|---|
| v3.2 CPU int8，拟首选 | 1 | 1 / 1 / 1 | 0 |
| v3.1 CPU int8，对照 | 1 | 1 / 1 / 1 | 1 |
| v3.2 非量化（文件名 gpu），对照 | 1 | 1 / 1 / 1 | 0 |

未通过样本 ID `0384164c-d1e3-4881-861b-1bf5494cd90e`，行 452，发布者标注 incomplete/midfiller/synthetic。v3.2 CPU 在原边界的结束概率 0.881712，追加 1/2/3 秒后为 0.955731/0.988007/0.980147。样本长 10.84 秒，官方输入窗口只保留末尾 8 秒：上下文丢失是待核实因素，不是已确认根因。不修改原标签以宣布通过。详细 120 项及两个变体结果见 `labeled-results.json`、`variant-comparison.json`。

## 性能与复现边界

先用既有公开 sherpa speaker-recognition 音频的整段/半段及追加静音做 30 份机制特征。它们没有端点标签，不能统计准确率。来源和文件 SHA 复用 `../2026-10-14-short-speaker-evidence/sources.json`，电脑特征提取 P95 6.01 ms、推理 P95 43.48 ms，见 `host-smoke.json`。手机使用相同预计算特征，数据见 `android-inference.json` 和原始数值 `android-inference.txt`。

- `probe-public-features.py --root <隔离研究目录> --public-audio <公开缓存> --sources <上述来源JSON>` 生成机制特征并调用 `probe-ort`。
- `probe-labeled-features.py --root <隔离研究目录>` 校验预选 ID/音频 SHA 后生成标注集特征；需要私有下载元数据 `public-selection.json`（由官方 rows API 返回）及脱敏的下载 SHA 清单。带时效签名的下载链接不入库，重放时应重新请求同 revision 的行并核对 ID/标签。
- Python 测试环境：numpy 2.2.6、soundfile 0.13.1、transformers 5.19.0、scipy 1.18.1。C++17 探针使用 ORT 1.28.2 同版本公开 C API 头文件；host 链接现有 sherpa 的 dylib，Android 使用现有 arm64-v8a libonnxruntime.so，NDK 28.2.13676358、API29，`-O2`、静态 C++ 标准库、rpath `$ORIGIN`。不提交模型、头文件二进制、特征或音频。
- 手机探针只在本轮新建的 `/data/local/tmp/crabot-turn-probe-20261027` 工作，结束已删除这个目录。没有打开麦克风/音箱、修改 App/配对/声音记录、重启 ADB 或变更密钥。现场 APK 没有升级。

## 下一步和停止条件

八秒限制的实际 ORT 核实、保留前文对照与候选资格见[后续核实](context-and-alternatives.md)。已确认固定输入维度，不能唯一归因于截断。后续[整轮 ASR＋XTurnix 实测](xturnix-findings.md)已解决下载，完成 40 项主对照、20 项串行墙钟及 40 项 CPU 参考。原误判被纠正，但新增一条提前结束、10 条完整句漏判 8 条，CPU/MPS 动作全部一致；整体热重放中位数406ms/P95约543ms（不含采音/VAD/网络/Agent/TTS），首次预热另约2.45秒。当前组合不通过，不改 spec/产品或安排真人试用。

保留本批失败用例，先进一步核对中文端点模型适用性及八秒上下文边界；任何改变已确认检测架构或放宽门槛的方案都先报告，不能直接改产品。APM 的构建及公开回声基准可独立准备，但真实录放音须先说明范围并获现场许可。A2 未通过前不安排重复真人试用，不宣称已解决现场的提前收尾/关麦问题。

2026-10-28后续[中文候选与取舍](chinese-options-20261028.md)：优先隔离验证TurnSense音频路线，Easy Turn作为联合模型备选；增加真实中文逐停顿重放方法，保留原失败集，不调参凑通过。仅完成官方代码/许可/接口核查，新候选均未实测，未改已确认设计或部署。
