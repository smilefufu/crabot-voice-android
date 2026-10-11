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

专用禁音频、无保留数据的 Android 16 ARM64 模拟器在系统启动阶段挂起并退出，未进入测试 App。复核过默认图形、禁 Vulkan 软件图形、此前成功参数和主机图形/新建测试数据，均出现 QEMU CPU/main loop 无响应，无法用 ADB 安装测试。未修改其他模拟器；上述模拟器尝试期间未连接真实手机。

本次仪器包已编译，增加无候选声纹完整分段与旧 WIP 加密文件保留检查，配对测试使用版本 2。6 项仪器测试当前为待运行；[0.1.0 的 5 项历史成功](2026-10-11-android-voice-v1.md)不算作本次通过。恢复模拟器后按 README 的专用测试命令执行。

## 真机安装与接口准备

2026-10-11，OnePlus 7 Pro 已通过 ADB 连接。上述产品 APK 安装成功，系统报告 `versionName=0.2.0`、`versionCode=2`；启动界面显示“模型已加载”“麦克风关闭”和“未连接”。麦克风与相机权限均未授予，没有采集手机声音。旧实验 App 和 FuFu 登记保留；不在这台手机运行会清理配对与声纹的仪器测试。

配套后端的 Shared/Core/Admin/Agent/Voice 编译已完成。产品音频适配器连接独立回环 SenseVoice/Kokoro 服务，以固定测试音频转写出“你好，我是螃蟹。”，固定文本生成有效 24kHz/mono PCM。仅证明接口连通，未经过手机、Manager、LLM 或实际扬声器播放。

2026-10-11 20:25:00（Asia/Shanghai），按用户明确授权完成配套后端全模块、Web、CLI、MCP 构建及 `crabot restart`，替换现有 user mode 部署并保留数据。后端使用本地部署分支保留 main 既有提交，PR #206 未合并；实际部署目录的 48 项 Voice 定向回归通过，原有七模块健康，实际后台 HTML 与新构建一致。独立回环 ASR/TTS API 已启动，Crabot 只通过接口调用。

尚未与配套后端配对、登记声纹或做真人录放音。后台已登录；此轮部署后 ADB 列表无手机，重新连接后继续创建语音实例和配对。进程健康和固定接口测试不代表真机验收完成。

## 继续验收

1. 模拟器恢复后完成 6 项仪器测试；真机 0.2.0 已安装且启动关麦，重新连接后与已部署的配套后端配对并完成单人录放音。
2. 创建联系人、绑定 Master 和正常 Friend，分别登记；删除声纹不改绑定，解绑/换绑对下一轮身份生效。
3. 未登记、已登记新短句、短接话、A→B / A→B→A；第二位说话人到场后做真实测试。score 0.50、margin 0.08、最少 1.5 秒仍是未校准研究值。
4. 锁屏、退后台、焦点/路由变化与麦克风/播放器释放；真实播放完成与按钮打断；播放关麦策略保持，AEC 尚未验收。
5. 实际场地距离、电视/音乐/自播误唤醒、50 轮 median/P95、30 分钟温度/电量/PSS，以及 Linux system mode 两 UID 隔离。

私聊和 Agent 发起登记属于后续；当前一期所有发言进入同一个普通群，无匿名发言准入特例。
