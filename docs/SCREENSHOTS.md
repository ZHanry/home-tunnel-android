# Current UI captures

Only nestlink 13.0.0 captures belong in current documentation. Older product images have been removed.

`assets/screenshots.json` lists the current captures together with the exact source commit, signed universal APK digest, emulator version and image hashes. An empty list means that final signed-APK captures are still pending. Component previews never count as installed-app or remote-media acceptance.

Reproduce captures by installing the verified universal APK on the API 35 emulator, logging in to the disposable self-hosted fixture, and capturing the current screen with `adb exec-out screencap -p`. Inspect the original PNG bytes and record their SHA-256, dimensions and test-data status. Preserve the original application ID and release signing certificate.
