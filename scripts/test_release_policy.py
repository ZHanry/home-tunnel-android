"""Release-stage regressions; these tests do not contact GitHub or publish artifacts."""
from pathlib import Path
import importlib.util
import os
import unittest
from unittest.mock import patch

script = Path(__file__).with_name("release.py")
spec = importlib.util.spec_from_file_location("release_policy_under_test", script)
module = importlib.util.module_from_spec(spec)
previous_directory = Path.cwd()
try:
    with patch.dict(os.environ, {"GITHUB_REPOSITORY": "ZHanry/home-tunnel-test", "GITHUB_SHA": "test-commit", "GITHUB_REF_NAME": "v0.1.0-rc.1"}):
        spec.loader.exec_module(module)
finally:
    os.chdir(previous_directory)

class ReleasePolicyTests(unittest.TestCase):
    def test_android_rc_and_stable_must_strictly_increase_version_code(self):
        module.validate_android_version_code(8000001, [7000000])
        module.validate_android_version_code(8000002, [7000000, 8000001])
        module.validate_android_version_code(8000003, [7000000, 8000001, 8000002])
        for candidate in (8000001, 8000002, 0, -1, 2100000001, True):
            with self.assertRaises(SystemExit):
                module.validate_android_version_code(candidate, [8000001, 8000002])

    def test_rc_zero_is_not_a_release_identity(self):
        with self.assertRaises(SystemExit):
            module.validate_release_tag("v8.0.0-rc.0", "8.0.0", "internal-testing")

    def test_first_project_version_is_allowed_as_a_test_build(self):
        self.assertEqual(module.validate_release_tag("v0.1.0-rc.1", "0.1.0-rc.1", "internal-testing"), ("0.1.0", "1"))

    def test_full_candidate_identity_must_be_committed_in_source(self):
        self.assertEqual(module.validate_release_tag("v8.0.0-rc.1", "8.0.0-rc.1", "internal-testing"), ("8.0.0", "1"))
        for tag, source in (("v8.0.0-rc.1", "8.0.0"), ("v8.0.0-rc.2", "8.0.0-rc.1"), ("v8.0.0", "8.0.0-rc.1")):
            with self.assertRaisesRegex(SystemExit, "source version"):
                module.validate_release_tag(tag, source, "internal-testing")

    def test_internal_testing_cannot_publish_a_stable_tag(self):
        with self.assertRaisesRegex(SystemExit, "prereleases only"):
            module.validate_release_tag("v0.1.0", "0.1.0", "internal-testing")

    def test_source_version_must_match(self):
        with self.assertRaisesRegex(SystemExit, "source version"):
            module.validate_release_tag("v0.2.0-rc.1", "0.1.0", "internal-testing")

    def test_public_release_requires_an_explicit_stage(self):
        self.assertEqual(module.validate_release_tag("v1.0.0", "1.0.0", "public-release"), ("1.0.0", None))
        with self.assertRaisesRegex(SystemExit, "Unknown release stage"):
            module.validate_release_tag("v1.0.0", "1.0.0", "publc-release")

    def test_public_asset_list_keeps_only_installable_deliverables(self):
        self.assertEqual(module.public_asset_names("android", "6.0.0"), ["HomeTunnel-Android-6.0.0-arm64-v8a.apk"])
        client = module.public_asset_names("client", "6.0.0")
        self.assertEqual(len(client), 6)
        self.assertTrue(all(name.endswith((".exe", ".zip", ".tar.gz")) for name in client))
        self.assertEqual(module.public_asset_names("server", "6.0.0"), ["home-tunnel-server-6.0.0.tar.gz", "compose.release.yaml"])

if __name__ == "__main__":
    unittest.main()
