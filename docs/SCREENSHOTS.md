# Documentation screenshots

These images were captured on 2026-09-30 by the existing Android API 35 emulator
instrumentation workflow from the 10.0.0 production Compose UI. The test supplies
synthetic account/device data; English UI labels and Chinese sample device names
are intentional. They show the remote-control entry and device list, not an active
remote session. The original 1080 × 2400 PNG bytes are preserved without editing.

- [Capture run](https://github.com/ZHanry/home-tunnel-android/actions/runs/36653765208)
- Artifact: `android-runtime-api-35`, ID `11070704569`
- PR head: `e333d8734e8988d4155ad3de1ab9d4deed2ecfe8`
- Actual tested merge revision: `d84682d706a467b4a09741fd75afa62b340e5399`
- [Source, APK and image digests](assets/screenshots.json)
- Fixture: `ManagementUiTest.navigationFiltersByDeviceAndSearchesServices`

Instrumentation reported 21 passed, 0 failed and 2 skipped tests. This is a debug
build with development fixtures. It is not final signed-APK, physical-device,
remote-media or full-app acceptance, and it does not change the owner waivers in
[release notes](RELEASE_NOTES.md).

The retired `overview.jpg` and `administration.jpg` were stale, unreferenced assets.
Current docs use `overview.png` and `devices.png`; the device image is not labeled
as administration.

## Reproduce

Run the existing Android CI on the intended source revision. Its Android runtime
API 26/35 jobs run `scripts/emulator-check.sh`, which installs the debug APK and
instrumentation APK and invokes `scripts/run-instrumentation.py`. That runner
pulls the test's screenshots and records their hashes in `instrumentation.json`.
Download `android-runtime-api-35`, verify the artifact digest and image hashes,
inspect the actual images, then update this manifest and documentation together.
Do not substitute generated mockups or alter acceptance records.
