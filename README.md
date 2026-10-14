# crabot-voice-android

Crabot 的原生 Android 语音终端，负责有限采音、播放、本地唤醒、说话人分段与端侧声纹匹配。

已实现第一期客户端，当前候选为 `0.5.2` 调试 APK（wire v5，手机声纹管理及短句实际比对），仍处于联调与验收阶段。仅支持 Android 10+、ARM64、内置麦克风/扬声器和 App 前台；默认关麦，播放时关麦、点按打断。FuFu 自然短句的识别、好友归属与播报已通过一轮真机复核；多人、陌生人误识率、唤醒误触发、整体延迟、回声及功耗尚未验收。

设计与验收依据：

- [身份与联系人修订 spec](docs/specs/2026-10-11-android-voice-identity-revision-design.md)及[后端修订](https://github.com/smilefufu/crabot-docs/blob/main/superpowers/specs/2026-10-11-voice-channel-contacts-and-group-revision-design.md)于 2026-10-11 确认并实现：群内陌生人可发言、声纹关联稳定渠道联系人、移除云/自建分类。身份 unknown/uncertain 保留全部内容；重叠或无效分段仍整轮失败。
- [Android 语音终端第一期 spec](docs/specs/2026-10-10-android-voice-terminal-v1-design.md)（已确认，进入实现）。
- [Voice Channel 配套后端 spec](https://github.com/smilefufu/crabot-docs/blob/main/superpowers/specs/2026-10-10-voice-channel-v1-design.md)（已确认，进入实现）。
- [开发约定](AGENTS.md)：安卓端 spec 与实施计划在本仓维护。
- [短句声纹修订](docs/specs/2026-10-14-short-utterance-speaker-identification-design.md)已确认；取消日常提取的固定时长门槛，登记要求和匹配条件保持。设备验证见[0.5.2记录](docs/verification/2026-10-14-short-utterance-speaker-identification.md)。
- [跨端正式协议目录](https://github.com/smilefufu/crabot-docs/tree/main/protocols)：共同接口的唯一契约来源；语音契约见 `protocol-voice.md`。

后端 Provider 配置、ASR/TTS 服务接入、联系人与好友解析、普通群权限和业务处理由 Crabot 负责。客户端不持有 Provider 或 Admin 凭据。当前实验与来源见[端侧研究记录](https://github.com/smilefufu/crabot-docs/blob/main/superpowers/research/voice-scenario-b-poc/README.md)，实验工具不作为产品 APK。

## 构建

准备 JDK 17、Python 3.11+ 和 Android Command-line Tools。设置安装环境提供的 `JAVA_HOME`、`ANDROID_HOME`，不要提交 `local.properties` 或本机路径。SDK 依赖为：

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.22.1"
python3 tools/prepare-models.py
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
```

模型脚本从官方发行源下载固定版本，先校验 archive SHA-256，再校验所有模型与 `model-files.json` 一致。只复制所需库/模型，不读取研究目录或录音；下载缓存、模型与 native 库均忽略入 Git。第一次下载需联网，已验证缓存可复用。

Gradle Wrapper 固定 8.11.1 并核验 distribution SHA-256；AGP 8.9.2、Kotlin 2.1.20、NDK/CMake 及依赖固定版本，Maven artifact checksum 由 `gradle/verification-metadata.xml` 校验。正常构建不要重新生成该文件来绕过失败。

输出为 `app/build/outputs/apk/debug/app-debug.apk`，使用本机构建工具的 debug key 签名，私钥不入库；这不是应用商店或正式发布签名。ABI 为 `arm64-v8a`。端侧组件与许可见[第三方组件](docs/third-party-components.md)，不包含 ASR/TTS 权重。

## 安装与连接

仅在选定手机安装产品 APK：

```sh
"$ANDROID_HOME/platform-tools/adb" -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

配套后端在 [PR #206](https://github.com/smilefufu/crabot/pull/206)，当前协议 v5。创建向导与接入设计于 2026-10-11 确认，见[Admin WS/WSS 接入 spec](docs/specs/2026-10-11-admin-entry-ws-wss-design.md)。后台从“新建语音渠道”配置 ASR/TTS API、手机连接及现有群权限，检查成功后启用；退出保留草稿，可继续原实例。

手机设置页点“扫描配对二维码”，扫描向导中的一次性二维码，使用同一 Admin Web 端口上的 WS/WSS。地址默认使用 tmp-page BASE_URL 的完整 HTTP/HTTPS URL，允许局域网、有效端口和代理路径前缀。HTTP/HTTPS 推荐 WS/WSS，用户可显式选择；WS 为明文，WSS 支持有效二维码证书 pin 或系统 CA+hostname，失败不会自动切换协议。核对两端代码后在 Admin 确认，配对和重连保持关麦。

有效保存的v3/v4配对可保留数据升级，用v5握手续连，无需重新扫码。历史v2配对保留加密文件、Keystore和同渠道声纹；旧地址不再自动重连，先在Admin撤销旧终端，再扫描同一渠道v5二维码。只有新凭据原子写入成功后更新状态；跨渠道重配或未知/损坏存储明确拒绝并保留文件，清除必须由用户显式执行。实验App的FuFu声纹不自动迁移。

手机或Admin可发起现场声纹登记，录三段不同内容、每段3–5秒；首次成功确认后才形成正式渠道联系人，再在Admin绑定已有好友。普通群启用仍要求名册有Master好友，陌生人和已登记者共用群权限；unknown/uncertain片段不会被删除后只执行其它人的话。改名/重录保持联系人和绑定，撤销声纹保留联系人与历史。
本地唤醒需现场显式开启，当前模型支持字典可表达的 2–32 个汉字。自建 ASR/TTS 以接口接入 Crabot；云服务的音频留存由对应服务规则决定。未知或争议片段不会被删除后只执行其它人的话。

日常对话先点一次“开启语音待机”，随后喊配置的唤醒词，看到“我在听”后说内容，停顿后自动结束并等待回答。播报整轮结束后有8秒直接接话窗口，不需每轮按键。播放时可点“打断并说话”，底部“静音”关闭麦克风及播放；第一次使用按系统提示授予麦克风权限。没有声纹也能在已启用的群里对话，未识别者按陌生人处理，不能确认时显示具体原因。

配对、重连、手动说话及技术诊断位于设置页；“声纹与联系人”提供现场新增、改名、重录及撤销样本，后台确认后更新目录。后台发起登记也须手机现场点按采样；退后台/断线后重新连接保持关麦。

## 验证

JVM 测试执行有限 PCM frame 和分段负例检查。仪器测试含公开合成音频的实际 JNI 推理、Keystore、TLS 证书 pin 和配对心跳：

```sh
./gradlew :app:assembleDebugAndroidTest --no-daemon
"$ANDROID_HOME/platform-tools/adb" -s DISPOSABLE_EMULATOR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s DISPOSABLE_EMULATOR_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s DISPOSABLE_EMULATOR_SERIAL shell am instrument -w -e disposable_voice_test true com.crabot.voice.test/androidx.test.runner.AndroidJUnitRunner
```

只在新建的、无保留数据的禁音频模拟器运行上面的仪器流程：Keystore 测试会清除测试 App 的配对/登记数据，代码也要求模拟器与显式参数。不要把通用 `connectedAndroidTest` 指向已配对手机。

新增的NativeShortIdentityTest依赖官方公开文件，先按[来源清单](docs/research/2026-10-14-short-speaker-evidence/sources.json)取得文件，再运行`python3 docs/research/2026-10-14-short-speaker-evidence/prepare-native-test-fixtures.py --source-dir PUBLIC_AUDIO_DIR --output .model-cache/short-speaker`。原始/派生WAV不入仓，仅进入测试APK；单独的短句测试不读取真实声纹/配对或开启麦克风。

2026-10-12 已保留数据安装 0.3.2 至 OnePlus 7 Pro，主页面与扫码页面保持竖屏；11 项 JVM 与一项只读真机方向回归通过。手机扫码、现场核对确认及局域网 Admin 同端口 WSS 连接已通过，连接后保持关麦；有限录放音仍待验收。扫码修订证据见[真机记录](docs/verification/2026-10-12-android-pairing-portrait.md)。

0.3.3 已保留数据升级至同一手机，简化日常页面，11 项 JVM 与产品构建通过；手机重连后关麦，主操作与折叠区已实际核对。真实问答/播放仍待现场测试，见[界面验证记录](docs/verification/2026-10-12-android-ui-simplification.md)。

现场未检测到人声已定位到该手机的 VoIP 采音路径：同次真人对照中 VOICE_COMMUNICATION 无人声、MIC 有人声。0.3.5 改用 MIC；来源配置回归先失败后通过，11 项 JVM、公开音频 VAD 和构建/签名验证通过，手机保留数据升级。产品按钮录音到 ASR/回答播放仍需真实验收。见[采音修复记录](docs/verification/2026-10-12-android-input-diagnostics.md)。

v4 标签回复与增量播放见[本次验证记录](docs/verification/2026-10-13-incremental-playback-v4.md)，覆盖安装保留有效 v3 配对和本机声纹，无需重新扫码。实际验证与剩余验收另见[协议 v3 历史验证记录](docs/verification/2026-10-11-android-voice-v3.md)；[0.1.0 历史记录](docs/verification/2026-10-11-android-voice-v1.md)保留旧构建证据。本次仪器包已编译，但没有可用的专用测试设备，不能将旧版测试算作新版本通过。文件/模拟器测试不替代手机采音、真实扬声器输出或真人场景 B；真实标准沿用已确认 spec。

0.4.1 修复 wire 升级后有效 v3 保存配对被误判为不兼容的问题；新握手仍为 v4，覆盖安装无需重新扫码。见[升级重连修复验证](docs/verification/2026-10-13-v4-pairing-upgrade-fix.md)。
