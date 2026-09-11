# Townee · Android

当前版本 **1.0.2** 使用专用发布密钥签名并关闭调试模式。[正式签名构建说明](RELEASE-SIGNING.md)。旧调试版需卸载后安装，卸载会清除本地数据。正式签名不保证消除 Play Protect 提示。


基于 Matters 公开 GraphQL 接口实现的非官方 Android 阅读客户端。Kotlin、Jetpack Compose、Material Design 3，最低 Android 8.0（API 26）。

## 已实现的代码

- 账户：原生邮箱密码登录、头像昵称、会话恢复、资料刷新和本机退出；仅通过官方 `emailLogin`、`viewer`、`userLogout` 接口工作。
- 首页：按照网页顺序显示关注、精选、热文、闲聊、活动／主题频道和“还有”；点击顶部菜单按钮或频道名称展开侧栏。
- 发现：与网页相同的推荐文章、闲聊和活动频道查询，分页浏览、刷新、封面与作者信息；关注页显示关注作者的文章。
- 搜索：提交关键词搜索文章，分页、空结果与失败重试；关键词使用 GraphQL variables。
- 阅读：原生文字、标题、图片与链接，可选择复制文字、调整字号、分享原文。
- 书架：正文成功加载后可收藏到本机，重启后保留，可取消收藏；正文离线可读，图片不保证离线可用。
- 设置：跟随系统、浅色、深色；字号与默认语言持久化。
- 繁简：顶部“繁中／简中”按钮即时切换界面和文章显示；设置中选择下次启动默认语言。使用 ICU 字形转换，保存的原文、输入、链接与完整账户名称保持不变。
- 导航：顶部搜索、网页写作、网页通知和账户入口；书架与设置在 Material 3 侧栏中，阅读内容最大宽度 720dp，支持系统返回键与边到边布局。
- 安全保存：密码只在当前请求内使用，不写入 SavedInstanceState、偏好设置或日志；会话以 AES-256-GCM 加密，密钥保存在 Android Keystore，密文放在不参与备份的应用目录。

这是非官方阅读版客户端。原生登录支持已设置密码的 Matters 邮箱账号；注册、找回密码及第三方登录需前往官网。写作、通知、评论和赞赏在浏览器 Custom Tabs 中使用 Matters 网页完成；浏览器会话与原生会话相互独立，本地收藏也不会同步至 Matters。退出本机账户后保留本地书架。HTML 被转换为原生阅读块，复杂表格、音视频嵌入及行内富文本样式尚未完整支持，可打开原文查看。

## 构建与安装

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0，以及可访问 Google Maven / Maven Central / Gradle 的网络。

Windows 推荐步骤：

1. 安装 Android Studio，在 SDK Manager 安装 Android SDK Platform 35 和 Build Tools 35.0.0。
2. 设置 `JAVA_HOME` 为 JDK 17 路径；设置 `ANDROID_HOME` 为 Android SDK 路径，或在项目根目录创建 `local.properties`，写入 `sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk`。
3. 在此目录的 PowerShell 执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

脚本下载固定版本 Gradle 8.11.1，检查官方 SHA-256，然后执行单元测试、Lint 和 APK 构建。它不会安装 JDK 或 Android SDK。也可以自行安装 Gradle 8.11.1 后执行：

```text
gradle testDebugUnitTest lintDebug assembleDebug
```

Android Studio 可直接打开此目录，已包含完整 Gradle Wrapper，Gradle JDK 选择 17。也可在 Windows 执行 `gradlew.bat testDebugUnitTest lintDebug assembleDebug`，macOS / Linux 执行 `sh gradlew testDebugUnitTest lintDebug assembleDebug`。Wrapper 固定为 Gradle 8.11.1，并校验发行包 SHA-256。

成功后的安装包：`app/build/outputs/apk/debug/app-debug.apk`。用文件管理器安装，或连接开启 USB 调试的设备执行：

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

该 APK 为调试签名，仅供开发测试；上架前需独立正式签名、版本管理与发布审核。

## GitHub 自动构建

将本目录内容作为 GitHub 仓库根目录，随附的 `.github/workflows/android.yml` 会在推送、PR 或手动运行时执行测试、Lint 并生成 APK。在 Actions 的 `matters-debug-apk` artifact 中下载。仓库：https://github.com/ostrisand/matters-reader-android 。发布安装包见 Releases；Actions 的调试签名与发布附件可能不同。

## 接口与结构

默认端点：`https://server.matters.town/graphql`。

- `Data.kt`：GraphQL 查询、响应解析、正文阅读块转换。
- `ReaderModel.kt`：请求状态、取消过期请求、分页、收藏与偏好保存。
- `MainActivity.kt`：Compose 界面与 Material 3 主题。
- `Account.kt`、`AccountUi.kt`：登录流程、账号状态和界面。
- `EncryptedSessionStore.kt`、`SessionCipher.kt`：加密会话持久化。
- `LanguageUi.kt`：繁简显示转换与本地化组件。
- `MattersApiTest.kt`：MockWebServer 响应解析、变量编码、错误处理、离线序列化与内容处理测试。
- `AccountTest.kt`、`NavigationLanguageTest.kt`：登录失败、过期恢复、离线退出、凭据传递、重定向保护、加密完整性、频道和繁简转换测试。

API 使用官方 schema 中的 `channels`、`channel`、`search`、`article` 及 `viewer.recommendation`；频道支持 TopicChannel、CurationChannel 和 WritingChallenge。会话使用官方 `x-access-token` 请求头，仅发送到 API；接口请求禁止自动重定向。端点返回的权限或服务错误会显示重试状态，不使用虚构文章填充列表。收藏保存正文的当时版本，重新打开收藏不会自动更新远端内容。官网 `userLogout` 只清除该请求的 Cookie，不撤销其他设备会话；本机退出会删除密文、密钥和内存令牌。

参考：

- [Matters 官网](https://matters.town/)
- [官方服务端及 GraphQL Schema](https://github.com/thematters/matters-server/blob/develop/schema.graphql)
- [官方 Web 客户端](https://github.com/thematters/matters-web)
- [Android Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [AGP 8.9 兼容性](https://developer.android.com/build/releases/agp-8-9-0-release-notes)

## 历史验证状态与后续验收

2026-09-11 已在项目工作目录配置 JDK 17、Gradle 8.11.1 和 Android SDK 35，完成实际构建。新版安装包为Release 附件中的 `Matters-1.0.0-debug.apk`，适用于 Android 8.0 及以上。沿用 0.1.0 的包名和调试签名，versionCode 升至 3，可直接覆盖安装。

- `testDebugUnitTest lintDebug assembleDebug`：BUILD SUCCESSFUL。
- 单元测试与 Lint 结果见`RELEASE-v1.0.0.md`。
- APK：apksigner 签名验证通过，APK Signature Scheme v2，RSA 2048 位调试证书。
- 生产接口：频道、精选、热文、最新、闲聊、活动文章、正文与搜索成功返回真实数据。
- 登录／退出接口参数及字段通过生产 GraphQL 校验（使用 skip 指令未执行登录或退出操作）；匿名 viewer 返回空 ID，按未登录处理。
- Android XML 和 PowerShell 构建脚本语法检查通过。

尚未执行模拟器或真机 UI 验收，也未使用真实账号验证成功登录或 Android Keystore 的设备端持久化。登录行为和加密逻辑已使用模拟接口及 JVM 密码学测试覆盖，用户可在 APP 内输入自己的账号完成实际验收，无需向开发者提供密码。此 APK 是调试签名测试版，不是 Google Play 正式发行版。若默认调试签名目录受限，可自行生成调试密钥，并用 `-PdebugKeystore=密钥绝对路径` 指定，别名和密码使用 Android 默认调试值；不传该参数时使用 Android 标准调试签名配置。

构建成功后应在手机及平板验证：频道和搜索分页、断网重试、快速切换频道、返回阅读列表、收藏后杀进程及离线打开、长文章与图片、200% 字号、TalkBack、深浅主题和旋转屏幕。当前导航页签通过 saved state 恢复，但进程被系统杀死后不恢复正在阅读的具体文章。

本项目没有复用官方品牌图标，启动图标为独立绘制的小镇简笔画。文章内容与图片版权归各原作者。对外发布前请自行确认名称和图标的使用许可，并根据发布时的 Google Play 要求补齐隐私说明及政策适配。
