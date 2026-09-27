"""Fail closed before any Android candidate signing.

A missing lock, an unconfirmed signer, or a snapshot mismatch stops the build.
This does not download a run and does not invent digests.
"""
import json
import hashlib
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
lock_path = ROOT / "native/controller-sdk-candidate.lock.json"
if not lock_path.is_file():
    sys.exit("Import the trusted SDK candidate before signing. No run id or digest was invented.")
lock = json.loads(lock_path.read_text(encoding="utf-8"))
if lock.get("signer_identity_observed") is not True:
    sys.exit("SDK candidate signer identity is unresolved. Refusing to sign until an attestation bundle is verified.")
if lock.get("matches_android_snapshot") is not True:
    sys.exit("SDK candidate source does not match the Android snapshot.")
source = json.loads((ROOT / "native/remote-source.lock.json").read_text(encoding="utf-8"))
if (lock.get("status") != "sdk-candidate-imported" or lock.get("repository") != "ZHanry/home-tunnel-client" or
        lock.get("source_revision") != source.get("source_revision") or source.get("source_tree_dirty") is not False or
        lock.get("source_tree_sha256") != source.get("source_tree_sha256") or
        lock.get("upstream_lock_sha256") != source.get("deps_lock_sha256") or
        not re.fullmatch(r"[0-9a-f]{64}", str(lock.get("artifact_sha256", ""))) or
        not re.fullmatch(r"[1-9][0-9]*", str(lock.get("artifact_id", ""))) or
        not re.fullmatch(r"[1-9][0-9]*", str(lock.get("workflow_run_id", ""))) or
        set(lock.get("abis", {})) != {"arm64-v8a", "x86_64"}):
    sys.exit("SDK candidate lock is incomplete or belongs to another source snapshot.")
directory = ROOT / "native/remote-source"
actual = {path.relative_to(directory).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
          for path in directory.rglob("*") if path.is_file()}
if actual != source.get("source_files"):
    sys.exit("The imported native source snapshot has local modifications.")
print(f"Confirmed SDK candidate run {lock.get('workflow_run_id')} for {lock.get('source_revision')}")
