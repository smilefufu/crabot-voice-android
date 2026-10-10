# crabot-voice-android

Crabot 的原生 Android 语音终端，负责有限采音、播放、本地唤醒、说话人分段与端侧声纹匹配。

当前处于设计阶段，尚无产品 APK。第一期设计与验收依据：

- [Android 语音终端第一期 spec](docs/superpowers/specs/2026-10-10-android-voice-terminal-v1-design.md)（已确认，进入实现）。
- [Voice Channel 配套后端 spec](https://github.com/smilefufu/crabot-docs/blob/main/superpowers/specs/2026-10-10-voice-channel-v1-design.md)（已确认，进入实现）。
- [开发约定](AGENTS.md)：安卓端 spec 与实施计划在本仓维护。
- [跨端正式协议目录](https://github.com/smilefufu/crabot-docs/tree/main/protocols)：共同接口的唯一契约来源；语音契约见 `protocol-voice.md`。

后端 Provider 配置、ASR/TTS 服务接入、身份准入和业务处理由 Crabot 负责。客户端不持有 Provider 或 Admin 凭据。当前实验与来源见[端侧研究记录](https://github.com/smilefufu/crabot-docs/blob/main/superpowers/research/voice-scenario-b-poc/README.md)，实验工具不作为产品 APK。
