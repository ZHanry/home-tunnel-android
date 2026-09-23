# Android 8.0.0 remote desktop

This release implements account-bound P-256 AndroidKeyStore identities, DPoP REST authentication, single-use WebSocket authentication, server-key pinning, pairing confirmation, bounded protocol parsing, and local input/lease gates. The existing tunnel-management app remains available. The stable version label does not replace the outstanding device acceptance evidence.

Each Surface attachment has a fresh generation. Direct replacement and detach release input immediately; the matching new Surface must present a frame before control can be requested again. Old queued frame callbacks are ignored, and a ready foreground target with no first frame times out after 15 seconds. Native state-machine and JVM regressions cover replacement, stale callbacks, timeout and detach/reattach. The opt-in device test now creates two distinct ImageReaders and verifies real replacement, but has not been run on a physical device.

The default native artifact is a **security core with no media backend** and returns `available=false`. A separately built, same-source Android arm64 controller now implements real WebRTC/VP8 receive and Surface output, native ticket/lease/grant and proof verification, selected direct-UDP checks, and synchronized keyboard/pointer/text input. It must pass the import checks below before JNI may link it. Audio, clipboard and files remain unavailable in this controller profile. A successful compile or imported library is not physical-device acceptance.

## Build and provenance

`native/remote-source` is a read-only text snapshot from the desktop repository. `native/remote-source.lock.json` contains every source digest, the original source-archive digest, ABI and truthful source state. Do not edit or fork this copy. Replace it only from a reviewed desktop source artifact and update the full lock.

Install Android NDK `27.2.12479018` and CMake `3.22.1`, then run:

```sh
python scripts/build-remote-native.py
./gradlew -PremoteNativeRoot="$PWD/.cache/remote-native" test lint assembleDebug assembleDebugAndroidTest
```

The builder produces arm64 and x86_64 security-core libraries for development/CI; Gradle validates their hashes before linking JNI. Missing native artifacts are reported as unavailable in a management-only developer build. Release CI requires the separately published, signed, hash-locked arm64 controller SDK, JNI and matching C++ runtime, verifies their identical APK/AAB library sets and 16 KiB ELF page alignment, and publishes native provenance. It fails before signing if the final SDK lock is absent. No independent WebRTC AAR or second transport stack is included. See [RELEASING.md](RELEASING.md) for the published-release import path; the following manual import is for development diagnosis only.

For the real controller, build `scripts/build-remote-android-webrtc.py --build` in
the exact clean client repository on Linux. The Android source lock must first be
imported from that same native source tree. Review the CI artifact's manifest
SHA-256, then import and build explicitly:

```sh
python scripts/import-remote-controller.py /path/to/android-webrtc-arm64 --reviewed-manifest-sha256 REVIEWED_SHA256
./gradlew -PremoteNativeRoot="$PWD/.cache/remote-controller" -PremoteControllerArm64=true test lint assembleDebug
```

The importer checks every SDK file, source tree, dependency lock, toolchain recipe
and public header. The GN build checks the C exports, static C++ runtime boundary
and 16 KiB ELF alignment. The app passes complete signed authority to native,
signs only correlated proof transcripts with AndroidKeyStore, and reports ready
only after native confirms an actual Surface frame. The view preserves aspect
ratio; touching outside the display cannot produce input. Input requires the
request/grant/state/ACK sequence, and text success requires the matching host ACK.

## Permission boundaries

- Android is a controller only. Microphone permission is requested only after explicit activation in an authorized session. Backgrounding, logout and account changes close the local input/microphone gates; no permanent foreground service is added.
- Clipboard access requires foreground focus, a granted direction and explicit consent. Only plain text is accepted; providers, HTML and images are not dereferenced.
- Files are selected through SAF; no broad storage permission is requested. Up to 64 individually selected files, 8 GiB per file and 32 GiB per batch are permitted by the shared protocol. Directories and images are outside this preview. The tested transfer primitives allow two active files and one unacknowledged chunk per file. A receive ACK follows the sink's commit callback; completion requires exact size, SHA-256 and successful output close. Cancellation and failed validation discard the incomplete output. Media-backend transport and actual SAF receive consent remain pending.
- Initial server trust is pinned on first use over HTTPS. Subsequent signing-key changes require a consecutive ES256 rotation chain rooted in the pinned active key, valid key fingerprints and validity intervals. Same-version mutations, instance changes and version/restore-epoch rollback fail closed. Restore-epoch changes clear the local remote session and authentication. Explicit recovery for lost pins or invalid chains is not implemented; failures never reset trust automatically.
- Before native startup, Kotlin independently verifies the server ticket, lease and host-signed grant against the local account, selected endpoints and key fingerprints, server instance/restore epoch, session and original pairing request. One-session grants must name that exact request; renewal cannot change grant or account-token versions or extend past the grant/signing-key expiry. The shared public authorization vectors exercise cross-client rejection behavior.
- Each input handshake uses a fresh UUID echoed by `CONTROL_GRANTED`, `INPUT_STATE` and `INPUT_SYNC_ACK`. Input stays disabled until the matching epoch/layout acknowledgement arrives. Backgrounding, surface loss, layout changes, explicit release and a five-second pending-handshake deadline invalidate it. Delayed acknowledgements leave the local session read-only rather than terminating video. Both Kotlin and the linked native controller enforce these gates independently.

## Validation still required

JVM protocol/crypto/state/transfer tests and AndroidKeyStore instrumentation are separate evidence. Successful compilation is not a physical-device test. Real Surface decoding, input lifecycle, Wi-Fi/cellular migration, codec negotiation, physical devices and sustained-session matrices remain required before claiming an accepted Android controller. Audio routes and actual clipboard/file transfer consent and transport need separate implementation and acceptance.
