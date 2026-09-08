<div align="center">
  <img src="docs/assets/HomeTunnel.svg" alt="Home Tunnel" width="80" height="80">
  <h1>Home Tunnel Android</h1>
  <p><strong>用手机管理家中的设备与连接</strong></p>
  <p>
    <img src="https://img.shields.io/badge/status-internal_testing-92400e" alt="Status: internal testing">
    <a href="https://github.com/ZHanry/home-tunnel-android/actions/workflows/ci.yml"><img src="https://github.com/ZHanry/home-tunnel-android/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0 license"></a>
  </p>
  <p><a href="README.en.md">English</a> · <a href="https://zhanry.github.io/home-tunnel/">项目网站</a></p>
</div>

Home Tunnel 的 Android 管理 App。连接自己的服务端，查看家中设备、管理连接并复制公网访问地址；隧道由家中的电脑或 NAS 运行。

> **内部测试阶段。** 当前重点是账号登录、设备与连接管理、网络异常恢复及真实设备验证。暂未提供面向生产使用的稳定版，也未宣称已上架应用商店。

[项目总览](https://github.com/ZHanry/home-tunnel) · [服务端](https://github.com/ZHanry/home-tunnel-server) · [运行隧道的 GUI / CLI 客户端](https://github.com/ZHanry/home-tunnel-client)

## 当前功能

- 使用自己的服务端地址和账号登录。
- 查看账号下的家庭设备和连接状态。
- 在已有设备上创建、编辑、暂停或删除连接；当前以 HTTP / HTTPS 管理流程为测试重点。
- 复制公网地址，并在登录、刷新或保存失败时显示反馈。

手机负责管理，电脑或 NAS 负责转发。测试前请先搭建服务端，并用桌面或 CLI 客户端注册至少一台设备。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 运行系统 | Android 8.0 / API 26 及以上 |
| 优先验证设备 | arm64 Android 手机或平板 |
| JDK | 17 |
| Android SDK | Platform 35、Build Tools 35 |
| 构建方式 | 仓库内的 Gradle Wrapper；可通过 Android Studio 打开 |

应用使用 Kotlin 与 Jetpack Compose。构建版本以仓库内配置为准。

## 构建并安装调试版

```sh
git clone https://github.com/ZHanry/home-tunnel-android.git
cd home-tunnel-android
./gradlew --no-daemon test lint assembleDebug
```

Windows 使用 `gradlew.bat`。调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

可以通过 Android Studio 安装到设备，也可以在已配置 adb 的环境执行：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug 应用 ID 带 `.debug` 后缀，使用调试签名；普通开发和 CI 无需发布密钥。不同开发机生成的调试证书可能不同，安装时应核对目标构建。

## 第一次使用

1. 在 Web 控制台创建测试账号，并让家中设备完成注册。
2. 打开 App，填写服务端 HTTPS 根地址，例如 `https://console.tunnel.example.com`。
3. 登录后查看设备和连接，尝试创建一个 HTTP 测试连接。
4. 验证复制地址、编辑、暂停、断网恢复以及会话过期后的行为。

## 测试与签名

`test` 执行单元测试，`lint` 检查 Android 工程，`assembleDebug` 构建调试 APK。真实设备上的登录、旋转屏幕、后台恢复、断网与重试仍需手动验证。

签名测试包通过本仓库受限的 `android-release` 环境构建。应用 ID 为 `io.github.zhanry.hometunnel`，公开证书指纹记录在 [release-signing-cert.sha256](release-signing-cert.sha256)。APK 可安装；AAB 用于后续分发准备，不能直接安装。

完整说明见 [构建指南](docs/BUILDING.md) 和 [测试发布流程](docs/RELEASING.md)。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/src/main/` | 界面、网络请求、会话、状态与资源 |
| `app/src/test/` | 单元测试 |
| `app/build.gradle.kts` | Android 与应用构建配置 |
| `gradle.properties` | 构建版本与 Gradle 配置 |
| `contracts/` | API 协议测试夹具及来源校验 |

[贡献指南](CONTRIBUTING.md) · [安全报告](SECURITY.md) · [开发记录](CHANGELOG.md) · [Apache-2.0](LICENSE)
