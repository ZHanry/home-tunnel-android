# Android 9.0.0 remote desktop

This release implements account-bound P-256 AndroidKeyStore identities, DPoP REST authentication, single-use WebSocket authentication, server-key pinning, pairing confirmation, bounded protocol parsing, and local input/lease gates. The existing tunnel-management app remains available. The stable version label does not replace the outstanding device acceptance evidence.

Each Surface attachment has a fresh generation. Direct replacement and detach release input immediately; the matching new Surface must present a frame before control can be requested again. Old queued frame callbacks are ignored, and a ready foreground target with no first frame times out after 15 seconds. Native state-machine and JVM regressions cover replacement, stale callbacks, timeout and detach/reattach. The opt-in device test now creates two distinct ImageReaders and verifies real replacement, but has not been run on a physical device.

The default native artifact is a **security core with no media backend** and returns `available=false`. Production arm64-v8a and x86_64 controller SDKs now come from the same unmodified client source and pinned WebRTC build. They implement VP8/Surface video, direct-UDP path verification, signed authority, synchronized input, foreground text clipboard, descriptor-based files and AAudio system playback. Audio availability also depends on the actual output device. The controller does not capture microphone audio. Successful compilation or import does not establish device acceptance.

## Build and provenance

`native/remote-source` is a read-only text snapshot from the desktop repository. `native/remote-source.lock.json` contains every source digest, the original source-archive digest, ABI and truthful source state. Do not edit or fork this copy. Replace it only from a reviewed desktop source artifact and update the full lock.

Install Android NDK `27.2.12479018` and CMake `3.22.1`, then run:

```sh
python scripts/build-remote-native.py
./gradlew -PremoteNativeRoot="$PWD/.cache/remote-native" test lint assembleDebug assembleDebugAndroidTest
```

The security-core builder is for bounded development/CI checks. Production candidates use the signed dual-ABI import path in [RELEASING.md](RELEASING.md). The committed source lock and candidate lock bind both ABIs, the recipe, original source archive, compiler, headers and library bytes. There is no independent WebRTC AAR or second transport stack.

```sh
python scripts/import-sdk-candidate.py --restore
./gradlew -PremoteNativeRoot="$PWD/.cache/remote-controller/x86_64" -PremoteControllerAbi=x86_64 testDebugUnitTest lint assembleDebug assembleDebugAndroidTest
```

Select `arm64-v8a` in both properties to build the other production ABI. A missing native artifact remains an explicit unavailable state in a management-only development build.

The importer checks every SDK file, source tree, dependency lock, toolchain recipe
and public header. The GN build checks the C exports, static C++ runtime boundary
and 16 KiB ELF alignment. The app passes complete signed authority to native,
signs only correlated proof transcripts with AndroidKeyStore, and reports ready
only after native confirms an actual Surface frame. The view preserves aspect
ratio; touching outside the display cannot produce input. Input requires the
request/grant/state/ACK sequence, and text success requires the matching host ACK.

## Permission boundaries

- Android is a controller only. System audio is playback-only and requires both a signed scope and peer feature acknowledgement. Backgrounding, logout and account changes close input, audio, clipboard and file gates; no permanent foreground service is added. Microphone capture is unavailable.
- Clipboard access requires foreground focus, a signed grant containing the direction and a peer feature acknowledgement. The session grant is the consent; no per-copy confirmation is shown. Only plain text up to 64 KiB is synchronized; providers, HTML and images are not dereferenced. Backgrounding disables both directions.
- Files are selected through SAF without broad storage access. The shared protocol permits 64 files, 8 GiB per file and 32 GiB per batch. Native transport uses app-owned descriptors and verifies exact size and SHA-256 before reporting receipt. The receiver explicitly accepts an offer into private staging, then chooses its SAF destination. A failed export retains the verified private copy for retry; cancellation removes incomplete output. Provider opens and nonblocking pipe IO respond to cancellation on API 26 and later. Full bidirectional remote transfer still needs final-package acceptance.
- Initial server trust is pinned on first use over HTTPS. Subsequent signing-key changes require a consecutive ES256 rotation chain rooted in the pinned active key, valid key fingerprints and validity intervals. Same-version mutations, instance changes and version/restore-epoch rollback fail closed. Restore-epoch changes clear the local remote session and authentication. Explicit recovery for lost pins or invalid chains is not implemented; failures never reset trust automatically.
- Before native startup, Kotlin independently verifies the server ticket, lease and host-signed grant against the local account, selected endpoints and key fingerprints, server instance/restore epoch, session and original pairing request. One-session grants must name that exact request; renewal cannot change grant or account-token versions or extend past the grant/signing-key expiry. The shared public authorization vectors exercise cross-client rejection behavior.
- Each input handshake uses a fresh UUID echoed by `CONTROL_GRANTED`, `INPUT_STATE` and `INPUT_SYNC_ACK`. Input stays disabled until the matching epoch/layout acknowledgement arrives. Backgrounding, surface loss, layout changes, explicit release and a five-second pending-handshake deadline invalidate it. Delayed acknowledgements leave the local session read-only rather than terminating video. Both Kotlin and the linked native controller enforce these gates independently.

## Controller screen

The remote screen is a controller. It offers approval, one-time password, fixed password, and unattended access. When a host reports `offered_access_modes`, only those modes are enabled. Unattended stays off when that list is missing unless the older host flag is set. Session tools intersect the signed grant with `ht_rd_get_capabilities` permissions and, for files and system audio, the host `capabilities.native` backends. A missing native report does not disable input or clipboard, and it does not enable files or audio. Microphone return is unavailable. The display picker uses the independently verified native layout. Switching display or reconnecting advances the server-authorized epoch while retaining the locally pinned endpoints, grant and permissions. Old input, file, clipboard and audio authority is disposed before replacement. The viewport supports bounded 1–4x zoom and two-finger panning; view transforms preserve pointer mapping. Display metrics are shown only when reported. Direct UDP and native handshake gates remain mandatory.

## Validation still required

JVM protocol/crypto/state/transfer tests and AndroidKeyStore instrumentation are separate evidence. Successful compilation is not a physical-device test. Real Surface decoding, input lifecycle, Wi-Fi/cellular migration, codec negotiation, physical devices and sustained-session matrices remain required before claiming an accepted Android controller. Audio routes, actual clipboard/files, monitor switching, reconnect, consent and permission revocation require their own runtime evidence. API35/API26 module and development instrumentation results do not stand in for final APK acceptance or arm64 device coverage.
