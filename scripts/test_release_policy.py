"""Release-stage regressions; these tests do not contact GitHub or publish artifacts."""
from pathlib import Path
import importlib.util
import hashlib
import json
import os
import tempfile
import unittest
import zipfile
import copy
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
    def test_public_documentation_uses_objective_test_coverage(self):
        root = Path(__file__).resolve().parents[1]
        documents = [*root.glob("README*.md"), *(root / "docs").rglob("*.md")]
        for document in documents:
            with self.subTest(path=document.relative_to(root)):
                self.assertNotRegex(document.read_text(encoding="utf-8"), r"(?i)owner[\s_-]+waiv|负责人豁免")
        policy = json.loads((root / "compatibility.json").read_text())["support_policy"]
        self.assertNotRegex(policy, r"(?i)owner[\s_-]+waiv|负责人豁免")

    def frozen_contract_fixture(self):
        project = {"contract_ref": "api-v1.4.0", "contract_status": "frozen", "frozen_tag": "api-v1.4.0"}
        lock = {"repository": "ZHanry/home-tunnel-server", "contract_status": "frozen",
                "frozen_tag": "api-v1.4.0", "published_contract_ref": "api-v1.4.0",
                "source_revision": "a" * 40, "source_tree_dirty": False}
        return project, [dict(lock, ref="api-v1.4.0"), dict(lock)]

    def test_proposed_or_missing_freeze_cannot_enter_publication(self):
        project, locks = self.frozen_contract_fixture()
        self.assertEqual(module.validate_frozen_contract(project, locks), ("api-v1.4.0", "a" * 40))
        for key, value in (("contract_status", "proposed"), ("frozen_tag", None), ("contract_ref", "main")):
            with self.subTest(key=key), self.assertRaisesRegex(SystemExit, "frozen contract"):
                module.validate_frozen_contract(dict(project, **{key: value}), locks)
        for index in (0, 1):
            for key, value in (("contract_status", "proposed"), ("published_contract_ref", None),
                               ("frozen_tag", None), ("source_tree_dirty", True), ("source_revision", "main"),
                               ("repository", "somebody/other-server")):
                altered = copy.deepcopy(locks); altered[index][key] = value
                with self.subTest(index=index, key=key), self.assertRaises(SystemExit):
                    module.validate_frozen_contract(project, altered)
        locks[1]["source_revision"] = "b" * 40
        with self.assertRaisesRegex(SystemExit, "different server commits"):
            module.validate_frozen_contract(project, locks)

    def test_contract_tag_must_resolve_to_the_pinned_revision(self):
        project, locks = self.frozen_contract_fixture()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / "contracts").mkdir()
            for name, lock in zip(("lock.json", "remote.lock.json"), locks):
                (root / "contracts" / name).write_text(json.dumps(lock), encoding="utf-8")
            with patch.object(module, "ROOT", root), patch.object(module, "PROJECT", project):
                with patch.object(module, "api", return_value={"object": {"type": "commit", "sha": "a" * 40}}):
                    module.check_frozen_contract()
                with patch.object(module, "api", side_effect=[{"object": {"type": "tag", "sha": "b" * 40}},
                                                               {"object": {"type": "commit", "sha": "a" * 40}}]) as query:
                    module.check_frozen_contract()
                    self.assertEqual(query.call_args.args[0], "repos/ZHanry/home-tunnel-server/git/tags/" + "b" * 40)
                for response in ({"type": "commit", "sha": "b" * 40}, {"type": "tree", "sha": "a" * 40},
                                 {"type": "tag", "sha": "b" * 40}):
                    with self.subTest(response=response), patch.object(module, "api", return_value={"object": response}):
                        with self.assertRaisesRegex(SystemExit, "Frozen contract tag"):
                            module.check_frozen_contract()
                with patch.object(module, "PROJECT", dict(project, contract_status="proposed")), patch.object(module, "api") as query:
                    with self.assertRaisesRegex(SystemExit, "candidate-only"):
                        module.check_frozen_contract()
                    query.assert_not_called()

    def test_candidate_identity_and_certificate_are_preserved(self):
        root = Path(__file__).resolve().parents[1]
        compatibility = json.loads((root / "compatibility.json").read_text())
        properties = (root / "gradle.properties").read_text()
        self.assertEqual((compatibility["version"], compatibility["stage"]), ("10.0.0", "public-release"))
        self.assertIn("HOME_TUNNEL_VERSION_NAME=10.0.0\n", properties)
        self.assertIn("HOME_TUNNEL_VERSION_CODE=10000000\n", properties)
        self.assertEqual((root / "release-signing-cert.sha256").read_text().strip(), "d7779e338be1039acee6dda9a43417cbf2baf4b0c9995578d9708501e95af702")
        self.assertIn('applicationId = "io.github.zhanry.hometunnel"', (root / "app/build.gradle.kts").read_text())

    def test_seal_requires_both_controller_abis_and_their_original_notices(self):
        import shutil
        import test_native_package_abis as package_fixture
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); native_dir = root / "native"; native_dir.mkdir()
            output = root / "release"; output.mkdir()
            (root / "scripts").mkdir()
            shutil.copyfile(script.parent / "verify-remote-packages.py", root / "scripts/verify-remote-packages.py")
            license_bytes = (script.parent.parent / "LICENSE").read_bytes()
            (root / "LICENSE").write_bytes(license_bytes)
            certificate = "d" * 64
            (root / "release-signing-cert.sha256").write_text(certificate)
            (root / "gradle.properties").write_text("HOME_TUNNEL_VERSION_CODE=10000000\n")
            source = {"source_revision": "a" * 40, "source_tree_sha256": "b" * 64, "source_files": {"fixture": "c" * 64}}
            lock = {"abis": {}}
            facts = {"signing_certificate_sha256": certificate, "version_code": 10000000, "packages": {}}
            def write(path, record):
                path.write_text(json.dumps(record), encoding="utf-8")
            for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
                library = package_fixture.elf(machine)
                library_sha = hashlib.sha256(library).hexdigest()
                contents = {"PROJECT-LICENSE": license_bytes, "WEBRTC-LICENSE.md": b"linked engine notice",
                            "NDK-NOTICE": b"source notice", "NDK-NOTICE.toolchain": b"runtime notice"}
                build = dict(source, target=abi, source_modified=False, controller_backend_linked=True, device_media_accepted=False,
                    files={"LICENSE.md": hashlib.sha256(contents["WEBRTC-LICENSE.md"]).hexdigest(), f"lib/{abi}/libhome_tunnel_remote.so": library_sha})
                provenance = {"source_revision": source["source_revision"], "target": abi, "archive_sha256": "e" * 64}
                build_path, provenance_path = output / f"android-controller-build-{abi}.json", output / f"android-controller-sdk-provenance-{abi}.json"
                write(build_path, build); write(provenance_path, provenance)
                pinned = {"controller_manifest_sha256": hashlib.sha256(build_path.read_bytes()).hexdigest(),
                    "provenance_sha256": hashlib.sha256(provenance_path.read_bytes()).hexdigest(), "archive_sha256": "e" * 64, "library_sha256": library_sha}
                lock["abis"][abi] = pinned
                native = dict(source, target=abi, available=True, device_media_accepted=False,
                              controller_manifest_sha256=pinned["controller_manifest_sha256"], library_sha256=library_sha)
                write(output / f"android-native-evidence-{abi}.json", native)
                notice = {"schema_version": 1, "ndk_version": "27.2.12479018", "source_revision": source["source_revision"],
                          "library_sha256": library_sha, "files": {k: hashlib.sha256(v).hexdigest() for k, v in contents.items()}}
                contents["native-notices.json"] = json.dumps(notice).encode()
                for key, value in contents.items():
                    (output / f"android-native-{abi}-{key}").write_bytes(value)
                packages = [(abi, f"HomeTunnel-Android-10.0.0-{abi}.apk", "")]
                if abi == "arm64-v8a": packages.append(("aab", "HomeTunnel-Android-10.0.0.aab", "base/"))
                for key, name, prefix in packages:
                    package_path = output / name
                    with zipfile.ZipFile(package_path, "w") as package:
                        for lib in ("libhome_tunnel_remote.so", "libhome_tunnel_remote_jni.so", "libc++_shared.so"):
                            package.writestr(f"{prefix}lib/{abi}/{lib}", library)
                        for notice_name, data in contents.items():
                            package.writestr(prefix + "assets/licenses/" + notice_name, data)
                    facts["packages"][key] = {"libraries": package_fixture.packages.package_libraries(package_path, f"{prefix}lib/{abi}/", library_sha, abi),
                        "notices": notice, "certificate_sha256": certificate, "application_id": "io.github.zhanry.hometunnel",
                        "version_code": 10000000, "version_name": "10.0.0", "debuggable": False}
            for name, data in (("controller-sdk-candidate.lock.json", lock), ("remote-source.lock.json", source)):
                write(native_dir / name, data)
            shutil.copyfile(native_dir / "controller-sdk-candidate.lock.json", output / "android-controller-sdk-candidate.lock.json")
            shutil.copyfile(native_dir / "remote-source.lock.json", output / "android-native-source.lock.json")
            write(output / "android-release-evidence.json", facts)
            with patch.object(module, "ROOT", root):
                module.verify_controller_evidence(output, "10.0.0")
                changed = output / "android-native-x86_64-NDK-NOTICE"
                original = changed.read_bytes(); changed.write_bytes(b"changed")
                with self.assertRaisesRegex(SystemExit, "notices differ"):
                    module.verify_controller_evidence(output, "10.0.0")
                changed.write_bytes(original)
                evidence = output / "android-native-evidence-x86_64.json"
                native = json.loads(evidence.read_text()); native["library_sha256"] = "f" * 64
                write(evidence, native)
                with self.assertRaisesRegex(SystemExit, "library/source identity"):
                    module.verify_controller_evidence(output, "10.0.0")

    def test_contract_ref_accepts_only_exact_ascii_stable_or_rc_versions(self):
        for value in ("api-v0.0.0", "api-v1.2.0", "api-v1.2.0-rc.1", "api-v12.30.4-rc.123"):
            with self.subTest(value=value):
                self.assertTrue(repository_policy.valid_contract_ref(value))
        for value in ("main", "api-v1.2", "api-v01.2.0", "api-v1.02.0", "api-v1.2.00",
                      "api-v1.2.0-rc.0", "api-v1.2.0-rc.01", "api-v1.2.0-rc.佟",
                      "api-v佟.2.0", "api-v1.2.0+build", "api-v1.2.0-rc.1+build",
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

    def test_stable_notes_list_unverified_coverage_without_rewriting_evidence(self):
        import test_android_promotion as promotion
        waived = ("vm_tests", "stability")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            promotion.fixture(root, waived=waived)
            originals = {path: path.read_bytes() for path in (root / "acceptance").iterdir()}
            notes = module.waiver_notes(root / "acceptance")
            self.assertIn("## Not verified\n", notes)
            for label in waived:
                self.assertIn(f"- `{label}`: not verified. Unverified cases: `synthetic-case-not-run`.", notes)
            self.assertNotIn("waiv", notes.lower())
            self.assertNotIn("owner", notes.lower())
            for path, original in originals.items():
                self.assertEqual(path.read_bytes(), original)
            for label in set(promotion.policy.COVERAGE) - set(waived):
                self.assertNotIn(f"`{label}`", notes)
            self.assertEqual(module.waiver_notes(root / "candidate"), "")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            promotion.fixture(root)
            self.assertEqual(module.waiver_notes(root / "acceptance"), "")

    def test_public_asset_list_keeps_only_installable_deliverables(self):
        self.assertEqual(module.public_asset_names("android", "6.0.0"), ["HomeTunnel-Android-6.0.0-arm64-v8a.apk", "HomeTunnel-Android-6.0.0-x86_64.apk"])
        client = module.public_asset_names("client", "6.0.0")
        self.assertEqual(len(client), 6)
        self.assertTrue(all(name.endswith((".exe", ".zip", ".tar.gz")) for name in client))
        self.assertEqual(module.public_asset_names("server", "6.0.0"), ["home-tunnel-server-6.0.0.tar.gz", "compose.release.yaml"])

if __name__ == "__main__":
    unittest.main()
