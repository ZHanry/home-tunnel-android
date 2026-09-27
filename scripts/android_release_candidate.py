"""Validate immutable candidate bytes and separate acceptance receipts.

These checks validate recorded evidence, not the act of performing device tests.
The release coordinator reviews the original evidence before committing receipts.
"""
import hashlib
import json
from pathlib import Path
import re

REPOSITORY = "ZHanry/home-tunnel-android"
CALLER = ".github/workflows/ci.yml"
SIGNER = ".github/workflows/android-candidate.yml"
ACCEPTANCE_REPOSITORY = "ZHanry/home-tunnel"
COVERAGE = ("x64_api35", "x64_api26", "arm64_build_signature", "gemini_screenshots",
            "v9_x64_migration", "vm_tests", "udp_network", "stability", "performance")
MANIFEST = "android-release-candidate.json"
ACCEPTANCE = "android-release-acceptance.json"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def filename(name):
    reserved = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}
    if (not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,180}", name)
            or name.endswith(".") or name.split(".")[0].upper() in reserved):
        raise SystemExit("Unsafe release evidence filename")
    return name


def local_file(directory, name):
    path = directory / filename(name)
    if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(directory.resolve()):
        raise SystemExit("Missing or linked release evidence file: " + name)
    return path


def read_json(path):
    if not 0 < path.stat().st_size <= 16 * 1024 * 1024:
        raise SystemExit("Empty or oversized release evidence")
    return json.loads(path.read_text(encoding="utf-8"))


def verify_files(files, directory):
    if not isinstance(files, dict) or not files or len(files) > 128:
        raise SystemExit("Missing or oversized release file inventory")
    if len({name.casefold() for name in files}) != len(files):
        raise SystemExit("Case-colliding release filenames")
    for name, item in files.items():
        path = local_file(directory, name)
        if (type(item.get("bytes")) is not int or item["bytes"] != path.stat().st_size or
                not re.fullmatch(r"[0-9a-f]{64}", str(item.get("sha256", ""))) or digest(path) != item["sha256"]):
            raise SystemExit("Release evidence bytes differ: " + name)


def package_names(version):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-rc\.[1-9][0-9]*)?", version):
        raise SystemExit("Invalid candidate version")
    return {"arm64-v8a": f"HomeTunnel-Android-{version}-arm64-v8a.apk",
            "x86_64": f"HomeTunnel-Android-{version}-x86_64.apk", "aab": f"HomeTunnel-Android-{version}.aab"}


def verify_candidate(record, directory, app_revision, sdk_lock, version):
    if (record.get("schema_version") != 2 or record.get("verification_stage") != "candidate" or
            record.get("tag_published") is not False or record.get("rebuilt") is not False or
            record.get("acceptance_complete") is not False):
        raise SystemExit("Publication requires the original immutable schema 2 candidate")
    if not re.fullmatch(r"[0-9a-f]{40}", app_revision) or record.get("app_revision") != app_revision or record.get("version") != version:
        raise SystemExit("Candidate app revision or version differs from the release")
    if (sdk_lock.get("status") != "sdk-candidate-imported" or sdk_lock.get("signer_identity_observed") is not True or
            sdk_lock.get("matches_android_snapshot") is not True or
            record.get("sdk_revision") != sdk_lock.get("source_revision") or
            str(record.get("sdk_run_id")) != str(sdk_lock.get("workflow_run_id"))):
        raise SystemExit("Candidate SDK differs from the confirmed import")
    build = record.get("build", {})
    if (build.get("repository") != REPOSITORY or build.get("caller_workflow") != CALLER or build.get("signer_workflow") != SIGNER or
            not re.fullmatch(r"[1-9][0-9]*", str(build.get("run_id", ""))) or type(build.get("run_attempt")) is not int or
            build["run_attempt"] < 1 or not str(build.get("source_ref", "")).startswith(("refs/heads/", "refs/tags/"))):
        raise SystemExit("Candidate build invocation is missing or inconsistent")
    verify_files(record.get("files"), directory)
    exported_lock = local_file(directory, "android-controller-sdk-candidate.lock.json")
    if read_json(exported_lock) != sdk_lock or digest(exported_lock) != record.get("sdk_lock_sha256"):
        raise SystemExit("Candidate SDK lock differs from the committed lock")
    names = package_names(version)
    if set(record.get("packages", {})) != set(names):
        raise SystemExit("Candidate must contain both APKs and the AAB")
    evidence = read_json(local_file(directory, "android-release-evidence.json"))
    if (evidence.get("schema_version") != 2 or evidence.get("status") != "passed" or evidence.get("runtime_acceptance") != "pending" or
            evidence.get("repository_revision") != app_revision or evidence.get("sdk_revision") != record["sdk_revision"] or
            evidence.get("version_name") != version or set(evidence.get("packages", {})) != set(names)):
        raise SystemExit("Candidate package verification differs from its source")
    for key, name in names.items():
        item = record["packages"][key]
        if item.get("name") != name or {k: item.get(k) for k in ("sha256", "bytes")} != record["files"].get(name):
            raise SystemExit("Candidate package differs from the sealed file inventory")
        if any(evidence["packages"][key].get(k) != item.get(k) for k in ("name", "sha256", "bytes")):
            raise SystemExit("Package verification does not describe the sealed package")
        for required in (name + ".spdx.json", name + ".sha256"):
            if required not in record["files"]:
                raise SystemExit("Package checksum or SBOM is absent from the sealed inventory")
        if (directory / (name + ".sha256")).read_text().strip() != f"{item['sha256']}  {name}":
            raise SystemExit("Package checksum file is inconsistent")
    return record


def verify_acceptance(acceptance, directory, candidate, candidate_sha):
    expected = {"schema_version": 1, "repository": REPOSITORY, "status": "passed", "acceptance_complete": True,
                "app_revision": candidate["app_revision"], "sdk_revision": candidate["sdk_revision"],
                "candidate_sha256": candidate_sha, "packages": candidate["packages"]}
    if any(acceptance.get(key) != value for key, value in expected.items()):
        raise SystemExit("Acceptance is incomplete or belongs to different source/package bytes")
    coverage = acceptance.get("coverage", {})
    files = acceptance.get("files", {})
    if set(coverage) != set(COVERAGE) or set(files) != {f"android-acceptance-{label}.json" for label in COVERAGE}:
        raise SystemExit("All required acceptance receipts must be present")
    verify_files(files, directory)
    for label in COVERAGE:
        item = coverage[label]
        name = f"android-acceptance-{label}.json"
        if item != {"status": "passed", "evidence": name}:
            raise SystemExit("Missing acceptance result: " + label)
        receipt = read_json(local_file(directory, name))
        if any(receipt.get(key) != value for key, value in expected.items() if key not in ("schema_version", "acceptance_complete")):
            raise SystemExit("Receipt has stale package or source bindings: " + label)
        if receipt.get("gate") != label or not receipt.get("environment") or not receipt.get("reviewed_by"):
            raise SystemExit("Receipt lacks its environment or reviewer: " + label)
        cases = receipt.get("cases")
        if not isinstance(cases, dict) or not cases or any(not name or result != "passed" for name, result in cases.items()):
            raise SystemExit("Receipt has missing, failed or skipped required cases: " + label)
        raw = receipt.get("raw_evidence", [])
        if not isinstance(raw, list) or not raw or any(not r.get("location") or not re.fullmatch(r"[0-9a-f]{64}", str(r.get("sha256", ""))) for r in raw):
            raise SystemExit("Receipt must locate the original hashed evidence: " + label)
        metrics = receipt.get("metrics", {})
        if label == "gemini_screenshots":
            total = metrics.get("applicable")
            if (type(total) is not int or total < 1 or metrics.get("reviewed") != total or metrics.get("approved") != total or
                    metrics.get("blocking") != 0 or metrics.get("actual_images_read") is not True):
                raise SystemExit("Gemini must review every applicable actual screenshot")
        if label == "stability":
            requirements = {"consecutive_connections": 30, "active_seconds": 7200, "online_seconds": 86400}
            if (any(type(metrics.get(k)) is not int or metrics[k] < v for k, v in requirements.items()) or
                    metrics.get("connection_failures") != 0 or type(metrics.get("input_release_ms")) is not int or
                    not 0 <= metrics["input_release_ms"] <= 2000 or type(metrics.get("recovery_or_retry_ms")) is not int or
                    not 0 <= metrics["recovery_or_retry_ms"] <= 30000):
                raise SystemExit("Stability acceptance is incomplete")
        if label == "arm64_build_signature" and metrics.get("runtime_scope") not in ("not_run", "smoke", "full"):
            raise SystemExit("arm64 runtime scope must be recorded separately")
        if label == "udp_network" and metrics.get("server_relay_payload_bytes") != 0:
            raise SystemExit("UDP-only payload acceptance is missing")
    return acceptance


def verify_publication(candidate, directory, app_revision, sdk_revision, version, sdk_lock, acceptance=None, acceptance_directory=None):
    if sdk_revision != sdk_lock.get("source_revision"):
        raise SystemExit("Publication SDK revision differs")
    verify_candidate(candidate, directory, app_revision, sdk_lock, version)
    acceptance_directory = acceptance_directory or directory
    acceptance = acceptance if acceptance is not None else read_json(local_file(acceptance_directory, ACCEPTANCE))
    verify_acceptance(acceptance, acceptance_directory, candidate, digest(local_file(directory, MANIFEST)))
    return True
