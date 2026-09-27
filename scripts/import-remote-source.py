"""Replace the read-only shared-core snapshot using a verified desktop source archive."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import re
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def relative_name(name):
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or path.as_posix() != name or
            any(part in (".", "..", "") for part in name.split("/")) or
            "\\" in name or ":" in name or any(ord(c) < 32 for c in name)):
        raise SystemExit("Unsafe source archive path")
    return path


def source_contents(archive_path, record):
    if (record.get("source_tree_dirty") is not False or
            not re.fullmatch(r"[0-9a-f]{40}", str(record.get("source_revision", ""))) or
            not isinstance(record.get("source_files"), dict) or
            not 1 <= len(record["source_files"]) <= 512):
        raise SystemExit("Source archive needs a bounded immutable source identity")
    if archive_path.stat().st_size > 8 * 1024 * 1024 or hashlib.sha256(archive_path.read_bytes()).hexdigest() != record["source_archive_sha256"]:
        raise SystemExit("Source archive digest mismatch")
    contents = {}
    folded = set()
    total = 0
    with tarfile.open(archive_path, "r:gz") as archive:
        for member in archive:
            path = relative_name(member.name)
            total += member.size
            if not member.isfile() or member.size > 4 * 1024 * 1024 or total > 32 * 1024 * 1024:
                raise SystemExit("Unexpected source archive member")
            if not path.is_relative_to("native/remote"):
                raise SystemExit("Unsafe source archive path")
            name = path.relative_to("native/remote").as_posix()
            if name not in record["source_files"] or name.casefold() in folded:
                raise SystemExit("Source file set mismatch")
            folded.add(name.casefold())
            content = archive.extractfile(member).read()
            if hashlib.sha256(content).hexdigest() != record["source_files"][name]:
                raise SystemExit(f"Source file digest mismatch: {name}")
            contents[name] = content
    if contents.keys() != record["source_files"].keys():
        raise SystemExit("Incomplete source archive")
    if hashlib.sha256(json.dumps(record["source_files"], sort_keys=True, separators=(",", ":")).encode()).hexdigest() != record["source_tree_sha256"]:
        raise SystemExit("Source tree identity mismatch")
    if record.get("header_sha256") != record["source_files"].get("include/home_tunnel/remote.h") or record.get("deps_lock_sha256") != record["source_files"].get("remote-deps.lock.json"):
        raise SystemExit("Source ABI or dependency identity mismatch")
    return contents


def write_source(contents, destination):
    if destination.is_symlink() or (destination.exists() and destination.is_junction()):
        raise SystemExit("Source destination must not be a linked directory")
    destination = destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    for existing in destination.rglob("*"):
        if existing.is_symlink() or existing.is_junction():
            raise SystemExit("Source snapshot contains a linked path")
    for existing in destination.rglob("*"):
        if existing.is_file() and existing.relative_to(destination).as_posix() not in contents:
            if not existing.resolve().is_relative_to(destination):
                raise SystemExit("Source snapshot path escaped its root")
            existing.unlink()
    for name, content in contents.items():
        target = destination / relative_name(name)
        if not target.resolve().is_relative_to(destination):
            raise SystemExit("Source snapshot path escaped its root")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    record = json.loads(args.manifest.read_text())
    contents = source_contents(args.archive, record)
    write_source(contents, ROOT / "native/remote-source")
    lock = {key: value for key, value in record.items() if key not in ("target", "library", "library_sha256")}
    lock["repository"] = "ZHanry/home-tunnel-client"
    (ROOT / "native/remote-source.lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print("Imported the verified same-source snapshot; no source changes were applied")


if __name__ == "__main__":
    main()
