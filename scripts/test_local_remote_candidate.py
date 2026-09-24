"""The private native verifier must never accept release-looking metadata."""

import importlib.util
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


script = Path(__file__).with_name("verify-local-remote-candidate.py")
spec = importlib.util.spec_from_file_location("local_candidate", script)
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)


class LocalCandidateTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.sdk = Path(self.directory.name)
        self.hashes = {
            "source_tree_sha256": "a" * 64,
            "header_sha256": "b" * 64,
            "notice_sha256": "c" * 64,
            "abis": {
                "arm64-v8a": {"library_sha256": "d" * 64, "args_sha256": "e" * 64},
                "x86_64": {"library_sha256": "f" * 64, "args_sha256": "0" * 64},
            },
        }
        self.manifest = {
            "schema_version": 1, "status": "local-test-only", "release_eligible": False,
            "arm64_runtime_verified": False, "version": "9.0.0", **copy.deepcopy(self.hashes),
        }

    def verify(self, abi="arm64-v8a"):
        (self.sdk / "local-candidate.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        arguments = [str(script), "--sdk", str(self.sdk), "--source", str(self.sdk),
                     "--ndk", str(self.sdk), "--abi", abi]
        with patch.object(sys, "argv", arguments), patch.object(candidate, "inspect", return_value={
            **self.hashes, "abis": {abi: self.hashes["abis"][abi]},
        }):
            candidate.main()

    def test_valid_local_manifest(self):
        self.verify()
        self.verify("x86_64")

    def test_release_marker_is_rejected(self):
        self.manifest["release_eligible"] = True
        with self.assertRaisesRegex(SystemExit, "non-release"):
            self.verify()

    def test_library_mismatch_is_rejected(self):
        self.manifest["abis"]["arm64-v8a"]["library_sha256"] = "1" * 64
        with self.assertRaisesRegex(SystemExit, "hash mismatch"):
            self.verify()

    def test_source_mismatch_is_rejected(self):
        self.manifest["source_tree_sha256"] = "2" * 64
        with self.assertRaisesRegex(SystemExit, "hash mismatch"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
