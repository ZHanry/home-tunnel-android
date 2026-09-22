# Android remote desktop preview

This preview implements account-bound P-256 AndroidKeyStore identities, DPoP REST authentication, single-use WebSocket authentication, server-key pinning, pairing confirmation, bounded protocol parsing, and local input/lease gates. The existing tunnel-management app remains available.

The bundled native artifact is currently a **security core with no media backend**. It returns `available=false`; Android does not label a connection successful or open input, microphone, clipboard or file permissions. Video, audio, touch/keyboard control and end-to-end file transfer are not usable in this candidate. The screen explicitly reports this limitation. A JNI/Surface adapter and SAF/clipboard/transfer primitives prepare the integration but are not evidence of a functioning media session.

## Build and provenance

`native/remote-source` is a read-only text snapshot from the desktop repository. `native/remote-source.lock.json` contains every source digest, the original source-archive digest, ABI and truthful source state. Do not edit or fork this copy. Replace it only from a reviewed desktop source artifact and update the full lock.

Install Android NDK `27.2.12479018` and CMake `3.22.1`, then run:

```sh
python scripts/build-remote-native.py
./gradlew -PremoteNativeRoot="$PWD/.cache/remote-native" test lint assembleDebug assembleDebugAndroidTest
```

The builder produces arm64 and x86_64 core libraries; Gradle validates their hashes before linking JNI. Release packages include arm64 only. Missing native artifacts are reported as unavailable in a management-only developer build. Release CI requires the locked core, JNI and matching C++ runtime, verifies their identical APK/AAB library sets and 16 KiB ELF page alignment, and publishes native provenance. No independent WebRTC AAR or second transport stack is included.

## Permission boundaries

- Android is a controller only. Microphone permission is requested only after explicit activation in an authorized session. Backgrounding, logout and account changes close the local input/microphone gates; no permanent foreground service is added.
- Clipboard access requires foreground focus, a granted direction and explicit consent. Only plain text is accepted; providers, HTML and images are not dereferenced.
- Files are selected through SAF; no broad storage permission is requested. Up to 64 individually selected files, 8 GiB per file and 32 GiB per batch are permitted by the shared protocol. Directories and images are outside this preview. The tested transfer primitives allow two active files and one unacknowledged chunk per file. A receive ACK follows the sink's commit callback; completion requires exact size, SHA-256 and successful output close. Cancellation and failed validation discard the incomplete output. Media-backend transport and actual SAF receive consent remain pending.
- Initial server trust is pinned on first use over HTTPS. Subsequent signing-key changes require a consecutive ES256 rotation chain rooted in the pinned active key, valid key fingerprints and validity intervals. Same-version mutations, instance changes and version/restore-epoch rollback fail closed. Restore-epoch changes clear the local remote session and authentication. Explicit recovery for lost pins or invalid chains is not implemented; failures never reset trust automatically.
- Before native startup, Kotlin independently verifies the server ticket, lease and host-signed grant against the local account, selected endpoints and key fingerprints, server instance/restore epoch, session and original pairing request. One-session grants must name that exact request; renewal cannot change grant or account-token versions or extend past the grant/signing-key expiry. The shared public authorization vectors exercise cross-client rejection behavior.
- Each input handshake uses a fresh UUID echoed by `CONTROL_GRANTED`, `INPUT_STATE` and `INPUT_SYNC_ACK`. Input stays disabled until the matching epoch/layout acknowledgement arrives. Backgrounding, surface loss, layout changes, explicit release and a five-second pending-handshake deadline invalidate it. Delayed acknowledgements leave the local session read-only rather than terminating video. These gates do not replace the still-required native readiness and media integration.

## Validation still required

JVM protocol/crypto/state/transfer tests and AndroidKeyStore instrumentation are separate evidence. Successful compilation is not a physical-device test. The media engine, native readiness callbacks, audio routes, input lifecycle, actual transfer consent, Wi-Fi/cellular migration, codec negotiation, physical devices and sustained-session matrices remain release blockers for claiming functional remote desktop.
