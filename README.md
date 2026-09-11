# Matters 阅站 · Android

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



构建成功后应在手机及平板验证：频道和搜索分页、断网重试、快速切换频道、返回阅读列表、收藏后杀进程及离线打开、长文章与图片、200% 字号、TalkBack、深浅主题和旋转屏幕。当前导航页签通过 saved state 恢复，但进程被系统杀死后不恢复正在阅读的具体文章。

本项目没有复用官方品牌图标，启动图标为独立绘制的字母 M。文章内容与图片版权归各原作者。对外发布前请自行确认名称和图标的使用许可，并根据发布时的 Google Play 要求补齐隐私说明及政策适配。
