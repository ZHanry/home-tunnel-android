import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import remote_emulator_policy as policy


class EmulatorPolicyTest(unittest.TestCase):
    def test_test_only_manifest_rejects_release_claims_and_modified_binaries(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(policy, "ROOT", Path(temporary)):
            root = Path(temporary)
            source = root / "native/remote-source"
            android = source / "android"
            android.mkdir(parents=True)
            original = policy.ARM64_ASSERT + "\n"
            (android / "BUILD.gn").write_text(original)
            (android / "android-build.lock.json").write_text(json.dumps({"gn_args": {
                "target_os": "android", "target_cpu": "arm64", "default_min_sdk_version": 26,
            }}))
            revision = "a" * 40
            (source / "remote-deps.lock.json").write_text(json.dumps({"webrtc": {"revision": revision}}))
            lock = {"source_revision": revision, "source_tree_sha256": "b" * 64,
                    "deps_lock_sha256": "c" * 64,
                    "source_files": {"android/BUILD.gn": policy.sha(android / "BUILD.gn")}}
            expected, tree = policy.source_files(lock)
            (root / "emulator-args.gn").write_text(policy.gn_text(policy.gn_args()))
            (root / "emulator-build.log").write_text("real build log fixture")
            library = root / "x86_64/libhome_tunnel_remote.so"
            library.parent.mkdir()
            library.write_bytes(b"real binary fixture")
            manifest = {"schema_version": 1, "status": "emulator-test-only", "test_only": True,
                        "source_modified": True, "target": "x86_64", "android_api": 26,
                        "controller_backend_linked": True, "device_media_accepted": False,
                        "source_revision": revision, "upstream_lock_sha256": lock["deps_lock_sha256"],
                        "webrtc_revision": revision, "source_files": expected, "source_tree_sha256": tree,
                        "gn_args": policy.gn_args(), "library_sha256": policy.sha(library),
                        "build_log_sha256": policy.sha(root / "emulator-build.log")}
            manifest_path = root / "emulator-webrtc-build.json"
            manifest_path.write_text(json.dumps(manifest))
            record = {"status": "webrtc-controller-linked-emulator-test", "target": "x86_64",
                      "available": True, "test_only": True, "device_media_accepted": False,
                      "source_tree_dirty": True, "source_tree_sha256": tree,
                      "base_source_tree_sha256": lock["source_tree_sha256"],
                      "controller_manifest_sha256": policy.sha(manifest_path),
                      "library_sha256": policy.sha(library)}
            policy.verify_manifest(root, record, lock)
            record["test_only"] = False
            with self.assertRaises(SystemExit):
                policy.verify_manifest(root, record, lock)
            record["test_only"] = True
            library.write_bytes(b"changed")
            manifest["library_sha256"] = policy.sha(library)
            manifest_path.write_text(json.dumps(manifest))
            record["controller_manifest_sha256"] = policy.sha(manifest_path)
            with self.assertRaises(SystemExit):
                policy.verify_manifest(root, record, lock)


if __name__ == "__main__":
    unittest.main()
