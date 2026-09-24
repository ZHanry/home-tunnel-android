# Real Android Surface acceptance

This is an opt-in device test, separate from JVM tests, security-core JNI tests, and SDK compilation. It is **not recorded as passed until a connected Android device runs it successfully**. Regular CI skips it unless the explicit instrumentation argument is present.

The test uses the production `RemoteController`, AndroidKeyStore identity, HTTPS APIs, WSS, independent signed authorization, native PeerConnection, selected direct UDP path checks, software decoder, and `ANativeWindow` rendering. An `ImageReader` consumes the real RGBA frames. No generated callback or injected frame can satisfy the test.

## Prerequisites

1. Import the reviewed arm64 SDK with `scripts/import-remote-controller.py` against the matching immutable source lock. Build and install both debug APKs with `-PremoteControllerArm64=true` and `-PremoteNativeRoot=<imported SDK directory>`. The default security-core build intentionally fails this test when explicitly enabled.
2. Select an arm64 device running API 26 or newer. For private candidate testing, an API 35 x86_64 emulator with a separately built x86_64 native controller may substitute for device execution, but its result does not verify that the delivered arm64 library loads or decodes on real hardware. Ordinary TLS validation stays enabled; the fixture server needs an HTTPS certificate trusted by the device. No proxy that disables verification is supported.
3. Use a dedicated test account and host. Enable the host normally, choose a display with the fixture dimensions, and show a moving pattern containing at least eight different colors. The server must advertise direct UDP only, without TURN or ICE TCP.
4. Obtain the account's current short-lived management access token, password and optional MFA code. Obtain the server instance ID and active signing-key thumbprint, host endpoint ID and host public-key thumbprint through your established trusted fixture setup.

The x86_64 emulator controller is imported separately with `scripts/import-remote-emulator.py` from a pinned WebRTC build, the exact GN x64 override, its build log and the reviewed same-source tree. Use `-PremoteControllerEmulatorX64=true -PremoteNativeRoot=<emulator artifact>` for debug builds only; this mode disables the release variant. `scripts/verify-remote-native.py --abis x86_64 --emulator-test` rejects ordinary security-core libraries and unmarked x64 binaries. The resulting debug APK is a local test instrument, not the arm64 deliverable.

Create an untracked private JSON file outside the repository:

```json
{
  "allow_test_pairing": true,
  "api_base_url": "https://fixture.example/api/v1/",
  "management_access_token": "SHORT_LIVED_TEST_ACCOUNT_TOKEN",
  "password": "TEST_ACCOUNT_PASSWORD",
  "mfa_code": "",
  "user_id": "ACCOUNT_UUID",
  "server_instance_id": "SERVER_UUID",
  "server_active_kid": "TRUSTED_SERVER_KEY_THUMBPRINT",
  "host_endpoint_id": "HOST_UUID",
  "host_jkt": "TRUSTED_HOST_KEY_THUMBPRINT",
  "width": 1280,
  "height": 720
}
```

Run:

```text
python scripts/run-remote-surface-acceptance.py --adb <adb executable> --serial <device serial> --fixture <private JSON path> --output <evidence JSON path>
```

The runner copies credentials over stdin into the debug app's private storage. It never places them in process arguments. The instrumentation consumes and deletes that copy immediately; the runner also removes it on failure. Remove the original private file after the run. The AndroidKeyStore identity remains available for subsequent dedicated-fixture runs, with no private-key export.

The fixture explicitly authorizes controller-side pairing with the pinned test host. Complete the normal local host pairing approval within 60 seconds; the resulting one-session grant automatically authorizes only that signed request, without a second host confirmation. The host's local approval gate is not bypassed. The comparison code is available in the app-private `files/remote-surface-pairing.json` file if the fixture operator needs to compare it.

For the isolated Windows-host/x86_64-emulator run, `home-tunnel-client/scripts/test-remote-android-emulator.mjs` creates a dedicated browser input target and supplies an additional `input_point` object (`x`, `y`, `width`, `height`). Its fixture requests keyboard, pointer and text scopes. The harness verifies real trusted target events and Unicode text after the Android controller's input handshake. A fixture without `input_point` remains view-only; it does not claim input acceptance. The harness supports `--mode same-account`, `cross-account-assist`, `cross-account-fixed` and `cross-account-request`; the latter three use separate authenticated accounts and the same signed session path.

The private 8.1 dirty-tree SDK can be checked with `scripts/verify-local-remote-candidate.py` and Gradle `-PremoteCandidateLocal=true -PremoteCandidateSource=<client native/remote>`. This path requires an explicit `local-candidate.json`, current source digest, matching GN arguments, 16 KiB ELF segments and exact C ABI. It disables the release variant and can produce only an internal debug experiment. It is not the published SDK provenance gate and does not prove the arm64 library runs on a device.

## Required observations

- At least 30 actual RGBA frames preserve the source aspect ratio and have at least half the source width and height (minimum 640 × 360); WebRTC may adaptively downscale. Each frame has at least eight sampled colors, and at least two frame sample hashes differ. Evidence records both source and decoded dimensions.
- The production controller enters `active`, which requires independent authority verification, proven direct UDP transport and a frame actually posted to the Surface.
- Backgrounding stops new rendered frames after the queued buffers drain.
- Foregrounding produces at least 10 additional frames.
- Direct Surface replacement, detach and reattachment present fresh frames without accepting stale callbacks.
- Closing the session stops frames and returns the controller to `idle`.

The evidence JSON contains frame counts, dimensions, lifecycle assertions and a hash of sampled pixels. It contains no captured desktop image, credentials, signed tickets or private keys. `native_input_attempted` records whether the optional input path ran; the isolated harness adds `native_pointer_verified`, `native_keyboard_verified` and `native_unicode_text_verified` only after observing the dedicated native target. Additional codecs, Android versions and real device models still require their own recorded acceptance runs.

An x86_64 emulator that only runs the UI APK without the native controller is not a substitute for this test. A missing JNI library or a skipped live Surface test must be reported as a failed candidate acceptance gate, not as successful arm64 media validation.
