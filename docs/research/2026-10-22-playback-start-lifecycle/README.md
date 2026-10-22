# 播放启动时序的隔离复现

2026-10-22。两项是**旧生产序列的故障复现**，不是验收通过或声纹实验；运行时预期失败，不放入常规app测试目录。首版夹具缺少存储字段、在setup失败，已剔除；result.json只记录修正后的两项。

在原0.8.0产品APK上，只覆盖测试APK。构造函数后立即取消排队的模型加载协程；使用自己的空缓存、合法的内存配对字段和假socket，不resume、不连后台、不启麦或读取真人档案。所有状态在主线程执行，结束destroy并删除本次独立缓存；真实terminal.enc前后摘要一致。

- 先证明ready及预留已接纳，再等3.3秒，实际预留为null，与后台首音频5秒/等待正文的预算相冲突。
- 先证明预留已接纳，再按旧Engine空头重试路径送playback_cancel，实际原turn也为null。该结果应与后端现有voice-stream空头重试用例一起读，不等于模型或全部历史无声都由此造成。

修订后的回归应测试“首段正文→立即start→延迟PCM”和“空头不打开终端”，不继续要求新后端发送这两条旧故障序列。

复现时把VOICE_REPRO_SRC设置为本目录的绝对路径，使用本目录repro.gradle仅增加androidTest源；只执行VoiceReservationReproTest。勿在真人设备运行完整connectedAndroidTest，勿覆盖产品APK或清数据。本轮结束恢复普通首页，测试不维持观察器。

## 现有客户端的兼容验证

独立选跑existingClientWaitsForPcmAfterAnEarlyStartWithoutFalsePlaybackReceipt，通过1项/3.962秒。已接受ready后立即送原playback_start，等待3.4秒没有PCM、没有误报playback回执；随后仅送7200个静音PCM样本，实际游标前进才报started，完整消费后报completed，麦克风仍关闭。它证明现有0.8.0支持修订后的启动序列，不代表后端已修复或真人体验通过。

对应三方法源码快照为VoiceReservationReproTest.with-compatibility.kt.txt；与前面两条预期失败诊断分开运行，结果见compatibility-result.json。首次额外方法构建曾因序号类型错误失败，旧APK产生的0 tests输出已剔除；本记录只采用修正源码重新构建后的1 test。重放兼容项时将该快照以VoiceReservationReproTest.kt放入独立源目录，并显式选择上述单方法，避免运行另两条预期失败序列。
