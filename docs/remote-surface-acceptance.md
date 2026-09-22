# Real Android Surface acceptance

This is an opt-in device test, separate from JVM tests, security-core JNI tests, and SDK compilation. It is **not recorded as passed until a connected Android device runs it successfully**. Regular CI skips it unless the explicit instrumentation argument is present.

The test uses the production `RemoteController`, AndroidKeyStore identity, HTTPS APIs, WSS, independent signed authorization, native PeerConnection, selected direct UDP path checks, software decoder, and `ANativeWindow` rendering. An `ImageReader` consumes the real RGBA frames. No generated callback or injected frame can satisfy the test.

## Prerequisites

1. Import the reviewed arm64 SDK with `scripts/import-remote-controller.py` against the matching immutable source lock. Build and install both debug APKs with `-PremoteControllerArm64=true` and `-PremoteNativeRoot=<imported SDK directory>`. The default security-core build intentionally fails this test when explicitly enabled.
2. Select an arm64 device running API 26 or newer. Ordinary TLS validation stays enabled; the fixture server needs an HTTPS certificate trusted by the device. No proxy that disables verification is supported.
3. Use a dedicated test account and host. Enable the host normally, choose a display with the fixture dimensions, and show a moving pattern containing at least eight different colors. The server must advertise direct UDP only, without TURN or ICE TCP.
4. Obtain the account's current short-lived management access token, password and optional MFA code. Obtain the server instance ID and active signing-key thumbprint, host endpoint ID and host public-key thumbprint through your established trusted fixture setup.

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

The fixture explicitly authorizes controller-side pairing with the pinned test host. Complete the normal local host pairing approval within 60 seconds, and then the normal local host session approval. The host's approval gate is not bypassed. The comparison code is available in the app-private `files/remote-surface-pairing.json` file if the fixture operator needs to compare it.

## Required observations

- At least 30 actual RGBA frames have the configured dimensions and at least eight sampled colors; at least two frame sample hashes differ.
- The production controller enters `active`, which requires independent authority verification, proven direct UDP transport and a frame actually posted to the Surface.
- Backgrounding stops new rendered frames after the queued buffers drain.
- Foregrounding produces at least 10 additional frames.
- Closing the session stops frames and returns the controller to `idle`.

The evidence JSON contains frame counts, dimensions, lifecycle assertions and a hash of sampled pixels. It contains no captured desktop image, credentials, signed tickets or private keys. This test establishes decoding and Surface/lifecycle behavior for that device and host combination; keyboard, pointer, text, additional codecs, other Android versions and device models require their own recorded acceptance runs.
