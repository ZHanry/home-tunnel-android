"""Release-stage regressions; these tests do not contact GitHub or publish artifacts."""
from pathlib import Path
import importlib.util
import hashlib
import json
import os
import tempfile
import unittest
import zipfile
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

repository_spec = importlib.util.spec_from_file_location(
    "repository_policy_under_test", Path(__file__).with_name("check-repository.py"))
repository_policy = importlib.util.module_from_spec(repository_spec)
repository_spec.loader.exec_module(repository_policy)

class ReleasePolicyTests(unittest.TestCase):
    def test_committed_8_0_release_identity_and_certificate_are_preserved(self):
        root = Path(__file__).resolve().parents[1]
        compatibility = json.loads((root / "compatibility.json").read_text())
        properties = (root / "gradle.properties").read_text()
        self.assertEqual((compatibility["version"], compatibility["stage"]), ("8.0.0", "public-release"))
        self.assertIn("HOME_TUNNEL_VERSION_NAME=8.0.0\n", properties)
        self.assertIn("HOME_TUNNEL_VERSION_CODE=8000002\n", properties)
        self.assertEqual((root / "release-signing-cert.sha256").read_text().strip(), "d7779e338be1039acee6dda9a43417cbf2baf4b0c9995578d9708501e95af702")
        self.assertIn('applicationId = "io.github.zhanry.hometunnel"', (root / "app/build.gradle.kts").read_text())

    def test_seal_requires_the_same_controller_library_as_the_reviewed_sdk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); native = root / "native"; native.mkdir()
            output = root / "release"; output.mkdir()
            source = {"source_revision": "a" * 40, "source_tree_sha256": "b" * 64, "source_files": {"fixture": "c" * 64}}
            build = dict(source, source_modified=False, controller_backend_linked=True, device_media_accepted=False,
                         files={"lib/arm64-v8a/libhome_tunnel_remote.so": "d" * 64})
            provenance = {"source_revision": source["source_revision"], "tag": "v8.0.0-rc.1", "archive": "SDK.zip", "archive_sha256": "e" * 64}
            build_bytes = json.dumps(build).encode(); provenance_bytes = json.dumps(provenance).encode()
            lock = {"tag": provenance["tag"], "asset": provenance["archive"], "sha256": provenance["archive_sha256"],
                    "controller_manifest_sha256": hashlib.sha256(build_bytes).hexdigest(), "provenance_sha256": hashlib.sha256(provenance_bytes).hexdigest()}
            for name, data in (("controller-sdk.lock.json", lock), ("remote-source.lock.json", source)):
                (native / name).write_text(json.dumps(data))
            (output / "android-controller-sdk.lock.json").write_bytes((native / "controller-sdk.lock.json").read_bytes())
            (output / "android-native-source.lock.json").write_bytes((native / "remote-source.lock.json").read_bytes())
            (output / "android-controller-build.json").write_bytes(build_bytes)
            (output / "android-controller-sdk-provenance.json").write_bytes(provenance_bytes)
            evidence = {"status": "webrtc-controller-linked-device-acceptance-required", "available": True, "device_media_accepted": False,
                        "source_revision": source["source_revision"], "controller_manifest_sha256": lock["controller_manifest_sha256"], "library_sha256": "d" * 64}
            (output / "android-native-evidence.json").write_text(json.dumps(evidence))
            with zipfile.ZipFile(output / "HomeTunnel-Android-8.0.0-rc.1-arm64-v8a.apk", "w") as package:
                for name in ("PROJECT-LICENSE", "WEBRTC-LICENSE.md", "NDK-NOTICE", "NDK-NOTICE.toolchain", "native-notices.json"):
                    package.writestr("assets/licenses/" + name, b"fixture notice")
                    (output / ("android-native-" + name)).write_bytes(b"fixture notice")
            with patch.object(module, "ROOT", root), patch.object(module, "run") as verify_packages:
                module.verify_controller_evidence(output, "8.0.0-rc.1")
                verify_packages.assert_called_once()  # Real APK/AAB ELF/hash checks run separately; no media acceptance is claimed here.
                (output / "android-native-NDK-NOTICE").write_bytes(b"changed fixture")
                with self.assertRaisesRegex(SystemExit, "notices differ"):
                    module.verify_controller_evidence(output, "8.0.0-rc.1")
                evidence["library_sha256"] = "f" * 64
                (output / "android-native-evidence.json").write_text(json.dumps(evidence))
                with self.assertRaisesRegex(SystemExit, "library/source identity"):
                    module.verify_controller_evidence(output, "8.0.0-rc.1")

    def test_contract_ref_accepts_only_exact_ascii_stable_or_rc_versions(self):
        for value in ("api-v0.0.0", "api-v1.2.0", "api-v1.2.0-rc.1", "api-v12.30.4-rc.123"):
            with self.subTest(value=value):
                self.assertTrue(repository_policy.valid_contract_ref(value))
        for value in ("main", "api-v1.2", "api-v01.2.0", "api-v1.02.0", "api-v1.2.00",
                      "api-v1.2.0-rc.0", "api-v1.2.0-rc.01", "api-v1.2.0-rc.١",
                      "api-v١.2.0", "api-v1.2.0+build", "api-v1.2.0-rc.1+build",
                      "api-v1.2.0/bad", "api-v1.2.0\n", "api-v1.2.0-rc.1\n", None):
            with self.subTest(value=value):
                self.assertFalse(repository_policy.valid_contract_ref(value))

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
