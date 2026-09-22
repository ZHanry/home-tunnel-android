# Home Tunnel Android 8.0.0 development candidate

This prerelease adds the Android controller's remote-desktop protocol and security
foundation. Existing tunnel management remains available. The source version is
8.0.0; prerelease packages display their full `8.0.0-rc.N` identity.

- Add a remote-desktop entry, endpoint enrollment, parent-account reauthentication,
  AndroidKeyStore P-256 identity, DPoP requests, WSS authentication and pairing-code
  confirmation against the shared server contract.
- Add strict JSON/JWS validation, account-change cancellation, signed server-key rotation,
  foreground and permission gates, and explicit text-only clipboard/SAF access.
- Fix account-token refresh races that could restore a cleared login.
- Integrate the hash-locked shared C ABI through JNI and package its matching C++
  runtime. Native-source and library digests accompany the APK/AAB release evidence.
- Preserve the Android application ID and persistent signing certificate. Each RC
  and stable package requires a strictly increasing versionCode.

The bundled shared core currently reports `security-core-only-media-unavailable`.
Remote video, keyboard/pointer sessions, system audio, microphone return, clipboard
delivery and file transfer are **not available** in this candidate. The UI blocks
session creation rather than claiming a successful connection. Multi-session media
and optional AV1/HEVC are also not implemented. These limitations block a stable
release claiming the complete 8.0 plan.

Local verification covers JVM tests, Lint, JNI compilation and package contents.
The release workflow also requires API 26/35 instrumentation CI and persistent
signing checks. Physical-device interoperability and media tests remain outstanding;
see [REMOTE_DESKTOP.md](REMOTE_DESKTOP.md) and the published evidence for exact scope.
