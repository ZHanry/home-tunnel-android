"""SDK import rejects mixed source, modified bytes and wrong build identities."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("client_sdk_policy", Path(__file__).with_name("client_sdk_candidate.py"))
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
SDK = POLICY.SDK
REVISION = "a" * 40
REF = "refs/heads/codex/v10-overhaul"
IDENTITY = "exact fixture identity"


def build_run():
    return {"repository": SDK.REPOSITORY, "path": SDK.PROFILES["client"][0], "event": "workflow_dispatch",
            "head_sha": REVISION, "id": 17, "run_attempt": 2, "status": "completed", "conclusion": "success", "ref": REF}


def seal(directory, record):
    record["files"] = {path.name: {"sha256": SDK.digest(path), "bytes": path.stat().st_size}
                       for path in directory.iterdir() if path.name not in (POLICY.INDEX, POLICY.INDEX + ".sigstore.json")}
    (directory / POLICY.INDEX).write_text(json.dumps(record), encoding="utf-8")


def fixture(directory):
    record = {"schema_version": 1, "repository": SDK.REPOSITORY, "revision": REVISION, "version": "9.0.0",
              "verification_stage": "candidate", "source_modified": False, "tag_published": False, "acceptance_complete": False,
              "build": {"repository": SDK.REPOSITORY, "caller_workflow": SDK.PROFILES["client"][0],
                        "signer_workflow": SDK.PROFILES["client"][1], "run_id": "17", "run_attempt": 2, "source_ref": REF}}
    for abi, token in (("arm64-v8a", "arm64"), ("x86_64", "x86_64")):
        archive = f"HomeTunnel-Remote-SDK-9.0.0-android-{token}.zip"
        suffix = "" if abi == "arm64-v8a" else "-x86_64"
        provenance = f"android-sdk{suffix}-provenance.json"
        sbom = f"android-sdk{suffix}.spdx.json"
        (directory / archive).write_bytes(b"SDK fixture: " + abi.encode())
        archive_hash = SDK.digest(directory / archive)
        (directory / (archive + ".sha256")).write_text(f"{archive_hash}  {archive}\n", encoding="utf-8")
        metadata = {"repository": SDK.REPOSITORY, "source_revision": REVISION, "source_modified": False,
                    "version": "9.0.0", "target": abi, "archive": archive, "archive_sha256": archive_hash,
                    "archive_bytes": (directory / archive).stat().st_size, "source_tree_sha256": "e" * 64,
                    "upstream_lock_sha256": "f" * 64, "recipe_sha256": "b" * 64, "compiler_lock": {"clang": "pinned"},
                    "gn_args": {"target_cpu": abi}, "elf_machine": abi, "controller_backend_linked": True,
                    "production_controller": True, "device_media_accepted": False}
        (directory / provenance).write_text(json.dumps(metadata), encoding="utf-8")
        (directory / sbom).write_text("{}", encoding="utf-8")
        for name in (archive, archive + ".sha256", provenance, sbom):
            (directory / (name + ".sigstore.json")).write_bytes(b"mock verifier input; not a real signature")
    (directory / (POLICY.INDEX + ".sigstore.json")).write_bytes(b"mock original index signature")
    seal(directory, record)
    return record


class ClientSdkTests(unittest.TestCase):
    def test_original_index_and_both_abis_are_verified_without_rewriting_index(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            fixture(directory)
            before = SDK.digest(directory / POLICY.INDEX)
            observed = []
            record = POLICY.verify_tree(directory, REVISION, build_run(), lambda *args: observed.append(args), IDENTITY)
            self.assertEqual(len(observed), 9)
            self.assertEqual(observed[0][0].name, POLICY.INDEX)
            self.assertTrue(all(item[2] == IDENTITY for item in observed))
            self.assertEqual(set(record["abis"]), {"arm64-v8a", "x86_64"})
            self.assertEqual(before, SDK.digest(directory / POLICY.INDEX))
            self.assertFalse((directory / "android-sdk-candidate.json").exists())
            lock = SDK.write_lock(record, REVISION, 17, "d" * 64, True, True, "client")
            self.assertEqual(lock["artifact_name"], "candidate-assets")
            self.assertEqual(lock["signer_workflow"], SDK.PROFILES["client"][1])

    def test_modified_bytes_and_unlisted_subjects_are_rejected(self):
        for extra in (False, True):
            with self.subTest(extra=extra), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                fixture(directory)
                name = "unexpected.txt" if extra else "android-sdk.spdx.json"
                (directory / name).write_bytes(b"changed bytes")
                with self.assertRaises(SystemExit):
                    POLICY.verify_tree(directory, REVISION, build_run(), lambda *args: None, IDENTITY)

    def test_published_or_preapproved_flags_cannot_be_imported_as_a_candidate(self):
        for key in ("source_modified", "tag_published", "acceptance_complete"):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                record = fixture(directory)
                record[key] = True
                seal(directory, record)
                with self.assertRaises(SystemExit):
                    POLICY.verify_tree(directory, REVISION, build_run(), lambda *args: None, IDENTITY)

    def test_source_and_shared_dependency_mismatch_are_rejected_after_valid_index(self):
        for key, value in (("source_revision", "b" * 40), ("source_tree_sha256", "c" * 64),
                           ("upstream_lock_sha256", "c" * 64), ("production_controller", False), ("device_media_accepted", True)):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                record = fixture(directory)
                path = directory / "android-sdk-x86_64-provenance.json"
                metadata = json.loads(path.read_text())
                metadata[key] = value
                path.write_text(json.dumps(metadata), encoding="utf-8")
                seal(directory, record)
                with self.assertRaises(SystemExit):
                    POLICY.verify_tree(directory, REVISION, build_run(), lambda *args: None, IDENTITY)

    def test_workflow_run_attempt_and_ref_must_match_the_fetched_run(self):
        for key, value in (("run_id", "18"), ("run_attempt", 1), ("source_ref", "refs/heads/main"),
                           ("signer_workflow", SDK.SIGNER_WORKFLOW), ("caller_workflow", SDK.CALLER_WORKFLOW)):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                record = fixture(directory)
                record["build"][key] = value
                seal(directory, record)
                with self.assertRaises(SystemExit):
                    POLICY.verify_tree(directory, REVISION, build_run(), lambda *args: None, IDENTITY)

    def test_client_format_is_explicit_and_attestations_use_its_exact_signer(self):
        run = build_run()
        with self.assertRaises(SystemExit):
            SDK.verify_run(run, REVISION, 17)
        SDK.verify_run(run, REVISION, 17, candidate_format="client")
        with self.assertRaises(SystemExit):
            SDK.verify_artifact({"name": "android-sdk-candidate", "digest": "d" * 64}, "d" * 64, "client")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            fixture(directory)
            statement = [{"verificationResult": {"statement": {"subject": [{"digest": {"sha256": SDK.digest(directory / POLICY.INDEX)}}],
                "predicate": {"runDetails": {"metadata": {"invocationId": f"https://github.com/{SDK.REPOSITORY}/actions/runs/17/attempts/2"}}}}}}]
            with patch.object(SDK.subprocess, "check_output", return_value=json.dumps(statement).encode()) as command:
                SDK.verify_attestation(directory / POLICY.INDEX, REVISION, REF, 17, 2, directory / "proof", "client")
                self.assertIn(SDK.REPOSITORY + "/" + SDK.PROFILES["client"][1], command.call_args.args[0])
                with self.assertRaises(SystemExit):
                    SDK.verify_attestation(directory / POLICY.INDEX, REVISION, REF, 17, 3, directory / "proof", "client")

    def test_zip_case_collisions_and_windows_device_paths_are_refused(self):
        for names in (("SDK.json", "sdk.json"), ("CON.json",), ("../escape",)):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                archive = directory / "bad.zip"
                with zipfile.ZipFile(archive, "w") as bundle:
                    for name in names:
                        bundle.writestr(name, b"fixture")
                with self.assertRaises(SystemExit):
                    SDK.extract_artifact(archive, directory / "out", "client")
                self.assertFalse((directory / "out").exists())


if __name__ == "__main__":
    unittest.main()
