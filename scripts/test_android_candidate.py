"""Candidate-boundary fixtures. These bytes are not installable or runtime-accepted packages."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), Path(__file__).with_name(name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


VERIFY = module("verify-android-candidate")
SEAL = module("seal-android-release-candidate")


class AndroidCandidateTests(unittest.TestCase):
    def test_persistent_signer_requires_v2_and_rejects_ambiguous_outputs(self):
        certificate = "a" * 64
        prefix = "Verified using v2 scheme (APK Signature Scheme v2): true\n"
        identity = f"Signer #1 certificate SHA-256 digest: {certificate}\n"
        self.assertEqual(VERIFY.apk_certificate(prefix + identity), certificate)
        self.assertEqual(VERIFY.apk_certificate(prefix + f"V3.1 Signer: certificate SHA-256 digest: {certificate}\n"), certificate)
        for output in (identity, prefix, prefix + identity + "Signer #2 certificate SHA-256 digest: " + certificate,
                       prefix + identity + "V3.1 Signer: certificate SHA-256 digest: " + "b" * 64):
            with self.subTest(output=output), self.assertRaises(SystemExit):
                VERIFY.apk_certificate(output)
        self.assertEqual(VERIFY.jar_certificate("  SHA256: " + ":".join(["AB"] * 32)), "ab" * 32)
        with self.assertRaises(SystemExit):
            VERIFY.jar_certificate("SHA256: " + "a" * 64 + "\nSHA256: " + "b" * 64)

    def test_production_identity_cannot_use_debug_app_or_changed_version(self):
        facts = {"application_id": VERIFY.APPLICATION, "version_name": "10.0.0", "version_code": 10000000,
                 "min_sdk": 26, "target_sdk": 35, "debuggable": False, "certificate_sha256": "a" * 64}
        VERIFY.validate_manifest(facts, "10.0.0", 10000000, "a" * 64)
        for key, value in (("application_id", VERIFY.APPLICATION + ".debug"), ("version_code", 9000000),
                           ("version_name", "9.0.0"), ("debuggable", True), ("debuggable", None),
                           ("min_sdk", 27), ("target_sdk", 34), ("certificate_sha256", "b" * 64)):
            with self.subTest(key=key), self.assertRaises(SystemExit):
                VERIFY.validate_manifest(dict(facts, **{key: value}), "10.0.0", 10000000, "a" * 64)

    def fixture(self, directory):
        revision, sdk_revision = "a" * 40, "b" * 40
        lock = {"source_revision": sdk_revision, "workflow_run_id": "17"}
        (directory / "android-controller-sdk-candidate.lock.json").write_text(json.dumps(lock))
        packages = {}
        for abi in ("arm64-v8a", "x86_64", "aab"):
            name = f"HomeTunnel-Android-10.0.0-{abi}.apk" if abi != "aab" else "HomeTunnel-Android-10.0.0.aab"
            data = abi.encode()
            (directory / name).write_bytes(data)
            (directory / (name + ".spdx.json")).write_text(json.dumps({"spdxVersion": "SPDX-2.3", "packages": []}))
            packages[abi] = {"name": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        evidence = {"schema_version": 2, "status": "passed", "repository_revision": revision, "sdk_revision": sdk_revision,
                    "runtime_acceptance": "pending", "version_name": "10.0.0", "packages": packages}
        (directory / "android-release-evidence.json").write_text(json.dumps(evidence))
        identity = {"repository": "ZHanry/home-tunnel-android", "run_id": "18", "run_attempt": 1, "source_ref": "refs/heads/codex/v10-overhaul"}
        return revision, lock, identity, evidence

    def test_seal_covers_all_abis_and_never_claims_acceptance(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            revision, lock, identity, _ = self.fixture(directory)
            result = SEAL.seal(directory, "10.0.0", revision, lock, identity)
            self.assertFalse(result["acceptance_complete"])
            self.assertFalse(result["tag_published"])
            self.assertEqual(set(result["packages"]), {"arm64-v8a", "x86_64", "aab"})
            self.assertEqual(set(result["files"]), {p.name for p in directory.iterdir()} - {"android-release-candidate.json"})
            for name, item in result["files"].items():
                self.assertEqual(item["sha256"], hashlib.sha256((directory / name).read_bytes()).hexdigest())
            with self.assertRaisesRegex(SystemExit, "never overwritten"):
                SEAL.seal(directory, "10.0.0", revision, lock, identity)

    def test_seal_rejects_stale_verification_or_replaced_x64_package(self):
        for mutation in ("app", "sdk", "runtime", "x64"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                revision, lock, identity, original = self.fixture(directory)
                evidence = copy.deepcopy(original)
                if mutation == "x64":
                    (directory / evidence["packages"]["x86_64"]["name"]).write_bytes(b"replacement")
                else:
                    key = {"app": "repository_revision", "sdk": "sdk_revision", "runtime": "runtime_acceptance"}[mutation]
                    evidence[key] = "changed"
                    (directory / "android-release-evidence.json").write_text(json.dumps(evidence))
                with self.assertRaises(SystemExit):
                    SEAL.seal(directory, "10.0.0", revision, lock, identity)
                self.assertFalse((directory / "android-release-candidate.json").exists())

    def test_sdk_subject_names_follow_the_committed_candidate_format(self):
        committed = json.loads((Path(__file__).parents[1] / "native/controller-sdk-candidate.lock.json").read_text())
        index, provenance = VERIFY.sdk_subject_names(committed)
        # The committed lock pins the full client candidate; its signed index is not the standalone SDK index.
        self.assertEqual(index, "client-candidate.json")
        self.assertEqual(provenance, {"arm64-v8a": "android-sdk-provenance.json", "x86_64": "android-sdk-x86_64-provenance.json"})
        standalone = dict(committed, caller_workflow=".github/workflows/android-webrtc.yml",
                          signer_workflow=".github/workflows/android-sdk-candidate.yml", artifact_name="android-sdk-candidate")
        self.assertEqual(VERIFY.sdk_subject_names(standalone), ("android-sdk-candidate.json", None))
        with self.assertRaises(SystemExit):
            VERIFY.sdk_subject_names(dict(committed, artifact_name="unexpected"))


if __name__ == "__main__":
    unittest.main()
