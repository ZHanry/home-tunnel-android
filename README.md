<div align="center">
  <img src="docs/assets/HomeTunnel.svg" alt="Home Tunnel" width="72" height="72">
  <h1>Home Tunnel for Android</h1>
  <p><strong>随时管理家庭设备与连接</strong></p>
  <p><a href="https://github.com/ZHanry/home-tunnel-android/releases/latest"><img src="https://img.shields.io/badge/release-6.0.1-176653" alt="Release 6.0.1"></a> <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0"></a></p>
  <p><a href="README.en.md">English</a> · <a href="https://zhanry.github.io/home-tunnel/">项目网站</a></p>
</div>

6.0 正式版使用全新的“总览、设备、连接、账户”导航。手机负责远程管理；隧道运行在家庭电脑或 NAS 上。

## 安装

从 [Releases](https://github.com/ZHanry/home-tunnel-android/releases/latest) 下载 `HomeTunnel-Android-6.0.1-arm64-v8a.apk`，在 Android 8.0+ 的 arm64 设备上安装。APK 使用项目持续维护的发布证书签名，可覆盖安装同证书版本。下载页仅保留 APK。

6.0.1 修复登录时的 `Caller-provided IV not permitted` 错误，升级无需先卸载或清除应用数据。

## 使用

1. 填写自己的 Home Tunnel 控制台 HTTPS 地址与账号。
2. 在“设备”中选择电脑或 NAS，查看该设备上的连接。
3. 创建连接时明确选择目标设备，填写该设备可以访问的服务地址。
4. 在“连接”中按名称或地址搜索，编辑配置或复制公网地址。

`127.0.0.1` 指所选电脑或 NAS。退出手机账号只结束手机管理会话，家庭隧道继续运行。主题跟随系统，并支持简体中文与英文。

## 开发

使用 JDK 17、Android SDK 35 和项目 Gradle Wrapper。运行 `./gradlew test lint assembleDebug`。正式 APK 由 GitHub Actions 使用发布环境中的签名材料构建；私钥不进入仓库。

[构建说明](docs/BUILDING.md) · [发布流程](docs/RELEASING.md) · [版本说明](docs/RELEASE_NOTES.md) · [安全报告](SECURITY.md) · [项目入口](https://github.com/ZHanry/home-tunnel)

<img src="docs/assets/overview.jpg" alt="Home Tunnel 6.0 Android" width="320">
