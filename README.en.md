# HomeDesk Android / Home Tunnel

Current main: **11.0.0-rc.1 candidate**, using a pinned Client Rust/Flutter gitlink. Hearth mobile navigation retains family devices and complete governed tunnel service management. Remote desktop requires authenticated encrypted direct P2P; no relay/vendor/proxy fallback. Failed direct connections terminate.

[简体中文](README.md) · [Candidate](https://github.com/ZHanry/home-tunnel-android/releases/tag/v11.0.0-rc.1) · [Last stable 10.0.0](https://github.com/ZHanry/home-tunnel-android/releases/tag/v10.0.0)

Three attachments: universal arm64-v8a/x86_64 APK, source/build materials and SHA256SUMS. Android API26+, target35, unchanged application ID and release certificate, increasing versionCode 11000001. Android 14/15 screen capture needs fresh visible user consent. Credentials use AndroidKeyStore AES-GCM256 with randomized IVs and AAD, no plaintext fallback or OS backup.

Configure your own hbbs trust settings and HTTPS Server 11.x portal. Account enrollment does not grant screen sharing. Real-device, cross-network NAT, sustained media, audio/input/files and 16 KiB page acceptance remain pending. [Candidate notes](docs/HOMEDESK_RELEASE.md).

Initialize recursive submodules and run `python3 scripts/check-homedesk-source.py`. Hosted CI pins Rust1.96, Flutter3.24.5, FRB1.80.1, vcpkg, JDK17 and NDK27.2, builds both ABIs, tests AndroidKeyStore/startup on API26/35, then signs the universal APK with the existing identity. Releases reuse exact main-build bytes. Historical app/native/10.x recipes are retained for provenance and are not packaged by the current workflow. Shared HomeDesk runtime is AGPL-3.0; original project code retains Apache-2.0.
