"""Exercise dual-ABI notices and rejection of mixed or renamed native libraries."""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("packages", ROOT / "scripts/verify-remote-packages.py")
packages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packages)


def elf(machine=62, alignment=16384):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 112, alignment)
    return bytes(data)


class NativePackageAbiTests(unittest.TestCase):
    def package(self, path, abi, data, extra=None):
        with zipfile.ZipFile(path, "w") as archive:
            for name in ("libhome_tunnel_remote.so", "libhome_tunnel_remote_jni.so", "libc++_shared.so"):
                archive.writestr(f"lib/{abi}/{name}", data)
            if extra:
                archive.writestr(*extra)

    def test_both_abis_and_invalid_library_sets(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "test.apk"
            for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
                with self.subTest(abi=abi):
                    data = elf(machine)
                    digest = hashlib.sha256(data).hexdigest()
                    self.package(apk, abi, data)
                    self.assertEqual(len(packages.package_libraries(apk, f"lib/{abi}/", digest, abi)), 3)
                    self.package(apk, abi, elf(62 if machine == 183 else 183))
                    with self.assertRaisesRegex(SystemExit, "ELF64"):
                        packages.package_libraries(apk, f"lib/{abi}/", digest, abi)
                    self.package(apk, abi, elf(machine, 4096))
                    with self.assertRaisesRegex(SystemExit, "16 KiB"):
                        packages.package_libraries(apk, f"lib/{abi}/", digest, abi)
                    self.package(apk, abi, data, (f"lib/{abi}/nested/libextra.so", data))
                    with self.assertRaisesRegex(SystemExit, "libraries only"):
                        packages.package_libraries(apk, f"lib/{abi}/", digest, abi)
                    self.package(apk, abi, data)
                    with self.assertRaisesRegex(SystemExit, "differs from its provenance"):
                        packages.package_libraries(apk, f"lib/{abi}/", "0" * 64, abi)

    def test_notice_cli_binds_each_library_and_rejects_other_abi(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sdk, ndk = root / "sdk", root / "ndk"
            sdk.mkdir(); ndk.mkdir()
            (sdk / "LICENSE.md").write_text("engine notice", encoding="utf-8")
            (ndk / "source.properties").write_text("Pkg.Revision = 27.2.12479018\n")
            for name in ("NOTICE", "NOTICE.toolchain"):
                (ndk / name).write_text("runtime notice")
            for abi in ("arm64-v8a", "x86_64"):
                with self.subTest(abi=abi):
                    library = hashlib.sha256(abi.encode()).hexdigest()
                    build = {"target": abi, "source_revision": "a" * 40, "files": {
                        "LICENSE.md": hashlib.sha256(b"engine notice").hexdigest(),
                        f"lib/{abi}/libhome_tunnel_remote.so": library}}
                    (sdk / "android-webrtc-build.json").write_text(json.dumps(build))
                    output = root / abi
                    command = [sys.executable, str(ROOT / "scripts/package-native-notices.py"),
                               "--sdk", str(sdk), "--ndk", str(ndk), "--output", str(output), "--abi", abi]
                    subprocess.run(command, check=True, capture_output=True)
                    record = json.loads((output / "native-notices.json").read_text())
                    self.assertEqual(record["library_sha256"], library)
                    apk = root / "notices.apk"
                    with zipfile.ZipFile(apk, "w") as archive:
                        for path in output.iterdir():
                            archive.write(path, "assets/licenses/" + path.name)
                    packages.package_notices(apk, "assets/licenses/", {
                        "source_revision": "a" * 40, "library_sha256": library}, build)
                    command[-1] = "x86_64" if abi == "arm64-v8a" else "arm64-v8a"
                    result = subprocess.run(command, capture_output=True, text=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("ABI differs", result.stderr)


if __name__ == "__main__":
    unittest.main()
