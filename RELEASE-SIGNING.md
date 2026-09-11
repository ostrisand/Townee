# Release 签名构建

从 v1.0.2 起，GitHub Release 的 APK 使用专用 RSA 3072 位发布证书，关闭调试模式。签名不表示 Google Play 审核通过，也不保证 Play Protect 不提示。

构建需要 JDK 17、Android SDK 35，以及以下环境变量：

- `TOWNEE_KEYSTORE`：发布密钥库的绝对路径。
- `TOWNEE_STORE_PASSWORD`：密钥库密码。
- `TOWNEE_KEY_PASSWORD`：密钥密码。

密钥别名为 `townee`。在本地安全设置环境变量后执行：

```text
gradlew.bat testReleaseUnitTest lintRelease assembleRelease
```

安装包位于 `app/build/outputs/apk/release/app-release.apk`。未提供签名配置时不能生成已签名的 Release 包。现有 Actions 工作流仍构建调试测试包，不是 Release 附件。

发布密钥和密码不放入仓库。请在安全的独立位置备份它们，后续更新保持相同包名与发布证书，并递增 versionCode。

旧调试版与正式签名版证书不同，无法直接覆盖安装。安装本版前须卸载旧调试版，卸载会清除本地收藏和登录状态。已有正文收藏不具备导出恢复功能，请先保留所需文章的原文链接。
