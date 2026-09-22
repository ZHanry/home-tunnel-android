import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("controller_import", Path(__file__).with_name("import-remote-controller.py"))
IMPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMPORT)


class ControllerImportPolicy(unittest.TestCase):
    def fixture(self, root):
        sdk = root / "sdk"
        library = sdk / "lib/arm64-v8a/libhome_tunnel_remote.so"
        library.parent.mkdir(parents=True)
        elf = bytearray(64)
        elf[:6] = b"\x7fELF\x02\x01"
        elf[18:20] = (183).to_bytes(2, "little")
        library.write_bytes(elf)
        header = sdk / "include/home_tunnel/remote.h"
        header.parent.mkdir(parents=True)
        header.write_text("fixture C ABI header")
        (sdk / "LICENSE.md").write_text("fixture notice")
        (sdk / "source-manifest.json").write_text("{}")
        recipe = root / "native/remote-source/android/android-build.lock.json"
        recipe.parent.mkdir(parents=True)
        recipe.write_text(json.dumps({"gn_args": {"target_cpu": "arm64"}}))
        source_files = {"android/android-build.lock.json": IMPORT.sha(recipe)}
        tree = hashlib.sha256(json.dumps(source_files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        lock = {"source_tree_dirty": False, "source_files": source_files, "source_tree_sha256": tree,
                "deps_lock_sha256": "a" * 64, "header_sha256": IMPORT.sha(header)}
        (root / "native/remote-source.lock.json").write_text(json.dumps(lock))
        manifest = {"source_modified": False, "target": "arm64-v8a", "android_api": 26, "controller_backend_linked": True,
                    "source_files": source_files, "source_tree_sha256": tree, "upstream_lock_sha256": "a" * 64,
                    "recipe_sha256": IMPORT.sha(recipe), "gn_args": {"target_cpu": "arm64"},
                    "files": {p.relative_to(sdk).as_posix(): IMPORT.sha(p) for p in sdk.rglob("*") if p.is_file()}}
        (sdk / "android-webrtc-build.json").write_text(json.dumps(manifest))
        return sdk

    def test_wrong_reviewed_manifest_and_modified_library_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(IMPORT, "ROOT", Path(temporary)):
            sdk = self.fixture(Path(temporary))
            checksum = IMPORT.sha(sdk / "android-webrtc-build.json")
            IMPORT.verify_sdk(sdk, checksum)
            with self.assertRaisesRegex(SystemExit, "reviewed SHA"):
                IMPORT.verify_sdk(sdk, "0" * 64)
            (sdk / "lib/arm64-v8a/libhome_tunnel_remote.so").write_bytes(b"changed binary")
            with self.assertRaisesRegex(SystemExit, "file hash"):
                IMPORT.verify_sdk(sdk, checksum)

    def test_other_source_tree_cannot_enable_the_backend(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(IMPORT, "ROOT", Path(temporary)):
            root = Path(temporary)
            sdk = self.fixture(root)
            lock_path = root / "native/remote-source.lock.json"
            lock = json.loads(lock_path.read_text())
            lock["source_tree_sha256"] = "b" * 64
            lock_path.write_text(json.dumps(lock))
            with self.assertRaisesRegex(SystemExit, "source tree"):
                IMPORT.verify_sdk(sdk, IMPORT.sha(sdk / "android-webrtc-build.json"))


if __name__ == "__main__":
    unittest.main()
