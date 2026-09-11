# Townee v1.0.2 · 正式签名 APK

本版切换至专用 RSA 3072 位发布密钥，使用 release 构建并关闭调试模式。保留 Townee 名称和小镇简笔画图标，包名 town.matters.reader，versionCode 5。

## 安装

下载 Townee-1.0.2-release.apk。旧版为调试签名，不能直接覆盖安装：请先保留重要文章链接，再卸载旧版并安装本版。卸载会清除本地收藏及登录状态。以后使用同一发布证书的版本可覆盖更新。

正式签名不代表 Google Play 审核通过，也不保证消除 Play Protect 提示。此前网络连接反馈仍待定位，尚未完成真机及真实账户成功登录验收。

## 验证

- testReleaseUnitTest、lintRelease、assembleRelease 均成功。
- 23 项 Release 单元测试通过；Lint 0 错误、13 警告。
- APK Signature Scheme v2 验证通过；APK 无 debuggable 标记。
- 证书 SHA-256：e3ce7ae53563bf1af78059df878018bf93f6ef657a563e342474bba2c70bab96
- APK SHA-256：139ECBCCE0F6380DEA5DFFF740916FE815B0717CA5F21CAC6F6D7E7D03DC5A53

密钥和密码仅保存在开发者本地，不包含在源码或附件中。构建步骤见 RELEASE-SIGNING.md。Actions 仍提供调试测试包，安装本正式签名版请使用本 Release 附件。
