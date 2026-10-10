# NestLink Android 14.0.0 构建与来源

正式 Android 产品来自本仓库固定的 `homedesk-core` 子模块，即 `ZHanry/home-tunnel-client` 的共享 Rust/Flutter 源码。应用目录为 `homedesk-core/client/flutter`，Android 工程为其 `android/` 子目录。一个 universal APK 同时包含 `arm64-v8a` 与 `x86_64`，与桌面客户端共用界面、账号、设备和远控逻辑。

本仓库根目录的旧 `app/`、独立控制器 SDK 导入脚本和历史候选流程保留供历史版本查询，不是 14.0.0 的构建入口。运行根目录 `./gradlew assembleRelease` 不会生成本版正式产品。

## 固定源码与工具链

```sh
git clone --recurse-submodules https://github.com/ZHanry/home-tunnel-android.git
cd home-tunnel-android
git checkout ANDROID_SOURCE_COMMIT
git submodule update --init --recursive
python3 scripts/check-homedesk-source.py
python3 scripts/check-device-capabilities-contract.py
```

`ANDROID_SOURCE_COMMIT` 应替换为待构建的完整提交 SHA。`homedesk-core.lock.json` 必须与子模块 gitlink、实际 HEAD 和共享契约字节一致；不要把子模块切到浮动分支或单独更换某个 ABI 的库。最终已发布版本的来源与安装包摘要见发行材料中的 `BUILD.json`，当前构建状态见 [14.0.0 说明](HOMEDESK_RELEASE.md)。

权威构建配方是 [Android CI](../.github/workflows/ci.yml)。它固定 Flutter 3.24.5、Rust 1.96.0、JDK 17、Android SDK Platform 35、Build Tools 35.0.0、NDK 27.2.12479018、Gradle 8.9 和 vcpkg 提交。最低 Android 8.0 / API 26，目标 API 35。Flutter 版本来自共享源码 `pubspec.yaml` 的 `14.0.0+14000000`，本仓库 `gradle.properties` 与 `compatibility.json` 同步记录 `versionName=14.0.0`、`versionCode=14000000`。

## CI 构建流程

1. `bridge` 从固定共享源码生成 Rust/Flutter FFI 桥，两个原生构建使用同一份桥文件。
2. `native` 分别编译 `aarch64-linux-android` 和 `x86_64-linux-android`。ARM64 使用 `flutter,hwcodec`，x86_64 使用 `flutter`；均使用 API 26 和 16 KiB 链接页设置。依赖源码、补丁、锁文件与许可证随原生构建保存。
3. `debug` 合并两个 ABI 的 `librustdesk.so` 和 `libc++_shared.so` 到共享 Flutter 工程，执行分析与全部 `homedesk_*`、`nestlink_*` widget 测试，再构建 universal debug APK 和 instrumentation APK。
4. `instrumentation` 在 API 26、35 的 x86_64 模拟器上运行真实 AndroidKeyStore 测试和应用启动检查，保存日志、截图、源码与文件摘要。
5. `signed` 在受保护的 `android-release` 环境构建正式 universal APK，使用原发行证书并验证实际包内容。PR 不接触签名密钥；main 构建的 Quality Gate 要求签名任务成功。

正式产物是 `NestLink-Android-14.0.0.apk`，成功 main run 的 `homedesk-android` artifact 同时保存 `products/` 与 `material-input/`。该流程不发布 AAB 或分 ABI 安装包。

## 本地调试共享应用

完整原生依赖构建、桥生成和 Gradle 准备步骤应照上述固定提交的 CI 配方执行；`scripts/setup-homedesk-sdk.sh` 会校验并准备本仓库锁定的 Gradle Wrapper、签名配置与 ABI 过滤器。该脚本按 CI 环境运行，本地执行时需提供相同 SDK 路径及环境，不要使用已有的未知来源 `jniLibs`。

生成同源桥文件、构建两个 ABI 并将原生库放入 `homedesk-core/client/flutter/android/app/src/main/jniLibs/` 后，在共享应用目录运行：

```sh
cd homedesk-core/client/flutter
flutter pub get --enforce-lockfile
flutter analyze --no-pub --no-fatal-infos --no-fatal-warnings
mapfile -t widget_tests < <(find test -maxdepth 1 -type f \( -name 'homedesk_*_test.dart' -o -name 'nestlink_*_test.dart' \) | sort)
flutter test --no-pub "${widget_tests[@]}"
flutter build apk --debug --target-platform android-arm64,android-x64
cd android
./gradlew --no-daemon :app:assembleDebugAndroidTest -Ptarget-platform=android-arm64,android-x64
```

以上为 Bash 命令；Windows 使用相同工程内的 `gradlew.bat`，并按本机 shell 枚举测试文件。debug APK 位于 `homedesk-core/client/flutter/build/app/outputs/flutter-apk/app-debug.apk`，测试 APK 位于共享应用的 `build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。调试包使用调试证书，但当前共享应用仍使用 `io.github.zhanry.hometunnel` 包名；不能覆盖已有正式签名安装。需要测试升级时使用原正式签名构建，并保留原配置。

## 正式签名与包核对

正式构建读取以下环境变量，或共享 Android 工程的本地 `key.properties`。CI 从受保护 secret 恢复临时 keystore，任务结束后删除；密钥与密码不得提交到仓库或发行材料。

```text
ANDROID_RELEASE_STORE_FILE
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD
```

```sh
cd homedesk-core/client/flutter
flutter build apk --release --target-platform android-arm64,android-x64
cd ../../..
python3 scripts/verify-homedesk-apk.py homedesk-core/client/flutter/build/app/outputs/flutter-apk/app-release.apk
```

验证器检查 APK 签名、原 applicationId、版本、API 26/35、两个 ABI 与实际 ELF 引擎，并在 `material-input/android-build.json` 记录包及原生库 SHA-256。发行证书 SHA-256 固定为 `d7779e338be1039acee6dda9a43417cbf2baf4b0c9995578d9708501e95af702`，权威记录为 [`release-signing-cert.sha256`](../release-signing-cert.sha256)。`--unsigned-debug` 只放宽调试证书匹配，仍要求实际签名和包结构，不会赋予发行资格。

## 安装与验证范围

使用明确选定的设备，例如 `adb -s emulator-5554 install -r APK_PATH`。API 26/35 的 CI 检查覆盖 AndroidKeyStore 往返、不可导出密钥、IV/密文/AAD/版本篡改拒绝和未配置应用启动；它不等同于服务端登录、已安装 APK 的远控及穿透联调。

正式验收还应使用实际签名 APK 验证账号登录与管理、设备目录、批准或密码授权、撤销、远控以及桌面执行穿透。将日志和截图绑定源码、APK SHA-256 和 CI run；真实 ARM64 手机、运营商 NAT、长期媒体会话等未执行范围必须明确记录。发布流程见 [RELEASING.md](RELEASING.md)。
