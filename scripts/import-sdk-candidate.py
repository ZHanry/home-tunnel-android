"""Import a trusted Android SDK candidate from one fixed client Actions run.

Historical stable arm64 Releases stay on scripts/fetch-remote-controller.py.
Two explicit formats are supported: the standalone SDK build and the complete
client candidate. Their caller, signer and artifact names cannot be mixed.
Exact source, run/attempt and certificate subjects must verify before import.
"""
import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "ZHanry/home-tunnel-client"
CALLER_WORKFLOW = ".github/workflows/android-webrtc.yml"
SIGNER_WORKFLOW = ".github/workflows/android-sdk-candidate.yml"
IDENTITY_REGEXP = r"^https://github.com/ZHanry/home-tunnel-client/\.github/workflows/android-sdk-candidate\.yml@refs/"
ABIS = ("arm64-v8a", "x86_64")
PREFIXES = {"arm64-v8a": "android-webrtc-arm64", "x86_64": "android-webrtc-x86_64"}
PROFILES = {
    "standalone-sdk": (CALLER_WORKFLOW, SIGNER_WORKFLOW, "android-sdk-candidate", "android-sdk-candidate.json"),
    "client": (".github/workflows/release.yml", ".github/workflows/client-candidate.yml", "candidate-assets", "client-candidate.json"),
}


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / (name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def local_file(directory, name):
    if not isinstance(name, str) or not name or name in (".", "..") or any(c in name for c in "/\\:") or any(ord(c) < 32 for c in name):
        raise SystemExit("Unsafe SDK candidate filename")
    path = directory / name
    if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(directory.resolve()):
        raise SystemExit("Missing or linked SDK candidate subject")
    return path


def read_json(path, maximum=16 * 1024 * 1024):
    if not 1 <= path.stat().st_size <= maximum:
        raise SystemExit("SDK candidate metadata is empty or oversized")
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_run(run, revision, run_id, ref=None, candidate_format="standalone-sdk"):
    caller, _, _, _ = PROFILES[candidate_format]
    if run.get("repository") != REPOSITORY:
        raise SystemExit("SDK candidate run is not from ZHanry/home-tunnel-client")
    if run.get("path") != caller or run.get("event") != "workflow_dispatch":
        raise SystemExit("SDK candidate run was not the selected registered dispatch")
    if run.get("head_sha") != revision or not re.fullmatch(r"[0-9a-f]{40}", revision or ""):
        raise SystemExit("SDK candidate run does not match the requested commit")
    if str(run.get("id")) != str(run_id) or not re.fullmatch(r"[1-9][0-9]{0,19}", str(run_id)):
        raise SystemExit("SDK candidate run id does not match")
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise SystemExit("SDK candidate run did not succeed")
    if ref is not None and run.get("ref") not in {ref, f"refs/heads/{ref}", f"refs/tags/{ref}"}:
        raise SystemExit("SDK candidate run is for a different ref")


def verify_artifact(artifact, expected_sha, candidate_format="standalone-sdk"):
    if artifact.get("name") != PROFILES[candidate_format][2]:
        raise SystemExit("SDK candidate artifact name mismatch")
    digest_value = str(artifact.get("digest", ""))
    if digest_value.startswith("sha256:"):
        digest_value = digest_value.split(":", 1)[1]
    if digest_value != expected_sha or not re.fullmatch(r"[0-9a-f]{64}", expected_sha):
        raise SystemExit("SDK candidate artifact digest mismatch")
    if artifact.get("expired") is True:
        raise SystemExit("SDK candidate artifact has expired")


def verify_tree(directory, revision, verify_blob, identity=IDENTITY_REGEXP):
    record = read_json(local_file(directory, "android-sdk-candidate.json"))
    if record.get("verification_stage") != "candidate" or record.get("tag_published") is not False or record.get("stable_release") is not False:
        raise SystemExit("SDK index is not an unpublished candidate")
    if record.get("repository") != REPOSITORY or record.get("source_revision") != revision:
        raise SystemExit("SDK candidate index names a different repository or commit")
    if record.get("caller_workflow") != CALLER_WORKFLOW or record.get("signer_workflow") != SIGNER_WORKFLOW:
        raise SystemExit("SDK candidate caller or signer workflow mismatch")
    if record.get("caller_event") != "workflow_dispatch" or record.get("device_media_accepted") is not False:
        raise SystemExit("SDK candidate event or acceptance flag mismatch")
    return verify_sdk_payload(record, directory, revision, verify_blob, identity, {"android-sdk-candidate.json"})


def verify_sdk_payload(record, directory, revision, verify_blob, identity, other_subjects):
    """Check both original ABI subjects after their enclosing index is verified."""
    abis = record.get("abis") or {}
    if set(abis) != set(ABIS):
        raise SystemExit("SDK candidate must contain arm64-v8a and x86_64")
    trees = set()
    locks = set()
    subjects_seen = set(other_subjects)
    for abi, item in abis.items():
        if item.get("production_controller") is not True or item.get("controller_backend_linked") is not True or item.get("device_media_accepted") is not False:
            raise SystemExit(f"SDK candidate ABI is not a production controller awaiting acceptance: {abi}")
        archive = local_file(directory, item["archive"])
        if digest(archive) != item.get("archive_sha256"):
            raise SystemExit(f"SDK candidate archive digest mismatch: {abi}")
        provenance = read_json(local_file(directory, item["provenance"]))
        if provenance.get("archive_sha256") != item["archive_sha256"] or provenance.get("target") != abi or provenance.get("source_modified") is not False:
            raise SystemExit(f"SDK candidate provenance mismatch: {abi}")
        if digest(directory / item["provenance"]) != item.get("provenance_sha256") or digest(local_file(directory, item["sbom"])) != item.get("sbom_sha256"):
            raise SystemExit(f"SDK candidate provenance or SBOM digest mismatch: {abi}")
        expected_subjects = {item["archive"], item["archive"] + ".sha256", item["provenance"], item["sbom"]}
        subjects = item.get("subjects")
        if not isinstance(subjects, list) or len(subjects) != 4 or {subject.get("name") for subject in subjects} != expected_subjects:
            raise SystemExit("SDK candidate requires every signed archive/checksum/provenance/SBOM subject")
        expected = {"repository": REPOSITORY, "source_revision": revision, "source_tree_sha256": item.get("source_tree_sha256"),
                    "version": record.get("version"), "archive": item["archive"], "upstream_lock_sha256": item.get("upstream_lock_sha256"),
                    "recipe_sha256": item.get("recipe_sha256"), "compiler_lock": item.get("compiler_lock"),
                    "gn_args": item.get("gn_args"), "elf_machine": item.get("elf_machine"),
                    "controller_backend_linked": True, "production_controller": True, "device_media_accepted": False}
        if any(provenance.get(key) != value for key, value in expected.items()):
            raise SystemExit("SDK candidate provenance has a different source/build identity")
        for key in ("source_tree_sha256", "upstream_lock_sha256", "recipe_sha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", str(item.get(key, ""))):
                raise SystemExit("SDK candidate has an invalid source/build digest")
        if archive.stat().st_size != item.get("archive_bytes") or archive.stat().st_size != provenance.get("archive_bytes"):
            raise SystemExit("SDK candidate archive size mismatch")
        trees.add(item.get("source_tree_sha256"))
        locks.add(item.get("upstream_lock_sha256"))
        for subject in subjects:
            name = subject["name"]
            if name in subjects_seen or subject.get("sigstore_bundle") != name + ".sigstore.json":
                raise SystemExit("SDK candidate signature subjects overlap or name a different bundle")
            path = local_file(directory, name)
            bundle = local_file(directory, subject["sigstore_bundle"])
            if not 1 <= bundle.stat().st_size <= 1024 * 1024:
                raise SystemExit("SDK candidate signature bundle is empty or oversized")
            if digest(path) != subject.get("sha256") or digest(bundle) != subject.get("sigstore_sha256") or path.stat().st_size != subject.get("bytes"):
                raise SystemExit(f"SDK candidate signature subject mismatch: {abi}")
            verify_blob(path, bundle, identity)
            subjects_seen.update((name, bundle.name))
        if (directory / (item["archive"] + ".sha256")).read_text(encoding="utf-8") != f"{item['archive_sha256']}  {item['archive']}\n":
            raise SystemExit("SDK candidate checksum contents differ from the archive")
    if len(trees) != 1 or len(locks) != 1 or None in trees:
        raise SystemExit("SDK candidate ABIs were not built from one source and dependency lock")
    if record.get("source_tree_sha256") != next(iter(trees)) or record.get("upstream_lock_sha256") != next(iter(locks)):
        raise SystemExit("SDK candidate index differs from its ABI source identities")
    if {path.name for path in directory.iterdir()} != subjects_seen:
        raise SystemExit("SDK candidate artifact contains unexpected or missing subjects")
    return record


def write_lock(record, revision, run_id, artifact_sha, snapshot_matches, identity_observed, candidate_format="standalone-sdk"):
    caller, signer, artifact, _ = PROFILES[candidate_format]
    abis = {abi: {"archive_sha256": item["archive_sha256"], "provenance_sha256": item["provenance_sha256"], "sbom_sha256": item["sbom_sha256"]}
            for abi, item in record["abis"].items()}
    return {
        "schema_version": 1,
        "status": "sdk-candidate-imported",
        "repository": REPOSITORY,
        "workflow_run_id": str(run_id),
        "source_revision": revision,
        "source_tree_sha256": record.get("source_tree_sha256"),
        "upstream_lock_sha256": record.get("upstream_lock_sha256"),
        "caller_workflow": caller,
        "signer_workflow": signer,
        "signer_identity_regexp": "^" + re.escape(f"https://github.com/{REPOSITORY}/{signer}@refs/"),
        "signer_identity_observed": identity_observed,
        "artifact_name": artifact,
        "artifact_sha256": artifact_sha,
        "matches_android_snapshot": snapshot_matches,
        "abis": abis,
    }


def snapshot_matches(record):
    lock_path = ROOT / "native/remote-source.lock.json"
    if not lock_path.is_file():
        return False
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    return record.get("source_revision") == lock.get("source_revision") and record.get("source_tree_sha256") == lock.get("source_tree_sha256")


def cosign_verify(path, bundle, identity_regexp):
    subprocess.run(["cosign", "verify-blob", "--bundle", str(bundle), "--certificate-identity-regexp", identity_regexp,
                    "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com", str(path)], check=True)


def import_candidate(run, artifact, directory, revision, run_id, artifact_sha, verify_blob=cosign_verify, ref=None, identity_observed=False):
    verify_run(run, revision, run_id, ref)
    verify_artifact(artifact, artifact_sha)
    record = verify_tree(directory, revision, verify_blob)
    if str(record.get("workflow_run_id")) != str(run_id):
        raise SystemExit("SDK candidate index was sealed for a different run")
    return write_lock(record, revision, run_id, artifact_sha, snapshot_matches(record), identity_observed)


def verify_attestation(path, revision, ref, run_id, attempt, evidence, candidate_format="standalone-sdk"):
    command = ["gh", "attestation", "verify", str(path), "--repo", REPOSITORY,
               "--signer-workflow", REPOSITORY + "/" + PROFILES[candidate_format][1],
               "--source-digest", revision, "--source-ref", ref, "--signer-digest", revision,
               "--deny-self-hosted-runners", "--format", "json"]
    result = subprocess.check_output(command)
    records = json.loads(result)
    invocation = f"https://github.com/{REPOSITORY}/actions/runs/{run_id}/attempts/{attempt}"
    matched = False
    for entry in records:
        statement = entry.get("verificationResult", {}).get("statement", {})
        predicate = statement.get("predicate", {})
        if (predicate.get("runDetails", {}).get("metadata", {}).get("invocationId") == invocation and
                any(subject.get("digest", {}).get("sha256") == digest(path) for subject in statement.get("subject", []))):
            matched = True
    if not matched:
        raise SystemExit("Verified attestation is not bound to this exact build run/attempt")
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / (path.name + ".verification.json")).write_bytes(result)


def extract_artifact(archive, directory, candidate_format="standalone-sdk"):
    if directory.exists():
        raise SystemExit("Candidate download extraction directory must be new")
    fetch = module("fetch-remote-controller")
    with zipfile.ZipFile(archive) as bundle:
        members = fetch.checked_members(bundle)
        if (len(members) > (256 if candidate_format == "client" else 32) or
                len({name.casefold() for name in members}) != len(members) or any(
                    not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}", name) or name.endswith(".") or
                    re.fullmatch(r"(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?", name, re.I) for name in members)):
            raise SystemExit("Unexpected SDK candidate artifact layout")
        directory.mkdir(parents=True)
        for name in members:
            with bundle.open(name) as source, (directory / name).open("xb") as target:
                shutil.copyfileobj(source, target, 1024 * 1024)


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id")
    parser.add_argument("--revision")
    parser.add_argument("--candidate-format", choices=tuple(PROFILES), help="Use client for SDKs sealed inside the complete client candidate")
    parser.add_argument("--cache", type=Path, default=ROOT / ".cache/sdk-candidate-download")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/remote-controller")
    parser.add_argument("--lock", type=Path, default=ROOT / "native/controller-sdk-candidate.lock.json")
    parser.add_argument("--restore", action="store_true", help="Restore exact bytes pinned by the committed lock without modifying source or lock")
    parser.add_argument("--import-source", action="store_true", help="Update the immutable native source snapshot after verifying both signed ABIs")
    parser.add_argument("--cosign", default="cosign")
    args = parser.parse_args()
    pinned = read_json(args.lock) if args.restore else None
    if args.restore and (args.run_id or args.revision or args.import_source or args.candidate_format):
        parser.error("--restore uses only the committed lock and never replaces the source snapshot")
    if pinned:
        formats = [name for name, (caller, signer, artifact, _) in PROFILES.items()
                   if (pinned.get("caller_workflow"), pinned.get("signer_workflow"), pinned.get("artifact_name")) == (caller, signer, artifact)]
        if len(formats) != 1:
            raise SystemExit("Pinned SDK candidate has an unknown caller/signer/artifact combination")
        candidate_format = formats[0]
    else:
        candidate_format = args.candidate_format or "standalone-sdk"
    _, signer, artifact_name, index_name = PROFILES[candidate_format]
    revision = pinned["source_revision"] if pinned else args.revision
    run_id = pinned["workflow_run_id"] if pinned else args.run_id
    if not re.fullmatch(r"[0-9a-f]{40}", str(revision)) or not re.fullmatch(r"[1-9][0-9]{0,19}", str(run_id)):
        parser.error("An exact --run-id and --revision are required")
    for directory in (args.cache, args.output):
        if directory.exists():
            raise SystemExit("Choose new cache/output directories; existing products are not overwritten")
    run = api(f"repos/{REPOSITORY}/actions/runs/{run_id}")
    run["repository"] = run.get("repository", {}).get("full_name")
    run["ref"] = "refs/heads/" + run["head_branch"]
    verify_run(run, revision, run_id, candidate_format=candidate_format)
    artifacts = api(f"repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100")
    matches = [value for value in artifacts["artifacts"] if value.get("name") == artifact_name]
    if len(matches) != 1:
        raise SystemExit("The successful run has no unique sealed SDK candidate")
    artifact = matches[0]
    artifact_sha = str(artifact.get("digest", "")).removeprefix("sha256:")
    verify_artifact(artifact, artifact_sha, candidate_format)
    if artifact.get("workflow_run", {}).get("id") != int(run_id) or artifact.get("workflow_run", {}).get("head_sha") != revision:
        raise SystemExit("Artifact belongs to a different source/run")
    if pinned and (artifact_sha != pinned.get("artifact_sha256") or str(artifact["id"]) != str(pinned.get("artifact_id")) or run["run_attempt"] != pinned.get("run_attempt")):
        raise SystemExit("Pinned SDK candidate artifact or build attempt has changed")
    args.cache.mkdir(parents=True)
    archive = args.cache / "artifact.zip"
    with archive.open("xb") as stream:
        subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip", "--allow-escape-sequences"], stdout=stream, check=True, timeout=1800)
    if digest(archive) != artifact_sha:
        raise SystemExit("Downloaded SDK artifact differs from the GitHub artifact digest")
    directory = args.cache / "subjects"
    extract_artifact(archive, directory, candidate_format)
    evidence = args.cache / "verification"
    verify_attestation(directory / index_name, revision, run["ref"], run_id, run["run_attempt"], evidence, candidate_format)
    identity = "^" + re.escape(f"https://github.com/{REPOSITORY}/{signer}@{run['ref']}") + "$"

    def verify_blob(path, bundle, expected_identity):
        subprocess.run([args.cosign, "verify-blob", "--bundle", str(bundle), "--certificate-identity-regexp", expected_identity,
                        "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com", str(path)], check=True)

    if candidate_format == "client":
        record = module("client_sdk_candidate").verify_tree(directory, revision, run, verify_blob, identity)
    else:
        record = verify_tree(directory, revision, verify_blob, identity)
    if str(record.get("workflow_run_id")) != str(run_id):
        raise SystemExit("SDK candidate index was sealed for a different run")
    fetch = module("fetch-remote-controller")
    source = module("import-remote-source")
    controller = module("import-remote-controller")
    sdk_records = {}
    source_record = contents = source_tree = None
    for abi, item in record["abis"].items():
        for name in (item["archive"], item["provenance"]):
            verify_attestation(directory / name, revision, run["ref"], run_id, run["run_attempt"], evidence, candidate_format)
        extracted = args.cache / abi
        fetch.extract_sdk(directory / item["archive"], extracted, read_json(directory / item["provenance"]))
        packaged = read_json(extracted / "source/remote-artifact.json")
        if packaged.get("source_revision") != revision or packaged.get("source_tree_sha256") != record["source_tree_sha256"]:
            raise SystemExit("Packaged source differs from the sealed candidate")
        packed_archive = local_file(extracted / "source", packaged["source_archive"])
        verified = source.source_contents(packed_archive, packaged)
        if contents is None:
            contents = verified
            source_record = packaged
            source_tree = args.cache / "verified-source"
            source.write_source(contents, source_tree)
        elif contents != verified or any(packaged.get(key) != source_record.get(key) for key in ("header_sha256", "deps_lock_sha256", "source_archive_sha256")):
            raise SystemExit("The two SDK ABIs have different source/header/dependency bytes")
        sdk = extracted / PREFIXES[abi]
        manifest_sha = digest(sdk / "android-webrtc-build.json")
        manifest, _ = controller.verify_sdk(sdk, manifest_sha, abi, source_record, source_tree)
        if manifest.get("gn_args") != item["gn_args"] or manifest.get("compiler_lock") != item["compiler_lock"]:
            raise SystemExit("Extracted SDK build differs from its sealed policy")
        sdk_records[abi] = (sdk, manifest, manifest_sha)
    if not snapshot_matches(record) and not args.import_source:
        raise SystemExit("Verified SDK requires its matching source snapshot; use --import-source for the initial local import")
    if args.import_source:
        old = read_json(ROOT / "native/remote-source.lock.json")
        current = ROOT / "native/remote-source"
        actual = {path.relative_to(current).as_posix(): digest(path) for path in current.rglob("*") if path.is_file()}
        if actual != old["source_files"]:
            raise SystemExit("Refusing to replace local edits in the existing native source snapshot")
        source.write_source(contents, current)
        new_source_lock = {key: value for key, value in source_record.items() if key not in ("target", "library", "library_sha256")}
        new_source_lock["repository"] = REPOSITORY
        (ROOT / "native/remote-source.lock.json").write_text(json.dumps(new_source_lock, indent=2) + "\n", encoding="utf-8")
    for abi, (sdk, manifest, manifest_sha) in sdk_records.items():
        controller.stage_sdk(sdk, args.output / abi, manifest, source_record, manifest_sha, abi)
        subprocess.run([sys.executable, ROOT / "scripts/verify-remote-native.py", args.output / abi, "--abis", abi, "--production"], check=True)
    lock = write_lock(record, revision, run_id, artifact_sha, snapshot_matches(record), True, candidate_format)
    lock.update(artifact_id=str(artifact["id"]), run_attempt=run["run_attempt"], source_ref=run["ref"],
                index_sha256=digest(directory / index_name), signer_identity_regexp=identity)
    for abi, (_, manifest, manifest_sha) in sdk_records.items():
        lock["abis"][abi].update(controller_manifest_sha256=manifest_sha,
                                 library_sha256=manifest["files"][f"lib/{abi}/libhome_tunnel_remote.so"])
    if pinned:
        if lock != pinned:
            raise SystemExit("Verified candidate no longer matches the committed import lock")
    else:
        args.lock.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Verified and imported both controller ABIs from {revision}, run {run_id}; device acceptance remains required")


if __name__ == "__main__":
    main()
