# Home Tunnel Android

Home Tunnel 的 Android 远程管理 App：登录服务端，查看家中设备与连接，创建、编辑、暂停连接并复制公网地址。隧道实际运行在家中的 Windows / macOS / Linux 客户端上。

[项目主页](https://github.com/ZHanry/home-tunnel) · [服务端](https://github.com/ZHanry/home-tunnel-server) · [桌面 / CLI 客户端](https://github.com/ZHanry/home-tunnel-client)

支持 Android 8.0（API 26）及以上，当前发布目标为 arm64-v8a，状态为 Experimental。
APK 用于侧载安装；AAB 是商店上传产物，不可直接安装，也不表示已发布到 Google Play。

## 构建

使用 JDK 17 和 Android SDK 35，在本仓库根目录执行：

```sh
./gradlew --no-daemon test lint assembleDebug
```

Windows 使用 `gradlew.bat`。普通开发与 CI 无需发布密钥；正式版本通过受保护的 `android-release` 环境签名。

## 下载与升级

已有版本：[原项目 5.0.0](https://github.com/ZHanry/home-tunnel/releases/tag/v5.0.0)。
后续版本由[本仓库 Releases](https://github.com/ZHanry/home-tunnel-android/releases) 独立发布。

迁移保留应用 ID `io.github.zhanry.hometunnel`、固定发布证书及递增的 `versionCode`，以支持现有用户升级。
证书指纹见 [release-signing-cert.sha256](release-signing-cert.sha256)。发布流程拒绝使用调试证书替代正式签名。

完整构建和签名说明见 [BUILDING.md](docs/BUILDING.md)，发布见 [RELEASING.md](docs/RELEASING.md)，协议基线见 [compatibility.json](compatibility.json)。

Apache-2.0，见 [LICENSE](LICENSE)。
