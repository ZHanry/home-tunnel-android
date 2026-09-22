"""Import generated RD contracts from a reviewed local server checkout, preserving exact bytes."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "contracts/remote-desktop.v1.json": "contracts/remote-desktop.v1.json",
    "contracts/remote-test-vectors.json": "contracts/remote-test-vectors.json",
    "contracts/remote-authorization-vectors.json": "contracts/remote-authorization-vectors.json",
    "control-center/test-fixtures/rd-keyset-vectors.json": "contracts/rd-keyset-vectors.json",
    "contracts/generated/remote_protocol.kt": "app/src/main/java/io/github/zhanry/hometunnel/remote/protocol/RemoteProtocol.kt",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=source, text=True).strip())
    imported = []
    for original, destination in FILES.items():
        content = (source / original).read_bytes()
        path = ROOT / destination
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        imported.append({"source": original, "path": destination, "sha256": hashlib.sha256(content).hexdigest()})
    for name in ("remote-test-vectors.json", "rd-keyset-vectors.json", "remote-authorization-vectors.json"):
        vectors = ROOT / "app/src/test/resources" / name
        vectors.parent.mkdir(parents=True, exist_ok=True)
        vectors.write_bytes((ROOT / "contracts" / name).read_bytes())
    hashes = {item["source"]: item["sha256"] for item in imported}
    lock = {"schema_version": 1, "repository": "ZHanry/home-tunnel-server", "source_revision": revision,
            "source_tree_dirty": dirty, "published_contract_ref": None,
            "source_tree_sha256": hashlib.sha256(json.dumps(hashes, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
            "files": imported}
    (ROOT / "contracts/remote.lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print("Imported exact generated RD snapshot; no unpublished API tag is claimed")


if __name__ == "__main__":
    main()
