"""Publication boundary tests with synthetic bytes; none are device acceptance results."""
import base64
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import android_release_candidate as policy
import test_android_candidate as candidate_tests


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), Path(__file__).with_name(name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


fetch = module("fetch-android-candidate")
stage = module("stage-android-release")


def write_json(path, record):
    path.write_text(json.dumps(record, sort_keys=True), encoding="utf-8")


WAIVER = {"approved_by": "owner", "approved_at": "2026-09-29T00:40:00Z",
          "reason": "Synthetic fixture waiver; not a real owner decision.", "disclosed_in": "fixture release notes"}


def waived_receipt(receipt):
    """A waived gate records no environment, raw evidence or measured results."""
    for key in ("environment", "raw_evidence"):
        receipt.pop(key)
    receipt.update(status="waived", cases={"synthetic-case-not-run": "waived"}, metrics={}, waiver=dict(WAIVER))
    return receipt


def rewrite(root, acceptance, label, mutate):
    name = f"android-acceptance-{label}.json"
    path = root / "acceptance" / name
    receipt = policy.read_json(path)
    mutate(receipt)
    write_json(path, receipt)
    acceptance["files"][name] = {"sha256": policy.digest(path), "bytes": path.stat().st_size}
    return receipt


def fixture(root, waived=()):
    candidate_dir, acceptance_dir = root / "candidate", root / "acceptance"
    candidate_dir.mkdir(); acceptance_dir.mkdir()
    revision, lock, identity, _ = candidate_tests.AndroidCandidateTests().fixture(candidate_dir)
    lock.update(status="sdk-candidate-imported", signer_identity_observed=True, matches_android_snapshot=True)
    write_json(candidate_dir / "android-controller-sdk-candidate.lock.json", lock)
    candidate = candidate_tests.SEAL.seal(candidate_dir, "10.0.0", revision, lock, identity)
    acceptance = {"schema_version": 1, "repository": policy.REPOSITORY, "status": "accepted_with_waivers" if waived else "passed",
        "acceptance_complete": True, "app_revision": revision, "sdk_revision": lock["source_revision"],
        "candidate_sha256": policy.digest(candidate_dir / policy.MANIFEST),
        "packages": candidate["packages"], "coverage": {}, "files": {}}
    for label in policy.COVERAGE:
        name = f"android-acceptance-{label}.json"
        receipt = {key: value for key, value in acceptance.items() if key not in ("coverage", "files", "acceptance_complete")}
        receipt.update(status="passed", gate=label, environment="synthetic fixture, no devices", reviewed_by="unit-test fixture",
            raw_evidence=[{"location": "fixture://not-real-acceptance", "sha256": "f" * 64}],
            cases={"synthetic-case-not-device-acceptance": "passed"}, metrics={})
        if label == "gemini_screenshots":
            receipt["metrics"] = {"applicable": 2, "reviewed": 2, "approved": 2, "blocking": 0, "actual_images_read": True}
        elif label == "stability":
            receipt["metrics"] = {"consecutive_connections": 30, "connection_failures": 0, "active_seconds": 7200,
                "online_seconds": 86400, "input_release_ms": 2000, "recovery_or_retry_ms": 30000}
        elif label == "arm64_build_signature":
            receipt["metrics"] = {"runtime_scope": "not_run"}
        elif label == "udp_network":
            receipt["metrics"] = {"server_relay_payload_bytes": 0}
        if label in waived:
            waived_receipt(receipt)
        write_json(acceptance_dir / name, receipt)
        acceptance["coverage"][label] = {"status": receipt["status"], "evidence": name}
        acceptance["files"][name] = {"sha256": policy.digest(acceptance_dir / name), "bytes": (acceptance_dir / name).stat().st_size}
    write_json(acceptance_dir / policy.ACCEPTANCE, acceptance)
    return candidate, acceptance, lock


class AndroidPromotionTests(unittest.TestCase):
    def verify(self, root, candidate, acceptance, lock):
        return policy.verify_publication(candidate, root / "candidate", "a" * 40, "b" * 40, "10.0.0", lock,
                                         acceptance, root / "acceptance")

    def test_immutable_candidate_and_separate_acceptance(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate, acceptance, lock = fixture(root)
            self.assertTrue(self.verify(root, candidate, acceptance, lock))
            self.assertIs(candidate["acceptance_complete"], False)
            for field, value in (("acceptance_complete", True), ("schema_version", 1), ("rebuilt", True)):
                with self.subTest(field=field), self.assertRaises(SystemExit):
                    self.verify(root, dict(candidate, **{field: value}), acceptance, lock)
            with self.assertRaises(SystemExit):
                self.verify(root, candidate, acceptance, dict(lock, signer_identity_observed=False))
            for field in ("app_revision", "candidate_sha256", "sdk_revision", "packages", "acceptance_complete"):
                with self.subTest(field=field), self.assertRaises(SystemExit):
                    self.verify(root, candidate, dict(acceptance, **{field: None}), lock)

    def test_partial_stale_and_rehashed_receipts_are_rejected(self):
        for mutation in ("missing", "stale", "raw", "reviewer", "skipped", "coverage", "image", "long_test", "input", "relay"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); candidate, acceptance, lock = fixture(root)
                label = {"long_test": "stability", "input": "stability", "relay": "udp_network"}.get(mutation, "gemini_screenshots")
                name = f"android-acceptance-{label}.json"
                if mutation == "missing":
                    del acceptance["files"][name]
                else:
                    path = root / "acceptance" / name
                    receipt = policy.read_json(path)
                    if mutation == "stale": receipt["app_revision"] = "c" * 40
                    if mutation == "raw": receipt["raw_evidence"] = []
                    if mutation == "reviewer": receipt["reviewed_by"] = ""
                    if mutation == "skipped": receipt["cases"] = {"required-case": "skipped"}
                    if mutation == "coverage": receipt["metrics"]["reviewed"] = 1
                    if mutation == "image": receipt["metrics"]["actual_images_read"] = False
                    if mutation == "long_test": receipt["metrics"]["online_seconds"] = 86399
                    if mutation == "input": receipt["metrics"]["input_release_ms"] = 2001
                    if mutation == "relay": receipt["metrics"]["server_relay_payload_bytes"] = 1
                    write_json(path, receipt)
                    acceptance["files"][name] = {"sha256": policy.digest(path), "bytes": path.stat().st_size}
                with self.assertRaises(SystemExit):
                    self.verify(root, candidate, acceptance, lock)

    SKIPPED = ("x64_api35", "x64_api26", "v9_x64_migration", "vm_tests", "udp_network", "stability", "performance")

    def test_owner_waived_gates_are_accepted_but_not_passed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); candidate, acceptance, lock = fixture(root, waived=self.SKIPPED)
            self.assertTrue(self.verify(root, candidate, acceptance, lock))
            self.assertEqual(acceptance["status"], "accepted_with_waivers")
            self.assertEqual({label for label, item in acceptance["coverage"].items() if item["status"] == "waived"}, set(self.SKIPPED))
            for label in self.SKIPPED:
                receipt = policy.read_json(root / "acceptance" / f"android-acceptance-{label}.json")
                self.assertNotIn("passed", receipt["cases"].values())
                self.assertEqual(receipt["metrics"], {})

    def test_every_gate_except_the_signed_build_is_waivable(self):
        self.assertEqual(set(policy.WAIVABLE), set(policy.COVERAGE) - {"arm64_build_signature"})
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); candidate, acceptance, lock = fixture(root, waived=policy.WAIVABLE)
            self.assertTrue(self.verify(root, candidate, acceptance, lock))

    def test_signed_build_gate_cannot_be_waived(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); candidate, acceptance, lock = fixture(root, waived=("arm64_build_signature",))
            with self.assertRaisesRegex(SystemExit, "cannot be waived: arm64_build_signature"):
                self.verify(root, candidate, acceptance, lock)

    def test_partially_waived_receipt_still_needs_raw_evidence_for_passed_cases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); candidate, acceptance, lock = fixture(root, waived=("stability",))
            rewrite(root, acceptance, "stability", lambda r: r["cases"].update({"synthetic-short-run": "passed"}))
            with self.assertRaisesRegex(SystemExit, "original hashed evidence"):
                self.verify(root, candidate, acceptance, lock)
            rewrite(root, acceptance, "stability", lambda r: r.update(
                raw_evidence=[{"location": "fixture://not-real-acceptance", "sha256": "e" * 64}]))
            self.assertTrue(self.verify(root, candidate, acceptance, lock))

    def test_invalid_owner_waivers_are_rejected(self):
        future = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
        mutations = {
            "no_reason": lambda w: w.pop("reason"), "blank_reason": lambda w: w.update(reason="  "),
            "no_disclosure": lambda w: w.pop("disclosed_in"), "not_owner": lambda w: w.update(approved_by="coordinator"),
            "future": lambda w: w.update(approved_at=future), "naive_time": lambda w: w.update(approved_at="2026-09-29T00:40:00"),
            "bad_time": lambda w: w.update(approved_at="yesterday"), "missing": None}
        for mutation, change in mutations.items():
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); candidate, acceptance, lock = fixture(root, waived=("performance",))
                rewrite(root, acceptance, "performance", lambda r: r.pop("waiver") if change is None else change(r["waiver"]))
                with self.assertRaisesRegex(SystemExit, "Waiver"):
                    self.verify(root, candidate, acceptance, lock)

    def test_waiver_timestamp_tolerates_small_clock_skew(self):
        policy.verify_waiver(dict(WAIVER, approved_at=(datetime.now(timezone.utc) + timedelta(minutes=4)).isoformat()), "vm_tests")
        policy.verify_waiver(dict(WAIVER, approved_at="2026-09-29T08:40:00+08:00"), "vm_tests")

    def test_manifest_status_must_match_recorded_coverage(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); candidate, acceptance, lock = fixture(root, waived=("vm_tests",))
            with self.assertRaisesRegex(SystemExit, "must be accepted_with_waivers"):
                self.verify(root, candidate, dict(acceptance, status="passed"), lock)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); candidate, acceptance, lock = fixture(root)
            with self.assertRaisesRegex(SystemExit, "must be passed"):
                self.verify(root, candidate, dict(acceptance, status="accepted_with_waivers"), lock)

    def test_inconsistent_waived_receipts_are_rejected(self):
        mutations = {
            "failed_case": lambda r: r["cases"].update({"synthetic-case-failed": "failed"}),
            "skipped_case": lambda r: r["cases"].update({"synthetic-case-skipped": "skipped"}),
            "no_waived_case": lambda r: r.update(cases={"synthetic-case": "passed"}),
            "receipt_passed": lambda r: r.update(status="passed"),
            "no_reviewer": lambda r: r.update(reviewed_by=""),
            "wrong_gate": lambda r: r.update(gate="stability")}
        for mutation, change in mutations.items():
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); candidate, acceptance, lock = fixture(root, waived=("udp_network",))
                rewrite(root, acceptance, "udp_network", change)
                with self.assertRaises(SystemExit):
                    self.verify(root, candidate, acceptance, lock)

    def test_passed_gates_cannot_carry_waived_cases_or_waivers(self):
        mutations = {"waived_case": lambda r: r["cases"].update({"synthetic-case-not-run": "waived"}),
                     "waiver": lambda r: r.update(waiver=dict(WAIVER)), "receipt_waived": lambda r: r.update(status="waived"),
                     "no_environment": lambda r: r.pop("environment")}
        for mutation, change in mutations.items():
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); candidate, acceptance, lock = fixture(root)
                rewrite(root, acceptance, "arm64_build_signature", change)
                with self.assertRaises(SystemExit):
                    self.verify(root, candidate, acceptance, lock)

    def test_coverage_entry_must_be_an_exact_passed_or_waived_record(self):
        for entry in ({"status": "skipped", "evidence": "android-acceptance-vm_tests.json"},
                      {"status": "waived", "evidence": "android-acceptance-vm_tests.json", "passed": True}, "waived"):
            with self.subTest(entry=entry), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); candidate, acceptance, lock = fixture(root, waived=("vm_tests",))
                acceptance["coverage"]["vm_tests"] = entry
                with self.assertRaisesRegex(SystemExit, "Missing acceptance result"):
                    self.verify(root, candidate, acceptance, lock)

    def test_changed_installable_and_supporting_files_are_rejected(self):
        for name in ("HomeTunnel-Android-10.0.0-x86_64.apk", "HomeTunnel-Android-10.0.0.aab.spdx.json"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); candidate, acceptance, lock = fixture(root)
                (root / "candidate" / name).write_bytes(b"changed")
                with self.assertRaisesRegex(SystemExit, "bytes differ"):
                    self.verify(root, candidate, acceptance, lock)

    def test_pinned_run_cannot_be_substituted_by_another_job_or_attempt(self):
        run = {"repository": {"full_name": policy.REPOSITORY}, "path": policy.CALLER, "event": "workflow_dispatch",
               "head_sha": "a" * 40, "id": 18, "status": "completed", "conclusion": "success", "run_attempt": 1}
        artifact = {"id": 19, "name": "android-release-candidate", "expired": False, "digest": "sha256:" + "b" * 64,
                    "workflow_run": {"id": 18, "head_sha": "a" * 40}}
        fetch.verify_run(run, artifact, "a" * 40, "18", "19", "b" * 64)
        for field, value in (("path", ".github/workflows/release.yml"), ("head_sha", "c" * 40),
                             ("event", "pull_request"), ("conclusion", "failure"), ("run_attempt", 0)):
            with self.subTest(field=field), self.assertRaises(SystemExit):
                fetch.verify_run(dict(run, **{field: value}), artifact, "a" * 40, "18", "19", "b" * 64)
        for field, value in (("id", 20), ("expired", True), ("digest", "sha256:" + "c" * 64), ("workflow_run", {})):
            with self.subTest(field=field), self.assertRaises(SystemExit):
                fetch.verify_run(run, dict(artifact, **{field: value}), "a" * 40, "18", "19", "b" * 64)

    def test_zip_cannot_escape_or_alias_another_subject(self):
        for names in (("../escape",), ("file:stream",), ("NUL.txt",), ("file", "FILE"), ("folder/file",), ("link",)):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); archive = root / "bundle.zip"
                with zipfile.ZipFile(archive, "w") as output:
                    for name in names:
                        item = zipfile.ZipInfo(name)
                        if name == "link": item.external_attr = (stat.S_IFLNK | 0o777) << 16
                        output.writestr(item, b"fixture")
                with self.assertRaises(SystemExit): fetch.extract(archive, root / "out")
                self.assertFalse((root / "out").exists())

    def test_hub_receipt_bytes_are_bound_to_the_requested_git_object(self):
        data = b'{"test_fixture":true}'
        sha = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
        item = {"type": "file", "path": "validation/android/" + "a" * 40 + "/" + policy.ACCEPTANCE, "sha": sha, "size": len(data)}
        blob = {"encoding": "base64", "content": base64.b64encode(data).decode(), "sha": sha}
        with patch.object(fetch, "api") as network:
            self.assertEqual(fetch.read_hub_file("b" * 40, "a" * 40, policy.ACCEPTANCE,
                lambda path: item if "/contents/" in path else blob), data)
            for altered in (dict(item, type="symlink"), dict(item, sha="c" * 40), dict(item, size=4*1024*1024+1)):
                with self.assertRaises(SystemExit):
                    fetch.read_hub_file("b" * 40, "a" * 40, policy.ACCEPTANCE,
                        lambda path: altered if "/contents/" in path else blob)
            network.assert_not_called()

    def test_staging_preserves_all_original_bytes(self):
        for waived in ((), self.SKIPPED):
            with self.subTest(waived=bool(waived)):
                self.stage_fixture(waived)

    def stage_fixture(self, waived):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); candidate, acceptance, lock = fixture(root, waived)
            subjects = set(candidate["files"]) | {policy.MANIFEST}
            for name in subjects:
                (root / "candidate" / (name + ".sigstore.json")).write_bytes(b"fixture signature, not a real certificate")
            (root / "verification").mkdir()
            for name in [policy.MANIFEST, *(p["name"] for p in candidate["packages"].values())]:
                write_json(root / "verification" / (name + ".verification.json"), {"test_fixture": True})
            write_json(root / "android-candidate-download.json", {"source_revision": "a" * 40, "signatures_verified": True,
                "run_attestations_verified": True, "acceptance_revision": "c" * 40,
                "candidate_sha256": policy.digest(root / "candidate" / policy.MANIFEST)})
            write_json(root / "acceptance/android-acceptance-origin.json", {"test_fixture": True})
            destination = root / "release"
            stage.stage(root, destination, "a" * 40, "10.0.0", lock)
            for original in (root / "candidate").iterdir():
                self.assertEqual(original.read_bytes(), (destination / original.name).read_bytes())
            with self.assertRaisesRegex(SystemExit, "new directory"):
                stage.stage(root, destination, "a" * 40, "10.0.0", lock)


if __name__ == "__main__":
    unittest.main()
