<div align="center">
  <img src="docs/assets/HomeTunnel.svg" alt="Home Tunnel" width="80" height="80">
  <h1>Home Tunnel Android</h1>
  <p><strong>Manage your home devices and connections from Android</strong></p>
  <p>
    <img src="https://img.shields.io/badge/status-internal_testing-92400e" alt="Status: internal testing">
    <a href="https://github.com/ZHanry/home-tunnel-android/actions/workflows/ci.yml"><img src="https://github.com/ZHanry/home-tunnel-android/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0 license"></a>
  </p>
  <p><a href="README.md">简体中文</a> · <a href="https://zhanry.github.io/home-tunnel/">Project website</a></p>
</div>

Connect to your own Home Tunnel server, inspect devices, manage connections and copy public addresses. Tunnels run on home computers or NAS hosts; this app provides remote management.

> **Internal testing.** The focus is sign-in, connection editing, error recovery and real-device validation. No production-stable or app-store release is claimed.

[Project overview](https://github.com/ZHanry/home-tunnel) · [Server](https://github.com/ZHanry/home-tunnel-server) · [GUI / CLI client](https://github.com/ZHanry/home-tunnel-client)

## Requirements

- Android 8.0 / API 26 or newer; arm64 devices are the primary validation target.
- JDK 17, Android SDK Platform 35 and Build Tools 35 for development.
- A configured test server, test account and a registered home computer or NAS.

The UI uses Kotlin and Jetpack Compose. Use the checked-in Gradle Wrapper.

## Build and install

```sh
git clone https://github.com/ZHanry/home-tunnel-android.git
cd home-tunnel-android
./gradlew --no-daemon test lint assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat`. Android Studio can also build and install the app. Debug builds use an application ID ending in `.debug` and do not require release keys; debug certificates can differ between development machines.

Enter the server's HTTPS origin, sign in, select an existing device and try a simple HTTP connection. Test editing, pause/resume, offline recovery and session expiration. HTTP / HTTPS management is the current testing focus.

## Signing and contribution

Signed test packages use the restricted `android-release` GitHub environment. The application ID is `io.github.zhanry.hometunnel`; the certificate fingerprint is recorded in [release-signing-cert.sha256](release-signing-cert.sha256). APKs are installable; AABs are distribution artifacts, not direct installers.

See [building](docs/BUILDING.md), [test releases](docs/RELEASING.md), [contributing](CONTRIBUTING.md) and [security](SECURITY.md). Automated Gradle checks do not replace real-device testing.

Licensed under [Apache-2.0](LICENSE).
