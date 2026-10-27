# 整轮 ASR 与 XTurnix-ZH：准备结果及未完成项

2026-10-27。结论：已确认来源、许可、官方输入格式，完成原固定集的整段/末尾八秒转写及格式核验；**候选推理尚未运行，不能断言 XTurnix-ZH 能纠正原误判，也不进入产品**。当前阻碍是公开权重及依赖下载，不是新的准确率结果。

## 本次实际完成

沿用[预登记](xturnix-evaluation-plan.md)及原来每标签 10 条的公开固定集；ID/音频 SHA/标签保持一致，不改录音、不换样本、不用人工改写 ASR 文本。20 条时长 4.64–13.52 秒，全在产品原 15 秒容量内。

| 项目 | 实际结果 | 不代表什么 |
|---|---|---|
| 整段及末尾 8 秒 ASR | 20×2 条全部有转写，保留原文、标签、SHA 及耗时 | 不是候选准确率，也没有实测流式 ASR 的末端等待 |
| 原误判样本整段转写 | 保留前文“清初文学家金圣探把杜甫所作之诗……”；人名等有识别误差 | 不能据此认定语义检测会正确等待 |
| 官方模板/动作 token 核对 | 40 份输入 98–124 token，均保留完整当前文本，远低于官方 2048 上限 | 只是 tokenizer/格式结果，尚未验证模型加载/推理 |
| 独立模型推理 | 未运行，无预测分数 | 未通过，不是通过，也不算模型判定失败 |

ASR 使用固定 SenseVoice int8、16 kHz、单线程、中文、ITN；本机 M2 Ultra 的整段解码中位数约 339 ms。它是不含传输/VAD/调度的文件离线耗时，不能直接承诺端点延迟或手机性能。详见 `whole-turn-asr.json`。

## 来源与实际接口

[官方模型](https://huggingface.co/xcczach/xturnix-zh-base/tree/b69ac8a4d98e93332ca3452c569a77dd499287a9) revision `b69ac8a4d98e93332ca3452c569a77dd499287a9`：Qwen3 0.6B，BF16，权重 1,192,135,096 字节，Apache-2.0。树对象及 LFS SHA 保留于 `xturnix-files.json`，许可保留于 `XTURNIX-LICENSE`。

[官方 turn_pipeline.py](https://huggingface.co/xcczach/xturnix-zh-base/blob/b69ac8a4d98e93332ca3452c569a77dd499287a9/turn_pipeline.py) 不是任意聊天提示词：它注入固定状态说明、使用非思考 chat template，并移除最后一条用户文本的末尾标点；倾听时只比较 `<|start|>` 与 `<|keep|>` 两个特殊 token 的下一 token logits，选较大者。动作分数是这两个候选之间归一化的值，不等同于已校准的现实判断置信度。未采用模型 speaking/stop 能力，不替代 AEC。

官方推理管线使用的上下文上限为 config 的 `xturnix_config.max_seq_length=2048`；不能拿 Qwen3 基础配置的 40960 位置上限当作已验证能力。官方截断旧完整对话轮，保留当前用户文本；连当前文本也装不下会报错。本次只有当前轮，没有引入旧会话内容。40 份公开转写完整放得下，仅排除这批文本因 token 上限丢前文的情况。

## 下载边界及可复现状态

模型传输发生 TLS EOF；一次有界重试通过官方 raw/resolve 入口取得 tokenizer 等九份非权重文件，均按发布者 Git blob/LFS 摘要验证。1.2 GB 权重 180 秒只取得 20,971,520 字节，按预登记停止，只有 `.part`，不能用于加载。固定 PyTorch 2.9.1 / Transformers 4.57.3 的独立安装也因官方 CDN 过慢而停止；只停止本次安装进程，没有终止任何业务服务。一次官方 PyTorch CDN 1 MiB 范围请求约 81 秒，未为此修改全局代理、证书或已有服务环境。

格式核对独立记录为 Transformers 5.19.0 / tokenizers 0.23.3 / Jinja2 3.1.6 / MarkupSafe 3.0.4，**与待运行推理环境分开**；从已校验官方源码提取原始文本预处理函数，不导入未审阅远程代码，不加载模型。脚本显式读取 `input_ids`、核对解码包含完整文本，避免新版 tokenizer 返回映射时把字段数错当 token 数。结果见 `xturnix-qualification.json`，其中 `predictions=null`。

复现入口（路径均由调用者指定）：

1. 在隔离研究目录准备原固定音频和源 `xturnix-files.json`；用官方仓上述 revision 下载到 `xturnix-model/`，保留完整权重，不能将 `.part` 改名使用。
2. `probe-whole-turn-asr.py --root <研究目录> --service-root <固定外部服务副本> --corpus <labeled-corpus.json>` 校验 ASR 资源及公开音频 SHA，输出 `whole-turn-asr.json`。本次实际运行过。
3. `probe-xturnix-format.py --root <研究目录>` 验证各文件摘要及完整文本，输出资格记录。本次实际运行过；补全权重后须重跑，使 `weight_present_and_verified=true`。
4. 在隔离且固定版本的推理环境运行 `probe-xturnix.py --root <研究目录> --device mps`。它先验证完整权重/源码摘要，再按预登记预热及 full/tail8 顺序运行；记录原失败分数、全部误判、ASR/检测分项延迟及标准 pipeline 对照。**该脚本目前只做语法检查，未运行，准备好不算测试通过。**

不提交模型、音频、特征、临时签名 URL、私有配置或依赖环境。未更换后端/APK、未改产品提示词/协议、未开麦/播音、未重启 ADB、未调用付费模型，也没有安排用户重测。

## 之后的决策边界

权重与独立运行环境就绪后，先完成同一批候选实测。只要整轮输入仍有提前结束，就报告失败，不能调分数门槛、延长静音、换标签或拼音频宣布通过。即使这 20 条通过，也仅证明固定合成组件集，不能称自然停顿已验收。

若结果支持这个方向，须先向用户说明并确认最小 spec 修订：仅对已经唤醒的当前轮允许准入前转写；独立中文检测服务负责判断是否说完，业务主控仍只接收正式提交的完整输入。等待唤醒不送环境声音做 ASR/LLM；声纹、身份/权限、AEC、任务执行及输入容量保持既有边界。ASR 错误、迟到结果、恢复说话及检测失败如何收口，需要在修订中明确，不能借本调查静默改产品。
