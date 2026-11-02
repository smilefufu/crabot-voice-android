# Easy Turn 联合音频/语言固定集参考

2026-11-02。**当前候选不通过，不接入产品。** 本轮没有用模型失败替代架构结论，也没有降低原门槛。它是大型研究模型的隔离参考，不是手机端点性能或完整对话验收。

## 可复核的输入和适配

预测前固定[主计划](easy-turn-evaluation-plan.json)：原 20 条公开音频、原标签和字节 SHA 全部保持，分别整段/末 8 秒，共 40 项；原失败与预先选定的另外四条整段各补两次，共 50 调用。旧音频缓存已不存在，经公开 rows API 恢复后，20 条逐字节符合原清单；不保存临时签名链接。恢复工具[只接受原清单和 SHA](restore-labeled-audio.py)。

固定 GitHub `fe175eaa…`、HF `7d1f8b06…`，完整 3,451,801,684 字节检查点 SHA 为 `1cb535bb0ffe3ffa0ac941bae4898aeb87adef7b88bb58f665af08eded6f6070`。只下载固定 Qwen2.5-0.5B-Instruct 的配置和分词器，用内置类建立模型后 **strict 加载全部 744 个张量，缺失/额外均为零**；别名张量内容一致。保持官方 float32 编码器和 bfloat16 Qwen，不切量化或精度找更好的结果。

[探针](probe-easy-turn.py)调用原模型/生成方法，并执行原源码中的 log-mel 函数；分开载入该函数是为了避开训练工具和 SoX 全局初始化，不改算法。原 torchaudio 重采样、官方首条提示词（含原首字符）及确定性生成参数均固定。只用 CPU，两条计算线程、一个推理作业；无远程自定义代码或付费接口。

## 实际结果

| 输入 | 提前结束 / 原未完成 10 条 | 漏判 / 原完成 10 条 | 原失败 |
|---|---|---|---|
| 整段 | 10 | 0 | 仍误判完成 |
| 末 8 秒 | 8 | 0 | 仍误判完成 |

预先固定的五条各三遍，动作变化为 0。整段不仅没有纠正原误判，还增加了提前结束；不能以末 8 秒的两条局部改进、完整句全判对或均值掩盖失败。不继续扩展到 256 条自然停顿回放，不做提示词/阈值扫描。

加载约 11.62 秒。首次波形加载/特征/生成共 27.65 秒（其中首次特征约 3.32 秒）；其余 49 次热参考：特征 P50/P95 为 3.23/4.00 ms，生成 P50/P95 为 18.30/24.01 秒，总参考 P50/P95 为 18.30/24.02 秒，最大约 26.50 秒。不是手机或 GPU 性能，也不含采音、VAD、网络、Agent 和 TTS，不能借上游 4090 指标声称手机可用。

### 解析和官方对照的审计

初版严格解析将所有结尾的 `<|endoftext|>` 当成额外文本，因此记为 invalid。独立核实：官方构造函数把原 pad token 改为 `[PAD]` 后，151643 不再属于分词器 special IDs，`skip_special_tokens=True` 仍保留该标记；官方生成又显式以 151643 结束。这是评测适配问题，不能记为模型分类错误。

保留原严格字段、全部文本和原计划，再用[独立分析器](analyze-easy-turn.py)仅去掉一个已知的尾部结束标记。没有重跑预测、改标签或去掉其他内容；六项[解析回归](test-easy-turn-analysis.py)覆盖重复标签、重复/中间结束标记、未知尾部及四类动作。[原输出](easy-turn-results.json)、[解析审计](easy-turn-analysis.json)和[汇总](easy-turn-summary.json)可逐条核对。

为排除“模型根本没有正确装载”，预测前另固定[官方四类各前两条](easy-turn-publisher-plan.json)。单声道六条（complete/incomplete/backchannel 各二）全部判对；另外两条 wait 是双声道，官方特征函数要求单声道，单列未执行，不临时混音或换例。首次跑到第一条双声道时停止；保留已得到的六次预测，仅补充两条文件格式检查，无重复推理。[全部对照记录](easy-turn-publisher-results.json)。不能把它写成八条全通过，也不足以宣称复现官方完整测试成绩。

这些对照支持“基本模型与官方调用已跑通，固定中文集存在实际质量差异”，未唯一定位训练域、提示词、精度或声学特征对误判的贡献。CPU 与 GPU 数值一致性尚未验证，不能断言发布者模型或宣称指标有错。

## 来源、依赖和停止边界

来源：[官方代码/论文说明](https://github.com/ASLP-lab/Easy-Turn)、[固定模型](https://huggingface.co/ASLP-lab/Easy-Turn)、[官方测试集](https://huggingface.co/datasets/ASLP-lab/Easy-Turn-Testset)、[基座配置](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct)。代码、权重、测试集许可 Apache-2.0；原固定集仍归属 Pipecat/CC-BY-4.0，详见原清单。权重、音频、依赖不提交。

独立 Python 3.10.19：torch/torchaudio 2.1.0、numpy 1.24.0、transformers 4.44.0、peft 0.17.0、accelerate 1.7.0、librosa 0.10.2.post1、scipy 1.10.1、scikit-learn 1.3.2、openai-whisper 20240930、gxl-ai-utils 1.6.1（辅助日志）。Whisper 老构建依赖 pkg_resources，固定 setuptools 70.3.0、wheel 0.45.1 并关闭其构建隔离后安装，未改系统环境。首次探针的 Path/mmap 参数类型错误在预测前修复，没有把它计入模型失败。

```sh
python restore-labeled-audio.py --output <独立缓存>/audio
OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 VECLIB_MAXIMUM_THREADS=2 \
  HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 TOKENIZERS_PARALLELISM=false \
  python probe-easy-turn.py --cache <独立缓存> --source <固定官方代码/Easy_Turn>
python analyze-easy-turn.py --input <缓存>/results.json --output <缓存>/analysis.json
```

原声学判停、文本判停和联合模型的失败均保留，不再以更大模型或更长等待凑通过。原 VAD/语义判停、独立待机许可、自然插话职责划分未因此推翻；中文 EOT 仍是明确未通过项，改变判停位置、准入前转写或验收边界须先说明并确认。硬件时钟/AEC 可独立验证，不能称产品自然对话已可测试。未改部署、手机、ADB、产品提示词、权限或正式协议。
