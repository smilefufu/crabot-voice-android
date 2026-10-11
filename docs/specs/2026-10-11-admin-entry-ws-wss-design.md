# Android 语音终端接入 Admin 共用端口与 WS/WSS

日期：2026-10-11
状态：定稿待用户书面确认；不授权实现

配套：[Crabot 创建向导与共用入口设计](https://github.com/smilefufu/crabot-docs/blob/main/superpowers/specs/2026-10-11-voice-channel-creation-wizard-design.md)。本文只修订安卓连接、扫码展示及有效 v2 数据兼容；正式字段、控制和版本唯一见 [protocol-voice](https://github.com/smilefufu/crabot-docs/blob/main/protocols/protocol-voice.md)。目前正式协议仍为 v2，确认后由文档仓先更新为 v3，本文不维护另一份 wire schema。

## 1. 目标与边界

用户在 Crabot 后台选择 WS/WSS，完整 HTTP/HTTPS base URL 自动推荐对应方式。手机扫描最终二维码并连接 Admin Web 的同一端口，无须手填地址或单独映射 Voice 端口。直接 WSS 的自签证书自动处理，已有 HTTPS 外部入口沿系统证书验证；手机不配置 Provider、证书购买、反向代理或另一套连接默认。

默认关麦、手机本地唤醒、端侧分段/声纹、联系人/Friend 绑定、陌生人普通群语义、整轮准入及真实播放回执沿用当前正式协议。本文不改变模型、采音或业务权限，不增加后台采音、私聊、多渠道切换、跨设备声纹迁移或桌宠实现。

当前 APK 只接受 WSS 和 `/voice`，TLS 只校验旧 Voice 证书 pin，Manifest 禁止明文；加密文件也将协议版本与声纹兼容绑定在一起。不能仅把 URL 改为 WS 就交付，需要一起修改连接与存储兼容。

## 2. 扫码、URL 校验与界面

新版只接受整数协议 3 的二维码及握手，不同时协商 v2。二维码决定实际 WS/WSS，手机不按网络类型或其它偏好自动重写 scheme、hostname、端口、代理前缀或实例 ID。拒绝未知协议、过期码、空/非法实例、userinfo/query/fragment、无效 scheme/端口、非规范路径、与 `channel_id` 不符的最终 `/ws/voice/<encoded_channel_id>` 路径。允许该路径前有规范代理前缀；不能把任意 WebSocket 地址当作语音终端。

扫码后展示渠道、实际连接 URL 与“WS（不加密）/WSS（加密）”，再走现有两端核对码及 human Admin 确认。WS 的事实说明是音频及连接凭据明文传输，不增加第二个手机协议选择或额外确认弹窗。扫码、连接及保存成功都保持关麦。

WS 使用普通 WebSocket 客户端；WSS 按 §3 校验证书。WS 二维码含 TLS 指纹属于不一致并拒绝；WSS 指纹缺省时只允许系统信任，有字段时须为规范 SHA-256。禁用 HTTP/HTTPS 重定向、网络自动重试、WSS→WS 降级和自动换入口。连接失败停留并显示网络/证书/版本/凭据错误，不清空声纹，也不重发旧音频。

## 3. TLS 信任与 Android 明文策略

WSS 同时支持两种严格信任依据，均绑定已扫码 URL：

1. 实际叶证书在有效期内且 SHA-256 与扫码 pin 精确一致，接受该二维码指定 host 的 Crabot 自签入口；不要求将证书安装进 Android 系统。
2. 不满足 pin 路径时，完整执行系统 CA 链和 URL hostname 校验；用于已有 HTTPS 外部入口，正常 CA 证书续期不需要重配。

两条都不成立则连接失败；无 pin 只执行第 2 条。不能因公网证书不等于内部 Admin pin 就误拒绝，也不能把“有 pin/自签”实现成 trust-all。过期证书两条都不接受；CA 路径必须验证 hostname。自签 pin 属于扫码建立的精确信任，公有 CA 信任不替代终端凭据。

Android 平台需要允许 WS 的明文网络连接。因为用户可以输入运行时 IP/域名，Manifest/NetworkSecurityConfig 允许客户端发起明文；应用连接入口再严格限制为已校验/配对且明确选择 WS 的语音 URL。不增加普通 HTTP API、WebView 或其它明文请求；WSS 始终使用 TLS。此平台配置不意味着应用可以将 WSS 自动降级。

连接仍使用既有 5 秒预算、有限队列和心跳；不新增无期限证书探测或后台连接恢复。退后台/锁屏/断连释放采音与播放，回到前台重连仍关麦。

## 4. v2 配对与加密声纹兼容

终端 wire 版本从 2 升为 3，分段和 embedding 模型未变。不能用 wire 版本变化判定 CAM++ 向量失效，不能让用户通过卸载或“清除本机数据”完成正常升级。

将内部数据格式与 wire 版本分开：加密 JSON 使用 `storage_version:1`；保存的配对含自己的协议版本、URL、渠道和专用凭据，向量保留原 registration_id/model_id/数值。Keystore alias、AES-GCM、AtomicFile 文件路径与隐私行为保持；不复制一份 plaintext 迁移文件。

有效旧 v2 文件读取后保留原文件和向量，标记“连接入口已升级，请从 Admin 重新扫码”。不连接旧 URL、不自动生成 v3 凭据、不自动删除数据。后台先按既有规则撤销旧终端凭据，再生成 v3 二维码；手机可以扫描**同一 channel_id** 的新码。只有 human 确认、收到新凭据且成功原子写入时，才升级存储并保留原向量，不把扫描/网络成功当作数据升级完成。

原有 v3 配对的同渠道地址/协议/自签证书更新也沿“撤销并重新扫码”处理，保留向量。不同 channel_id 不能静默覆盖已有配对/向量；拒绝并说明需先使用现有明确清除动作。未知旧 WIP/实验 App 不自动迁移；FuFu 的实验数据不受本次安装影响。

JSON 校验、迁移或写盘失败不覆盖原有效文件，不清空向量或报告已保存配对。若后台已签发凭据但本机写入失败，关闭连接并明确提示 Admin 撤销后重新扫码；不拿旧凭据或未知本机状态假装成功。新文件提交后才更新内存。模型不兼容、Keystore 丢失等既有不可恢复条件仍按正式协议处理，不承诺恢复无法解密的向量。

首次成功 v3 hello 仍核对后端现行登记 ID/model/revision，处理正式撤销后才识别；升级不擅自激活登记、改 Friend 绑定或重提旧 turn。握手不兼容和未完成创建提示保持可见，手机不能绕过后台 draft 门禁。

## 5. 复用边界与协议影响

- `TerminalConnection` 继续负责单连接生命周期和帧回调；不复用管理后台 Chat/JWT，不保存 human、runtime 或 Provider 凭据。
- 扫码/确认/终端 credential 的既有语义不变；后台决定连接 URL，手机只验证和执行。没有手机侧 Provider/网络配置页。
- `SecureStore` 复用加密及原子落盘能力，兼容有效 v2 只为保持同渠道声纹；不将“忘记本机数据”当迁移入口，不恢复旧网络交互。
- 正式二维码的可选 pin、Admin 路径、协议 3、控制/音频要求只在 `protocol-voice` 更新。Android 不维护后端草稿 REST 或权限类型的副本。

## 6. 验证与交付

| 步骤 | 验证方式与通过条件 |
|---|---|
| 解析/URL | WS/WSS、中文实例编码、有效端口、代理前缀通过；旧 v2/过期码、其它实例、userinfo/query/fragment、路径绕过、非法 pin/协议组合拒绝。手机不重写或降级 URL。 |
| 连接/TLS | 受控服务验证 WS 无 TLS、直接自签 pin 成功、CA/hostname 成功；错误 pin且无系统信任、过期、CA 路径 hostname 不符、无 pin 的自签、重定向均失败。CA 证书更换但链/hostname 有效仍可连。 |
| 加密兼容 | 用独立测试数据模拟有效 v2 与 v3 同渠道重配，逐值核对向量/registration_id/model_id 保留；旧 URL 不自动连，新凭据原子写后才生效；坏 JSON/写盘失败不清除有效旧数据；不同渠道不静默复用。 |
| 生命周期/失败 | 配对、失联、证书错误、退后台/锁屏、重连均关麦；不恢复旧上传/播放。持久化失败显示真实恢复动作，不报告已连接。 |
| 实际真机 | 保留现有安装数据，不卸载、不用真实手机跑会重置数据的 instrumentation。与同一 Admin 端口分别通过 LAN WS、直接 WSS 扫码/核对/有限录放音，核实旧声纹仍可用。外部 CA 用受控入口联调，不用模拟替代真实播放。 |
| 发布 | 记录 APK/模块/协议 3 及未变的模型版本；通过相关测试与完整本次 diff review 后直接提交、推送 main，不走 PR。真机条件不足的项目保留未验收状态。 |

## 7. 自审与确认

本次边界限于新入口、WS/WSS 验证及同渠道有效数据兼容；UI 没有第二个协议决策，系统 CA 与 pin 不混淆，wire 升级不清声纹，旧业务/身份不重新设计。正式协议更新和实现都须在本稿与后端配套稿获得书面确认后进行。

外部依据：[Android 网络安全配置](https://developer.android.com/privacy-and-security/security-config) 说明系统信任与明文策略；[RFC 6455](https://www.rfc-editor.org/rfc/rfc6455) 定义 WS/WSS。扫码 pin 与系统 CA 的组合及 v2 数据保留是本项目设计。
