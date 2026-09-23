# Home Tunnel Android

**在手机上管理自己的服务器与家庭设备**

[![Stable 7.0.0](https://img.shields.io/badge/stable-7.0.0-176653)](https://github.com/ZHanry/home-tunnel-android/releases/tag/v7.0.0) [![License Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

[English](README.en.md) · [项目网站](https://zhanry.github.io/home-tunnel/) · [下载](https://github.com/ZHanry/home-tunnel/blob/main/docs/DOWNLOADS.md) · [快速开始](https://github.com/ZHanry/home-tunnel/blob/main/docs/GETTING_STARTED.md)


Android 8.0+ 的 Home Tunnel 远程管理客户端。手机负责管理，实际隧道持续运行在
Windows、macOS、Linux 电脑或 NAS 上。

当前源码为 **8.0.0**：新增控制端安全身份、配对界面和同源 JNI 核心。发行包要求锁定客户端正式 Release 的原生 SDK，支持 VP8/Surface 与键鼠、文本的 UDP 直连实现；Android 真机解码与输入尚待验证。音频、麦克风回传、剪贴板/文件传输、多会话界面和 AV1/HEVC 尚不可用，应用如实显示状态。已有隧道管理功能继续可用，下方保留 7.0 历史下载。[能力与验证边界](docs/REMOTE_DESKTOP.md)。

[下载 7.0.0 正式 APK（arm64-v8a）](https://github.com/ZHanry/home-tunnel-android/releases/download/v7.0.0/HomeTunnel-Android-7.0.0-arm64-v8a.apk) · [Release 与签名证据](https://github.com/ZHanry/home-tunnel-android/releases/tag/v7.0.0)

## 登录后能做什么

- 加密保存最多 20 个服务器/账号，切换不混用连接、请求或缓存。
- TOTP/恢复码登录、MFA 设置、会话撤销和为新电脑生成一次性接入码。
- 按服务端能力创建 HTTP/TCP/UDP 及 SSH/RDP/RTSP 预设；管理员带版本编辑端口池。
- 设备搜索、标签和收藏；最多 50 条连接批量暂停/恢复，确认范围并逐项报告。
- 管理账号、系统状态和审计；复制脱敏的管理端诊断报告。

服务端需为 **7.0.0**。输入自己的 HTTPS 地址后登录，选择已登记设备，再创建连接。
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
