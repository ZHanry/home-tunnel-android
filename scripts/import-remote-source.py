"""Replace the read-only shared-core snapshot using a verified desktop source archive."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def relative_name(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise SystemExit("Unsafe source archive path")
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    record = json.loads(args.manifest.read_text())
    if hashlib.sha256(args.archive.read_bytes()).hexdigest() != record["source_archive_sha256"]:
        raise SystemExit("Source archive digest mismatch")
    contents = {}
    with tarfile.open(args.archive, "r:gz") as archive:
        for member in archive.getmembers():
            path = relative_name(member.name)
            if member.isdir():
                continue
            if not member.isfile() or member.size > 4 * 1024 * 1024:
                raise SystemExit("Unexpected source archive member")
            name = path.relative_to("native/remote").as_posix()
            if name not in record["source_files"] or name in contents:
                raise SystemExit("Source file set mismatch")
            content = archive.extractfile(member).read()
            if hashlib.sha256(content).hexdigest() != record["source_files"][name]:
                raise SystemExit(f"Source file digest mismatch: {name}")
            contents[name] = content
    if contents.keys() != record["source_files"].keys():
        raise SystemExit("Incomplete source archive")
    if hashlib.sha256(json.dumps(record["source_files"], sort_keys=True, separators=(",", ":")).encode()).hexdigest() != record["source_tree_sha256"]:
        raise SystemExit("Source tree identity mismatch")
    destination = (ROOT / "native/remote-source").resolve()
    destination.mkdir(parents=True, exist_ok=True)
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
    lock = {key: value for key, value in record.items() if key not in ("target", "library", "library_sha256")}
    lock["repository"] = "ZHanry/home-tunnel-client"
    (ROOT / "native/remote-source.lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print("Imported the verified same-source snapshot; no source changes were applied")


if __name__ == "__main__":
    main()
