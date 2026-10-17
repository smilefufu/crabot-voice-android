# 联合升级前置验证

日期：2026-10-17。产品源码commit：`c6c0a6597f87fd0d175dfcb5eb7aaa22783fa2b2`；APK为0.6.1 / versionCode15 / wire7。本记录提交只维护文档，不改变产品源码或APK。

## 已执行

- 使用JDK17执行`:app:testDebugUnitTest --rerun --no-daemon`，XML逐项统计23通过、0失败、0错误；未用Gradle的UP-TO-DATE作为本次执行通过。
- `:app:assembleDebug`和`:app:assembleDebugAndroidTest`成功，两个APK均通过`apksigner verify`。签名一致性仍需连接用户手机后核对，未卸载或清除数据。

| 产物 | 字节数 | SHA256 |
|---|---:|---|
| app-debug.apk | 54616451 | dc0850120b980e85920767f453aa1a53245bcfc98f61db88600e1c0c87825847 |
| app-debug-androidTest.apk | 1305967 | 414cc8c16e7dc5926f8651056f13b8f613e983305a251f57409ffe24a4ab9608 |

## 验证边界及剩余工作

当前ADB没有设备；本轮没有执行仪器测试、采集麦克风、安装APK或改变用户配对与声纹。此前隔离模拟器和公开音频验证仍按原记录解读，不冒充本次真机结果。

现有前置包包含设备所有者与任务反馈候选，不包含全语音声纹管理。管理工具、可信任务控制、三段自动引导、普通好友持久绑定及原请求结果续办仍待跨端协议和实现；它不是整组最终升级包。模型可见的新增说明在对话单独确认，未应用未确认文字。

整组完成后重新核定协议、版本与最终APK哈希，按[后端联合升级准备](https://github.com/smilefufu/crabot-docs/blob/main/superpowers/plans/2026-10-17-voice-joint-upgrade-readiness.md)统一升级。现场保留原配对、FuFu声纹和稳定人物引用，默认保持关麦；分别验收真实首音频、异步任务完成回报及生命周期。Master引导另一人登记、不同人分别识别须两位真人完成，单人自演不算多人验收。
