# Home Tunnel Android 10.0.0

The Android controller now uses the same-source production remote SDK from the
10.0.0 desktop release for both arm64-v8a and x86_64. Remote sessions add scoped
system audio playback, consent-bound file transfer, monitor selection, bounded
reconnect and viewport gestures on top of the 9.0.0 authorization modes. Login,
MFA, password change and tunnel editors stay visible above the soft keyboard in
landscape and with large text, and account/network notices follow the app
language. The APK keeps the existing application ID and release signing identity
and uses versionCode `10000000`. Microphone return is not available.

Android sessions use direct UDP only; the server's TURN relay is for browser
viewers. The APK is signed with the existing release certificate, and the signing
is verified in CI.

Gemini reviewed emulator screenshots of development builds. The following were
not run for this release and are recorded as owner waivers in the attached
acceptance record:

- Android controlling a real Windows host
- the API 26/35 emulator runs of the final APK
- physical arm64 phones
- the 9→10 upgrade
- the 2-hour and 24-hour soaks
- performance comparison
- the NAT, IPv6 and fault matrix

## Previous release: 9.0.0

# Home Tunnel Android 9.0.0

The app refreshes navigation and the remote-control surface and exposes
host-approved requests, fixed-password access and one-time temporary-password
access with matching 9.0.0 server and desktop components. Login asks for an MFA
code only when the server requires one. The APK keeps the existing application
ID and release signing identity and uses versionCode `9000000`.

The Android controller requires the exact reviewed 9.0.0 native SDK from the
published desktop release. The x86_64 API 35 emulator exercised local-network
video and input, but the arm64 APK and real phones were not run. Clipboard
delivery remains unverified; audio, microphone return and file transport are
not available. Build and release evidence must not be presented as arm64
runtime acceptance.

## Previous release: 8.0.0

# Home Tunnel Android 8.0.0

This release adds the Android controller's remote-desktop protocol and security
foundation. Existing tunnel management remains available. The source version is
`8.0.0` with versionCode `8000002`; the source, tag, APK/AAB display version and asset names must match exactly.

- Add a remote-desktop entry, endpoint enrollment, parent-account reauthentication,
  AndroidKeyStore P-256 identity, DPoP requests, WSS authentication and pairing-code
  confirmation against the shared server contract.
- Add strict JSON/JWS validation, account-change cancellation, signed server-key rotation,
  foreground and permission gates, and explicit text-only clipboard/SAF access.
- Fix account-token refresh races that could restore a cleared login.
- Bind first-frame readiness to the current Surface generation. Replacing or detaching
  the target releases input; queued frames from an old target cannot re-enable it.
- Integrate the hash-locked shared C ABI through JNI and package its matching C++
  runtime. Native-source and library digests accompany the APK/AAB release evidence.
- Preserve the Android application ID and persistent signing certificate. Each RC
  and stable package requires a strictly increasing versionCode.

The default development/CI shared core reports `security-core-only-media-unavailable`
and blocks session creation. The separately compiled controller implements VP8/Surface
and keyboard/pointer/text through verified direct UDP, but physical-device decoding
and input remain unverified. Release now requires that controller SDK from the final
client tag, a verified published Release and committed digest lock; developer CI
artifacts cannot satisfy the release gate. No final SDK lock has been fabricated.
System audio, microphone return, clipboard/file delivery, multi-session UI and optional
AV1/HEVC remain unavailable in the Android controller profile. The 8.0.0 label does
not imply completion of these features or of physical-device acceptance.

Local verification covers JVM tests, Lint, JNI compilation and package contents.
The release workflow also requires API 26/35 instrumentation CI and persistent
signing checks. Physical-device interoperability and media tests remain outstanding;
see [REMOTE_DESKTOP.md](REMOTE_DESKTOP.md) and the published evidence for exact scope.
