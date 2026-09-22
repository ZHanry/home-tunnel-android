"""Verify imported Android native artifacts against the reviewed same-source lock."""
from pathlib import Path
import argparse
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(directory, abis):
    lock = json.loads((ROOT / "native/remote-source.lock.json").read_text())
    if digest(directory / "include/home_tunnel/remote.h") != lock["header_sha256"]:
        raise SystemExit("Native ABI header hash does not match the source lock")
    for abi in abis:
        record = json.loads((directory / abi / "remote-artifact.json").read_text())
        for key in ("abi", "status", "source_revision", "source_tree_sha256", "source_archive_sha256", "deps_lock_sha256", "header_sha256", "available"):
            if record.get(key) != lock[key]:
                raise SystemExit(f"Native artifact provenance mismatch: {abi} {key}")
        if record.get("target") != abi or record.get("library_sha256") != digest(directory / abi / "libhome_tunnel_remote.so"):
            raise SystemExit(f"Native library hash/ABI mismatch: {abi}")
    print("Native ABI, source lock and library hashes verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--abis", nargs="+", default=["arm64-v8a", "x86_64"], choices=("arm64-v8a", "x86_64"))
    args = parser.parse_args()
    verify(args.directory, args.abis)
