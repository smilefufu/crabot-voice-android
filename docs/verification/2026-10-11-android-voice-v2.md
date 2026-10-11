# Android Voice 协议 v2 验证记录

日期：2026-10-11。对应已确认的联系人与身份修订；这不是完整产品验收通过。

## 构建结果

- 分支：`feat/android-voice-v1`，产品 `com.crabot.voice`、`versionName=0.2.0`、`versionCode=2`、Android 29+ / ARM64。
- Wrapper 构建 `testDebugUnitTest assembleDebug assembleDebugAndroidTest` 通过；4 项 JVM 测试通过。新增检查覆盖分段越界、非有限值及内容重叠整轮失败。
- APK：54,613,341 bytes；SHA-256 `c331afbfd091e3ffa461fc84fe22865dc9c60a5f5b6ddbceb81e714cf092c50c`。
- `apksigner verify` 通过，Android Debug 签名证书 SHA-256 `8b20f31fe89d9cd746c9049cf6d8305ea79a3ebabb7aa4c852328550cdee0af0`。不含测试 WAV、用户录音、向量或 Provider/Admin 凭据。
- 固定模型、JNI、Wrapper 和依赖版本保持原设计；共同正式契约是 `protocol-voice.md` v0.2.0，终端整数版本 `2`。

## 已落实的行为及证据边界

客户端不再拒绝身份 unknown/uncertain 的整轮：所有可归属的片段都上传，未匹配片段不带候选登记 ID。无声纹登记也可完成分段；短簇不足身份确认按陌生人保留，内容重叠/无效/模型失败仍整轮失败。后端逐段使用相同 PCM 区间做 ASR，并按当前联系人绑定生成 Friend 或陌生人标签。

手机登记仅关联后台指定的稳定渠道联系人，删除或重登使用独立声纹样本 ID。ASR/TTS 只展示 API 地址，手机不持有 Provider 或 Admin 凭据。旧 WIP 加密数据被版本检查拒绝、保留，清除需现场明确操作；不读写旧实验 App 的 FuFu 声纹。

后端隔离回归验证了真实 WSS 的 A→B→A 与 A→陌生人→A 全量文本、旧版握手拒绝、播放 completed 才确认成功、绑定名册与声纹分离。实际 Agent handler 验证篡改通知 Friend/正文/回答时限不会替代 exact-core Admin 读取，Manager 输入按每条消息标身份。它们证明业务接入逻辑，不证明端侧真人分段或匹配准确率。

## 本次仪器测试未运行

专用禁音频、无保留数据的 Android 16 ARM64 模拟器在系统启动阶段挂起并退出，未进入测试 App。复核过默认图形、禁 Vulkan 软件图形、此前成功参数和主机图形/新建测试数据，均出现 QEMU CPU/main loop 无响应，无法用 ADB 安装测试。未修改其他模拟器，未连接任何真实手机。

本次仪器包已编译，增加无候选声纹完整分段与旧 WIP 加密文件保留检查，配对测试使用版本 2。6 项仪器测试当前为待运行；[0.1.0 的 5 项历史成功](2026-10-11-android-voice-v1.md)不算作本次通过。恢复模拟器后按 README 的专用测试命令执行。

## 继续验收

1. 模拟器恢复后完成 6 项仪器测试、App 启动关麦界面；手机连接后安装 0.2.0，与配套后端使用独立测试数据配对并完成单人录放音。
2. 创建联系人、绑定 Master 和正常 Friend，分别登记；删除声纹不改绑定，解绑/换绑对下一轮身份生效。
3. 未登记、已登记新短句、短接话、A→B / A→B→A；第二位说话人到场后做真实测试。score 0.50、margin 0.08、最少 1.5 秒仍是未校准研究值。
4. 锁屏、退后台、焦点/路由变化与麦克风/播放器释放；真实播放完成与按钮打断；播放关麦策略保持，AEC 尚未验收。
5. 实际场地距离、电视/音乐/自播误唤醒、50 轮 median/P95、30 分钟温度/电量/PSS，以及 Linux system mode 两 UID 隔离。

私聊和 Agent 发起登记属于后续；当前一期所有发言进入同一个普通群，无匿名发言准入特例。
