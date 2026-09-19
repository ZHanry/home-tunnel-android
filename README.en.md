# Home Tunnel Android

**Manage your servers and home devices from Android**

[![Stable 7.0.0](https://img.shields.io/badge/stable-7.0.0-176653)](https://github.com/ZHanry/home-tunnel-android/releases/tag/v7.0.0) [![License Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

[简体中文](README.md) · [Website](https://zhanry.github.io/home-tunnel/en/) · [Downloads](https://github.com/ZHanry/home-tunnel/blob/main/docs/DOWNLOADS.md) · [Quick start](https://github.com/ZHanry/home-tunnel/blob/main/docs/GETTING_STARTED.md)


The Android 8.0+ management app for Home Tunnel. Your phone controls devices and
connections; tunnels run continuously on a Windows/macOS/Linux computer or NAS.

[Download signed 7.0.0 APK (arm64-v8a)](https://github.com/ZHanry/home-tunnel-android/releases/download/v7.0.0/HomeTunnel-Android-7.0.0-arm64-v8a.apk) · [Release evidence](https://github.com/ZHanry/home-tunnel-android/releases/tag/v7.0.0)

- Save up to 20 encrypted server/account profiles with isolated request/cache state.
- TOTP/recovery-code sign-in, MFA setup, session revocation and one-time enrollment codes.
- Capability-based HTTP/TCP/UDP, SSH/RDP/RTSP presets and versioned administrator port settings.
- Device tags/favorites and batch pause/resume for up to 50 connections with per-item results.
- Account administration, status/audit views and redacted management diagnostics.

Requires server **7.0.0**. Enter your own HTTPS origin, sign in and select an enrolled
home device. Management discovery accepts an absent FRPS certificate while still
verifying HTTPS. Existing encrypted single-account state migrates on upgrade;
uninstalling can destroy local keys and is not required.

Build with JDK 17 and Android SDK 35:

```sh
./gradlew test lint assembleDebug assembleDebugAndroidTest
python3 scripts/check-repository.py
```

Official releases retain the established Android signing identity pinned in
`release-signing-cert.sha256`. CI checks KeyStore and management UI on API 26/35.
Release assets retain checksums, SBOMs and signing/install evidence.

[Feature guide](docs/PLATFORM_FEATURES.md) · [API](contracts/README.md) · [Project](https://github.com/ZHanry/home-tunnel)
