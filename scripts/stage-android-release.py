"""Stage only verified candidate subjects and separately reviewed acceptance receipts."""
import argparse
import json
import shutil
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from android_release_candidate import ACCEPTANCE, MANIFEST, digest, local_file, read_json, verify_publication


def stage(source, destination, revision, version, lock):
    if destination.exists():
        raise SystemExit("Release staging requires a new directory")
    candidate_dir, acceptance_dir = source / "candidate", source / "acceptance"
    candidate = read_json(local_file(candidate_dir, MANIFEST))
    acceptance = read_json(local_file(acceptance_dir, ACCEPTANCE))
    downloaded = read_json(local_file(source, "android-candidate-download.json"))
    if (downloaded.get("source_revision") != revision or downloaded.get("signatures_verified") is not True or
            downloaded.get("run_attestations_verified") is not True or not downloaded.get("acceptance_revision") or
            downloaded.get("candidate_sha256") != digest(candidate_dir / MANIFEST)):
        raise SystemExit("Download verification is missing or stale")
    verify_publication(candidate, candidate_dir, revision, lock["source_revision"], version, lock, acceptance, acceptance_dir)
    subjects = set(candidate["files"]) | {MANIFEST}
    copies = {name: local_file(candidate_dir, name) for name in subjects | {n + ".sigstore.json" for n in subjects}}
    copies.update({name: local_file(acceptance_dir, name) for name in set(acceptance["files"]) | {ACCEPTANCE, "android-acceptance-origin.json"}})
    copies["android-candidate-download.json"] = source / "android-candidate-download.json"
    for name in [MANIFEST, *(p["name"] for p in candidate["packages"].values())]:
        verification = name + ".verification.json"
        copies[verification] = local_file(source / "verification", verification)
    destination.mkdir(parents=True)
    for name, original in copies.items():
        with original.open("rb") as src, (destination / name).open("xb") as dst:
            shutil.copyfileobj(src, dst)
    verify_publication(read_json(destination / MANIFEST), destination, revision, lock["source_revision"], version, lock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    stage(args.source, args.output, args.revision, args.version, read_json(ROOT / "native/controller-sdk-candidate.lock.json"))
    print("Staged the accepted original packages, SBOMs, signatures and acceptance receipts without rebuilding")


if __name__ == "__main__":
    main()
