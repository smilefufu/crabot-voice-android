# 短句身份长度门槛：有限文件验证

日期：2026-10-14。产品候选为 APK0.5.1/code12/wire5。本记录只提供修订的测量依据，不修改产品或宣称真人声纹已通过。

现场轮次 `77d77e97-9e1a-4e58-b124-1b8e5e3ad03e` 已被后台 accepted；手机上行录音3.72秒，匿名人声片段1.18125秒。现有候选 FuFu 已 active 且有同模型本地向量，长度判断先于 embedding/matches，故结果 uncertain 并且没有比对分数。该轮 tag→TTS→手机实际 started/completed 已有真实回执（303600个播放样本）；身份识别与音频播放分别验收，尚未收到用户确认听感及三轮接话。

公开录音来自 [sherpa-onnx speaker recognition release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models)，下载后按 `sources.json` 核验摘要。A=fangjun，登记3个文件；B=leijun，登记2个文件；C=liudehua，不登记。各自测试文件独立于登记，五个测试文件去外侧低能量部分、保留内部停顿，以0.5秒步长裁剪8种长度，共291个相互重叠的片段。

登记使用原始向量求均值，和产品登记过程一致；查询提取特征后按分数0.50、候选差值0.08判断，另计算仅登记A时的未知拒识。不是自匹配实验，不固定真实说话人数给识别器；没有进行多人分段或业务提交。三个来源人、一个未登记者以及重叠片段不足以估计家庭误识率。

Mac CPU/2线程结果见 `mac-results.json`。OnePlus 7 Pro/Android10 使用 APK 相同的原生库、CPU/2线程，结果见 `native-results.json`；这两次所有291个查询的判定一致。手机实验仅在独立临时目录读取公开文件，不连接 Crabot、不读取真实声纹/配对、不启动录音或扬声器。

| 有效片段长度 | 已登记者正确/总数 | 误归为另一登记人 | 未登记者误接纳/总数 |
|---|---:|---:|---:|
| 0.3秒 | 3/40 | 0 | 0/6 |
| 0.5秒 | 14/38 | 0 | 0/6 |
| 0.75秒 | 31/36 | 0 | 0/5 |
| 1秒 | 31/34 | 0 | 0/5 |
| 1.18秒 | 32/33 | 0 | 0/4 |
| 1.5秒 | 29/30 | 0 | 0/4 |
| 2秒 | 26/26 | 0 | 0/3 |
| 3秒 | 20/20 | 0 | 0/1 |

各长度全部能提取有效特征；仅登记A时，各长度均未把B/C匹配为A。这些有限观察不能证明未来零误识，尤其极短片段。1.5秒不是模型接口硬限制；此前1秒候选在讨论后撤回，当前待确认稿改用现成IsReady判断提取前置条件，实际FuFu仍需现场复核。

手机和本机权重/库逐文件SHA256一致：

| 文件 | SHA256 |
|---|---|
| campplus.onnx | f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11 |
| libsherpa-onnx-c-api.so | 5b47d50661f2a5c5399ff2a47e6ff07031c8b31dab0480313c237b063a573dd8 |
| libsherpa-onnx-cxx-api.so | 587a8d54936b1f669231b26d9e6e424af7e51f417fd013f78fd7e49580b0068f |

`evaluate.py` 使用 sherpa-onnx1.13.8/numpy/soundfile，传入冻结模型及已核验公开数据目录；输出每个片段的来源、偏移、分数和汇总。`probe.cpp` 以Android NDK/API29链接上述同版本库，读取登记名单及由前者来源/偏移生成的PCM16裁剪名单，运行相同的embedding和GetBestMatches。原始公开录音、模型及生成的WAV不入仓；用户音频/向量始终不进入本记录。

补测同一权重的本机0.1/0.15/0.2/0.25/0.3秒公开片段，IsReady均为true，输出192维有限非零特征，见 `below-300ms-results.json`。只证明0.3秒不是模型硬下限，不评价这些片段的身份正确率，也不声称已在手机复核。上游CAM++所用通用实现的[IsReady](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/speaker-embedding-extractor-general-impl.h)检查可计算的特征帧，不提供身份可信度保证。
