"""Fetch only a hash-locked SDK from the verified, published client release."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "ZHanry/home-tunnel-client"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_lock(lock, source):
    if lock.get("schema_version") != 1 or lock.get("repository") != REPOSITORY:
        raise SystemExit("Controller SDK lock must identify the reviewed client repository")
    tag = lock.get("tag")
    number = r"(?:0|[1-9][0-9]*)"
    if not isinstance(tag, str) or not re.fullmatch(rf"v{number}\.{number}\.{number}(?:-rc\.[1-9][0-9]*)?", tag):
        raise SystemExit("Controller SDK lock requires an exact published version tag")
    if (not re.fullmatch(r"[0-9a-f]{40}", str(lock.get("source_revision", ""))) or
            lock.get("source_revision") != source.get("source_revision") or source.get("source_tree_dirty") is not False):
        raise SystemExit("Controller SDK commit differs from the immutable native source snapshot")
    if lock.get("asset") != f"HomeTunnel-Remote-SDK-{tag[1:]}-android-arm64.zip":
        raise SystemExit("Controller SDK asset does not match its exact tag")
    for key in ("sha256", "provenance_sha256", "controller_manifest_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(lock.get(key, ""))):
            raise SystemExit("Controller SDK requires all reviewed artifact digests")


def api(path):
    result = subprocess.check_output(["gh", "api", f"repos/{REPOSITORY}/{path}"])
    if len(result) > 4 * 1024 * 1024:
        raise SystemExit("Unexpectedly large GitHub release response")
    return json.loads(result)


def verify_publication(lock, fetch=api):
    release = fetch("releases/tags/" + lock["tag"])
    if release.get("draft") is not False or release.get("tag_name") != lock["tag"]:
        raise SystemExit("Controller SDK release is missing, draft or for another tag")
    target = fetch("git/ref/tags/" + lock["tag"])["object"]
    for _ in range(5):
        if target.get("type") == "commit":
            break
        if target.get("type") != "tag" or not re.fullmatch(r"[0-9a-f]{40}", str(target.get("sha", ""))):
            raise SystemExit("Unexpected controller SDK Git tag target")
        target = fetch("git/tags/" + target["sha"])["object"]
    if target.get("type") != "commit" or target.get("sha") != lock["source_revision"]:
        raise SystemExit("Controller SDK release tag moved or names another source commit")


def verify_release_files(directory, lock):
    manifest = json.loads((directory / "release-manifest.json").read_text())
    expected = {"repository": REPOSITORY, "revision": lock["source_revision"], "version": lock["tag"][1:],
                "component": "client", "rc_tag": lock["tag"], "verification_stage": "verified"}
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise SystemExit("Controller SDK must come from a verified client release, not prepared CI artifacts")
    sums = {}
    for line in (directory / "SHA256SUMS.txt").read_text().splitlines():
        checksum, name = line.split("  ", 1)
        if not re.fullmatch(r"[0-9a-f]{64}", checksum) or Path(name).name != name or "/" in name or "\\" in name or name in sums:
            raise SystemExit("Invalid client release checksum inventory")
        sums[name] = checksum
    for path in directory.iterdir():
        if path.name in ("SHA256SUMS.txt", "SHA256SUMS.txt.sigstore.json"):
            continue
        if not path.is_file() or sums.get(path.name) != digest(path):
            raise SystemExit("Downloaded client release bytes differ from the signed checksum inventory")
    if digest(directory / lock["asset"]) != lock["sha256"] or digest(directory / "android-sdk-provenance.json") != lock["provenance_sha256"]:
        raise SystemExit("Controller SDK or provenance differs from the committed digest lock")


def extract_sdk(archive_path, output, record):
    if output.exists():
        raise SystemExit("Controller SDK extraction directory must be new")
    with zipfile.ZipFile(archive_path) as bundle:
        members = {}
        total = 0
        for item in bundle.infolist():
            name = item.filename
            path = PurePosixPath(name)
            total += item.file_size
            if (not name or name != path.as_posix() or path.is_absolute() or any(part in (".", "..") for part in name.split("/")) or
                    "\\" in name or ":" in name or any(ord(c) < 32 for c in name) or item.is_dir() or
                    stat.S_ISLNK(item.external_attr >> 16) or name in members or item.flag_bits & 1 or
                    item.file_size > 1024 ** 3 or total > 3 * 1024 ** 3 or len(members) >= 30000):
                raise SystemExit("Unsafe, duplicate or oversized controller SDK member")
            members[name] = item
        if set(members) != set(record.get("files", {})):
            raise SystemExit("Controller SDK inventory differs from its release provenance")
        output.mkdir(parents=True)
        for name in members:
            path = output / name
            if not path.resolve().is_relative_to(output.resolve()):
                raise SystemExit("Controller SDK path escapes its destination")
            path.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(name) as source, path.open("xb") as target:
                shutil.copyfileobj(source, target, 1024 * 1024)
            if digest(path) != record["files"][name]:
                raise SystemExit("Controller SDK file differs from its release provenance")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=ROOT / "native/controller-sdk.lock.json")
    parser.add_argument("--cache", type=Path, default=ROOT / ".cache/controller-sdk-release")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/remote-controller")
    args = parser.parse_args()
    if not args.lock.is_file():
        raise SystemExit("A verified client release and reviewed native/controller-sdk.lock.json are required before Android release")
    lock = json.loads(args.lock.read_text())
    source = json.loads((ROOT / "native/remote-source.lock.json").read_text())
    validate_lock(lock, source)
    verify_publication(lock)
    if args.cache.exists() and any(args.cache.iterdir()):
        raise SystemExit("Choose a new empty SDK download directory; existing artifacts are never overwritten")
    args.cache.mkdir(parents=True, exist_ok=True)
    names = [lock["asset"], lock["asset"] + ".sigstore.json", "android-sdk-provenance.json",
             "android-sdk-provenance.json.sigstore.json", "release-manifest.json", "SHA256SUMS.txt", "SHA256SUMS.txt.sigstore.json"]
    command = ["gh", "release", "download", lock["tag"], "--repo", REPOSITORY, "--dir", str(args.cache)]
    for name in names:
        command += ["--pattern", name]
    subprocess.run(command, check=True)
    identity = f"https://github.com/{REPOSITORY}/.github/workflows/release.yml@refs/tags/{lock['tag']}"
    for name in ("SHA256SUMS.txt", lock["asset"], "android-sdk-provenance.json"):
        subprocess.run(["cosign", "verify-blob", "--bundle", str(args.cache / (name + ".sigstore.json")),
                        "--certificate-identity", identity, "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
                        str(args.cache / name)], check=True)
    verify_release_files(args.cache, lock)
    provenance = json.loads((args.cache / "android-sdk-provenance.json").read_text())
    expected = {"source_revision": lock["source_revision"], "source_modified": False, "tag": lock["tag"],
                "archive": lock["asset"], "archive_sha256": lock["sha256"], "device_media_accepted": False}
    if any(provenance.get(key) != value for key, value in expected.items()):
        raise SystemExit("Controller SDK provenance does not match the reviewed release lock")
    extracted = args.cache / "sdk"
    extract_sdk(args.cache / lock["asset"], extracted, provenance)
    packaged_source = json.loads((extracted / "source/remote-artifact.json").read_text())
    for key in ("source_revision", "source_tree_sha256", "source_archive_sha256", "deps_lock_sha256", "header_sha256"):
        if packaged_source.get(key) != source.get(key):
            raise SystemExit("Packaged controller source does not match the app's immutable snapshot")
    subprocess.run([sys.executable, ROOT / "scripts/import-remote-controller.py", extracted / "android-webrtc-arm64",
                    "--reviewed-manifest-sha256", lock["controller_manifest_sha256"], "--output", args.output], check=True)
    print("Published, signed controller SDK imported; physical-device acceptance is still required")


if __name__ == "__main__":
    main()
