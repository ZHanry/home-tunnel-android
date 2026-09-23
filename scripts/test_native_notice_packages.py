"""Notice redistribution checks; fixtures do not claim executable media acceptance."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


def module(name, script):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(script))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


PACK = module("native_notice_pack", "package-native-notices.py")
VERIFY = module("native_notice_verify", "verify-remote-packages.py")


class NativeNotices(unittest.TestCase):
    def fixture(self, root):
        sdk = root / "sdk"; sdk.mkdir()
        ndk = root / "ndk"; ndk.mkdir()
        (sdk / "LICENSE.md").write_bytes(b"Reviewed linked engine notices")
        build = {"source_revision": "a" * 40, "files": {
            "LICENSE.md": hashlib.sha256((sdk / "LICENSE.md").read_bytes()).hexdigest(),
            "lib/arm64-v8a/libhome_tunnel_remote.so": "b" * 64}}
        (sdk / "android-webrtc-build.json").write_text(json.dumps(build))
        (ndk / "source.properties").write_text("Pkg.Revision = 27.2.12479018\n")
        (ndk / "NOTICE").write_bytes(b"Reviewed NDK source notices")
        (ndk / "NOTICE.toolchain").write_bytes(b"Reviewed NDK runtime notices")
        return sdk, ndk, build

    def test_generator_rejects_changed_sdk_notices_and_other_ndk_revisions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); sdk, ndk, _ = self.fixture(root)
            (sdk / "LICENSE.md").write_bytes(b"changed")
            with self.assertRaisesRegex(SystemExit, "reviewed SDK"):
                PACK.package(sdk, ndk, root / "output")
            (ndk / "source.properties").write_text("Pkg.Revision = 26.0.0\n")
            with self.assertRaisesRegex(SystemExit, "pinned NDK"):
                PACK.package(sdk, ndk, root / "output")
            self.assertFalse((root / "output").exists())

    def test_installable_packages_retain_exact_notices_and_reject_missing_or_rehashed_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); sdk, ndk, build = self.fixture(root)
            output = root / "notices"; PACK.package(sdk, ndk, output)
            contents = {path.name: path.read_bytes() for path in output.iterdir()}
            native = {"source_revision": "a" * 40, "library_sha256": "b" * 64}
            records = []
            for name, prefix in (("test.apk", "assets/licenses/"), ("test.aab", "base/assets/licenses/")):
                with zipfile.ZipFile(root / name, "w") as archive:
                    for key, value in contents.items():
                        archive.writestr(prefix + key, value)
                records.append(VERIFY.package_notices(root / name, prefix, native, build))
            self.assertEqual(*records)
            for mutation in ("missing", "tampered", "rehashed"):
                altered = dict(contents)
                if mutation == "missing":
                    del altered["NDK-NOTICE.toolchain"]
                else:
                    altered["WEBRTC-LICENSE.md"] = b"changed notice"
                    if mutation == "rehashed":
                        record = json.loads(altered["native-notices.json"])
                        record["files"]["WEBRTC-LICENSE.md"] = hashlib.sha256(altered["WEBRTC-LICENSE.md"]).hexdigest()
                        altered["native-notices.json"] = json.dumps(record).encode()
                with zipfile.ZipFile(root / "bad.apk", "w") as archive:
                    for key, value in altered.items():
                        archive.writestr("assets/licenses/" + key, value)
                with self.subTest(mutation=mutation), self.assertRaises(SystemExit):
                    VERIFY.package_notices(root / "bad.apk", "assets/licenses/", native, build)


if __name__ == "__main__":
    unittest.main()
