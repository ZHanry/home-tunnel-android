# Home Tunnel Android

**在手机上管理自己的服务器与家庭设备**

[![Stable release](https://img.shields.io/github/v/release/ZHanry/home-tunnel-android?label=stable)](https://github.com/ZHanry/home-tunnel-android/releases/latest) [![License Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

[English](README.en.md) · [项目网站](https://zhanry.github.io/home-tunnel/) · [下载](https://github.com/ZHanry/home-tunnel/blob/main/docs/DOWNLOADS.md) · [快速开始](https://github.com/ZHanry/home-tunnel/blob/main/docs/GETTING_STARTED.md)


Android 8.0+ 的 Home Tunnel 远程管理客户端。手机负责管理，实际隧道持续运行在
Windows、macOS、Linux 电脑或 NAS 上。

当前版本为 **10.0.0**，使用与 10.0.0 桌面端同源、经核验的 arm64-v8a / x86_64 原生 SDK。远控支持经授权的画面与输入、系统声音播放、剪贴板、文件、显示器选择和有界重连，仅使用 UDP 直连；服务端 TURN 中继用于浏览器控制端。麦克风回传不可用。构建、签名和开发版截图审核不等于最终安装包或真机验收，未测项目及负责人豁免见 [发行说明](docs/RELEASE_NOTES.md) 和 [能力与验证边界](docs/REMOTE_DESKTOP.md)。

[下载 10.0.0 正式 APK（arm64-v8a）](https://github.com/ZHanry/home-tunnel-android/releases/download/v10.0.0/HomeTunnel-Android-10.0.0-arm64-v8a.apk) · [x86_64 APK](https://github.com/ZHanry/home-tunnel-android/releases/download/v10.0.0/HomeTunnel-Android-10.0.0-x86_64.apk) · [Release、校验和与签名证据](https://github.com/ZHanry/home-tunnel-android/releases/tag/v10.0.0)

## 登录后能做什么

- 加密保存最多 20 个服务器/账号，切换不混用连接、请求或缓存。
- TOTP/恢复码登录、MFA 设置、会话撤销和为新电脑生成一次性接入码。
- 按服务端能力创建 HTTP/TCP/UDP 及 SSH/RDP/RTSP 预设；管理员带版本编辑端口池。
- 设备搜索、标签和收藏；最多 50 条连接批量暂停/恢复，确认范围并逐项报告。
- 管理账号、系统状态和审计；复制脱敏的管理端诊断报告。

隧道管理保留对 **7.0.0+** 服务端的兼容；远控应搭配公布对应能力的 **10.0.0** 服务端与桌面端。输入自己的 HTTPS 地址后登录，选择已登记设备，再创建连接。
纯管理登录无需 FRPS 证书字段；HTTPS 身份仍正常校验。已有单账号状态自动迁移，
继续使用 AndroidKeyStore；不要为升级而卸载应用。

## 构建

JDK 17、Android SDK 35，使用仓库内已锁定的 Gradle wrapper：

```sh
./gradlew test lint assembleDebug assembleDebugAndroidTest
python3 scripts/check-repository.py
```

包含 JNI 的构建还需 NDK 27.2.12479018、CMake 3.22.1。`python scripts/build-remote-native.py` 提供开发/CI 使用的双 ABI 安全核心；远控发行必须从客户端正式封存的 Release 导入固定摘要的 arm64 Controller SDK。Gradle 显式选择对应 profile，详细步骤见[预览构建说明](docs/REMOTE_DESKTOP.md)和[发行步骤](docs/RELEASING.md)。

正式发行必须使用既有 Android 签名身份，证书摘要固定在 `release-signing-cert.sha256`。
CI 在 Android API 26 和 35 上检查 KeyStore 与管理界面；发行保留 APK、AAB、
校验清单、SBOM 和签名/安装检查证据。

[功能与升级](docs/PLATFORM_FEATURES.md) · [API 契约](contracts/README.md) · [项目入口](https://github.com/ZHanry/home-tunnel) · [服务端部署](https://github.com/ZHanry/home-tunnel-server/blob/main/docs/SELF_HOSTING.md)
