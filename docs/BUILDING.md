# Android 构建与设备测试

## 工具

使用 JDK 17、Android SDK Platform 35、Build Tools 35，以及仓库内的 Gradle Wrapper。
运行目标为 Android 8.0 / API 26 及以上，当前优先验证 arm64 设备。

## 调试构建

```sh
./gradlew --no-daemon test lint assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `gradlew.bat`。Debug 应用 ID 带 `.debug` 后缀，使用调试签名，不需要发布密钥。
安装到设备前确认 adb 连接的目标；不同开发机的调试证书可能不同，不能把签名不匹配理解为网络或服务端错误。

## 签名安装包

签名所需的本地环境变量如下：

```text
ANDROID_RELEASE_STORE_FILE
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD
```

```sh
./gradlew --no-daemon test lint assembleRelease bundleRelease
```

签名信息不足时 release variant 保持未签名；它不会自动使用调试证书。
受限的 GitHub `android-release` 环境使用对应的签名配置构建，并校验产物。
应用 ID 为 `io.github.zhanry.hometunnel`，证书指纹记录于 `release-signing-cert.sha256`。

APK 可直接安装；AAB 用于分发准备，不是手机安装包。存在 AAB 不代表已经上架应用商店。
版本发布流程见 [RELEASING.md](RELEASING.md)。

## 真实设备检查

发布前的 Quality Gate 会在 Android 8 / API 26 和 Android 15 / API 35 模拟器上运行实际 AndroidKeyStore 与管理界面检查。密钥库检查覆盖已有密钥保存、冷启动读取、随机 IV、会话更新、旧加密格式兼容、篡改拒绝和退出后重新登录。

本地可先运行 `./gradlew assembleDebug assembleDebugAndroidTest`，再对明确选定的模拟器运行：

```sh
python scripts/run-instrumentation.py --serial emulator-5554
```

结果保存在 `instrumentation-evidence/`。该命令仅使用调试应用与夹具账号，不连接真实控制台。它直接运行 AndroidJUnitRunner，不能用普通 JVM 单元测试代替。

- 使用服务端 HTTPS 根地址登录，确认设备和连接属于正确账号。
- 创建 / 编辑一个 HTTP 测试连接，检查电脑端同步与公网访问。
- 验证复制地址、暂停恢复、保存失败后的输入保留和重试。
- 验证屏幕旋转、前后台切换、断网和会话过期。
- 使用测试数据；不要上传应用私有目录或凭据。

应用声明网络访问与网络状态权限，关闭 Android 系统备份。手机管理已有设备上的连接，不运行本地 Agent 或隧道前台服务。
