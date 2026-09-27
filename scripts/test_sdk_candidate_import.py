"""Negative tests for SDK candidate identity and stable publication acceptance."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent


def load(filename, module_name):
    spec = importlib.util.spec_from_file_location(module_name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CANDIDATE = load("import-sdk-candidate.py", "import_sdk_candidate")
PUBLICATION = load("android_release_candidate.py", "android_release_candidate")
ABI = load("remote_production_abi.py", "remote_production_abi")


def run():
    return {"repository": CANDIDATE.REPOSITORY, "path": CANDIDATE.CALLER_WORKFLOW, "event": "workflow_dispatch",
            "head_sha": "a" * 40, "id": 17, "status": "completed", "conclusion": "success", "ref": "refs/heads/codex/v10-overhaul"}


class CandidateIdentityTests(unittest.TestCase):
    def signed_fixture(self, directory):
        record = {"verification_stage": "candidate", "tag_published": False, "stable_release": False,
                  "repository": CANDIDATE.REPOSITORY, "source_revision": "a" * 40,
                  "source_tree_sha256": "e" * 64, "upstream_lock_sha256": "f" * 64, "version": "9.0.0",
                  "caller_workflow": CANDIDATE.CALLER_WORKFLOW, "signer_workflow": CANDIDATE.SIGNER_WORKFLOW,
                  "caller_event": "workflow_dispatch", "device_media_accepted": False, "workflow_run_id": "17", "abis": {}}
        for abi in CANDIDATE.ABIS:
            archive = directory / (abi + ".zip")
            archive.write_bytes(abi.encode())
            checksum = CANDIDATE.digest(archive)
            (directory / (archive.name + ".sha256")).write_text(f"{checksum}  {archive.name}\n", encoding="utf-8")
            provenance_name = abi + "-provenance.json"
            sbom_name = abi + "-sbom.json"
            item = {"archive": archive.name, "archive_sha256": checksum, "archive_bytes": archive.stat().st_size,
                    "provenance": provenance_name, "sbom": sbom_name, "source_tree_sha256": "e" * 64,
                    "upstream_lock_sha256": "f" * 64, "recipe_sha256": "b" * 64,
                    "compiler_lock": {"clang": "pinned"}, "gn_args": {"target_cpu": abi}, "elf_machine": abi,
                    "controller_backend_linked": True, "production_controller": True, "device_media_accepted": False}
            provenance = {key: value for key, value in item.items() if key not in ("provenance", "sbom")}
            provenance.update(repository=CANDIDATE.REPOSITORY, source_revision="a" * 40, version="9.0.0", target=abi, source_modified=False)
            (directory / provenance_name).write_text(json.dumps(provenance), encoding="utf-8")
            (directory / sbom_name).write_text("{}", encoding="utf-8")
            item["provenance_sha256"] = CANDIDATE.digest(directory / provenance_name)
            item["sbom_sha256"] = CANDIDATE.digest(directory / sbom_name)
            item["subjects"] = []
            for name in (archive.name, archive.name + ".sha256", provenance_name, sbom_name):
                path = directory / name
                bundle = directory / (name + ".sigstore.json")
                bundle.write_bytes(b"test signature verifier fixture")
                item["subjects"].append({"name": name, "sha256": CANDIDATE.digest(path), "bytes": path.stat().st_size,
                                         "sigstore_bundle": bundle.name, "sigstore_sha256": CANDIDATE.digest(bundle)})
            record["abis"][abi] = item
        return record

    def test_complete_signature_inventory_and_cross_abi_binding(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            record = self.signed_fixture(directory)
            index = directory / "android-sdk-candidate.json"
            index.write_text(json.dumps(record))
            observed = []
            CANDIDATE.verify_tree(directory, "a" * 40, lambda *args: observed.append(args))
            self.assertEqual(len(observed), 8)
            mutations = (
                lambda value: value["abis"]["x86_64"].update(subjects=[]),
                lambda value: value["abis"]["x86_64"].update(provenance="../escape"),
                lambda value: value.update(source_tree_sha256="c" * 64),
                lambda value: value["abis"]["arm64-v8a"]["subjects"][0].update(sigstore_bundle="x86_64.zip.sigstore.json"),
                lambda value: value["abis"]["arm64-v8a"]["subjects"][0].update(bytes=999),
                lambda value: value["abis"]["arm64-v8a"].update(gn_args={"target_cpu": "different"}),
            )
            for mutation in mutations:
                broken = json.loads(json.dumps(record))
                mutation(broken)
                index.write_text(json.dumps(broken))
                with self.assertRaises(SystemExit):
                    CANDIDATE.verify_tree(directory, "a" * 40, lambda *args: None)
            index.write_text(json.dumps(record))
            (directory / "unsealed-extra").write_text("untrusted")
            with self.assertRaisesRegex(SystemExit, "unexpected"):
                CANDIDATE.verify_tree(directory, "a" * 40, lambda *args: None)

    def test_valid_signature_for_a_different_run_or_attempt_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            subject = directory / "candidate.json"
            subject.write_bytes(b"same bytes can be built twice")
            def response(attempt):
                return json.dumps([{"verificationResult": {"statement": {
                    "subject": [{"digest": {"sha256": CANDIDATE.digest(subject)}}],
                    "predicate": {"runDetails": {"metadata": {"invocationId":
                        f"https://github.com/{CANDIDATE.REPOSITORY}/actions/runs/17/attempts/{attempt}"}}}
                }}}]).encode()
            with patch.object(CANDIDATE.subprocess, "check_output", return_value=response(1)):
                CANDIDATE.verify_attestation(subject, "a" * 40, "refs/heads/main", "17", 1, directory / "proof")
                with self.assertRaisesRegex(SystemExit, "run/attempt"):
                    CANDIDATE.verify_attestation(subject, "a" * 40, "refs/heads/main", "18", 1, directory / "proof")
                with self.assertRaisesRegex(SystemExit, "run/attempt"):
                    CANDIDATE.verify_attestation(subject, "a" * 40, "refs/heads/main", "17", 2, directory / "proof")

    def test_wrong_repository_workflow_and_conclusion_are_rejected(self):
        good = run()
        CANDIDATE.verify_run(good, "a" * 40, 17, "codex/v10-overhaul")
        for key, value in (("repository", "other/repo"), ("path", ".github/workflows/release.yml"),
                           ("event", "push"), ("head_sha", "b" * 40), ("conclusion", "failure")):
            broken = dict(good)
            broken[key] = value
            with self.subTest(key=key), self.assertRaises(SystemExit):
                CANDIDATE.verify_run(broken, "a" * 40, 17)
        with self.assertRaises(SystemExit):
            CANDIDATE.verify_artifact({"name": "android-sdk-candidate", "digest": "sha256:" + "c" * 64}, "d" * 64)

    def test_wrong_archive_and_unconfirmed_signer_do_not_import(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            archive = directory / "sdk.zip"
            archive.write_bytes(b"candidate")
            provenance = {"archive_sha256": hashlib.sha256(b"candidate").hexdigest(), "target": "arm64-v8a", "source_modified": False}
            (directory / "prov.json").write_bytes(json.dumps(provenance).encode())
            (directory / "sbom.json").write_bytes(b"{}")
            (directory / "sdk.zip.sigstore.json").write_bytes(b"bundle")
            subject = {"name": "sdk.zip", "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                       "sigstore_bundle": "sdk.zip.sigstore.json", "sigstore_sha256": hashlib.sha256(b"bundle").hexdigest()}
            item = {"archive": "sdk.zip", "archive_sha256": "0" * 64, "provenance": "prov.json",
                    "provenance_sha256": hashlib.sha256((directory / "prov.json").read_bytes()).hexdigest(),
                    "sbom": "sbom.json", "sbom_sha256": hashlib.sha256(b"{}").hexdigest(),
                    "production_controller": True, "controller_backend_linked": True, "device_media_accepted": False,
                    "source_tree_sha256": "e" * 64, "upstream_lock_sha256": "f" * 64, "subjects": [subject]}
            other = dict(item)
            record = {"verification_stage": "candidate", "tag_published": False, "stable_release": False,
                      "repository": CANDIDATE.REPOSITORY, "source_revision": "a" * 40,
                      "caller_workflow": CANDIDATE.CALLER_WORKFLOW, "signer_workflow": CANDIDATE.SIGNER_WORKFLOW,
                      "caller_event": "workflow_dispatch", "device_media_accepted": False, "workflow_run_id": "17",
                      "abis": {"arm64-v8a": item, "x86_64": other}}
            (directory / "android-sdk-candidate.json").write_text(json.dumps(record))
            seen = []

            def verify_blob(path, bundle, identity):
                seen.append(identity)
                raise SystemExit("wrong provenance")

            with self.assertRaisesRegex(SystemExit, "archive digest|wrong provenance"):
                CANDIDATE.verify_tree(directory, "a" * 40, verify_blob)
            self.assertFalse(seen)
            lock = CANDIDATE.write_lock(record, "a" * 40, 17, "d" * 64, False, False)
            self.assertFalse(lock["signer_identity_observed"])
            self.assertEqual(lock["signer_workflow"], CANDIDATE.SIGNER_WORKFLOW)


# Immutable publication and acceptance boundary regressions live in test_android_promotion.py.


class ProductionAbiTests(unittest.TestCase):
    def elf(self, machine, align):
        blob = bytearray(120)
        blob[:6] = b"\x7fELF\x02\x01"
        blob[18:20] = machine.to_bytes(2, "little")
        blob[32:40] = (64).to_bytes(8, "little")
        blob[54:56] = (56).to_bytes(2, "little")
        blob[56:58] = (1).to_bytes(2, "little")
        blob[64:68] = (1).to_bytes(4, "little")
        blob[112:120] = align.to_bytes(8, "little")
        return bytes(blob)

    def test_emulator_and_wrong_alignment_are_rejected(self):
        ABI.require_library_bytes = None
        ABI.require_production_manifest({
            "production_controller": True, "controller_backend_linked": True, "source_modified": False,
            "available": False,
            "target": "x86_64", "elf_machine": "Advanced Micro Devices X86-64", "android_api": 26,
            "page_size": 16384, "device_media_accepted": False, "compiler_lock": {"clang": "locked"},
            "gn_args": {"target_cpu": "x64", "is_debug": False},
        }, "x86_64")
        with self.assertRaisesRegex(SystemExit, "emulator|rewritten"):
            ABI.require_production_manifest({"test_only": True, "source_modified": True}, "x86_64")
        self.assertEqual(ABI.elf_machine_and_alignment(self.elf(62, 16384)), 62)
        with self.assertRaisesRegex(SystemExit, "16 KiB"):
            ABI.elf_machine_and_alignment(self.elf(62, 4096))
        with self.assertRaisesRegex(SystemExit, "ELF machine"):
            path_machine = self.elf(183, 16384)
            # arm64 bytes are not an x64 production library
            import tempfile
            from pathlib import Path as FilePath
            with tempfile.TemporaryDirectory() as temporary:
                library = FilePath(temporary) / "libhome_tunnel_remote.so"
                library.write_bytes(path_machine)
                ABI.require_library(library, "x86_64")


if __name__ == "__main__":
    unittest.main()
