"""Record the bytes signed by the candidate workflow. Acceptance stays incomplete."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def seal(directory, version, revision, lock, identity):
    path = directory / "android-release-candidate.json"
    if path.exists():
        raise SystemExit("Existing Android candidate evidence is never overwritten")
    if json.loads((directory / "android-controller-sdk-candidate.lock.json").read_text(encoding="utf-8")) != lock:
        raise SystemExit("The exported SDK lock differs from the verified source lock")
    if (identity.get("repository") != "ZHanry/home-tunnel-android" or
            not re.fullmatch(r"[0-9a-f]{40}", revision) or
            not re.fullmatch(r"[1-9][0-9]*", str(identity.get("run_id", ""))) or
            type(identity.get("run_attempt")) is not int or identity["run_attempt"] < 1 or
            not str(identity.get("source_ref", "")).startswith(("refs/heads/", "refs/tags/"))):
        raise SystemExit("Candidate must be bound to a real source and workflow invocation")
    names = {"arm64-v8a": f"HomeTunnel-Android-{version}-arm64-v8a.apk",
             "x86_64": f"HomeTunnel-Android-{version}-x86_64.apk", "aab": f"HomeTunnel-Android-{version}.aab"}
    evidence = json.loads((directory / "android-release-evidence.json").read_text(encoding="utf-8"))
    if (evidence.get("schema_version") != 2 or evidence.get("status") != "passed" or
            evidence.get("repository_revision") != revision or evidence.get("sdk_revision") != lock.get("source_revision") or
            evidence.get("runtime_acceptance") != "pending" or evidence.get("version_name") != version or
            set(evidence.get("packages", {})) != set(names)):
        raise SystemExit("Package verification is missing, stale or claims unperformed runtime acceptance")
    packages = {}
    for key, name in names.items():
        package = directory / name
        checked = evidence["packages"][key]
        sha = digest(package)
        if checked.get("name") != name or checked.get("sha256") != sha or checked.get("bytes") != package.stat().st_size:
            raise SystemExit("Candidate bytes changed after signature and identity verification")
        sbom = json.loads((directory / (name + ".spdx.json")).read_text(encoding="utf-8"))
        if not str(sbom.get("spdxVersion", "")).startswith("SPDX-") or not isinstance(sbom.get("packages"), list):
            raise SystemExit("Every installable package requires its SPDX SBOM")
        checksum = directory / (name + ".sha256")
        with checksum.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(f"{sha}  {name}\n")
        packages[key] = {"name": name, "sha256": sha, "bytes": package.stat().st_size}
    files = {}
    for item in sorted(directory.iterdir()):
        if not item.is_file() or item.is_symlink() or item.name.endswith(".sigstore.json") or any(c in item.name for c in "/\\:"):
            raise SystemExit("Candidate directory contains an unexpected or previously signed subject")
        files[item.name] = {"sha256": digest(item), "bytes": item.stat().st_size}
    record = {"schema_version": 2, "verification_stage": "candidate", "tag_published": False,
              "rebuilt": False, "acceptance_complete": False, "version": version, "app_revision": revision,
              "sdk_revision": lock["source_revision"], "sdk_run_id": lock["workflow_run_id"],
              "sdk_lock_sha256": digest(directory / "android-controller-sdk-candidate.lock.json"),
              "build": dict(identity, caller_workflow=".github/workflows/ci.yml", signer_workflow=".github/workflows/android-candidate.yml"),
              "packages": packages, "files": files}
    path.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((ROOT / "native/controller-sdk-candidate.lock.json").read_text(encoding="utf-8"))
    revision = os.environ.get("GITHUB_SHA", "")
    if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != revision or subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT).strip():
        raise SystemExit("The built source must remain the exact clean candidate commit")
    seal(args.output, args.version, revision, lock, {
        "repository": os.environ.get("GITHUB_REPOSITORY"), "source_ref": os.environ.get("GITHUB_REF"),
        "run_id": os.environ.get("GITHUB_RUN_ID"), "run_attempt": int(os.environ.get("GITHUB_RUN_ATTEMPT", "0"))})
    print("Sealed Android candidate bytes with acceptance still pending")


if __name__ == "__main__":
    main()
