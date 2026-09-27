"""Verify imported Android native artifacts against the reviewed same-source lock."""
from pathlib import Path
import argparse
import hashlib
import json
from remote_emulator_policy import verify_manifest
from remote_production_abi import require_library, require_build_policy

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(directory, abis, emulator_test=False, production=False):
    lock = json.loads((ROOT / "native/remote-source.lock.json").read_text())
    if production and emulator_test:
        raise SystemExit("A production ABI cannot use the emulator test policy")
    if emulator_test and abis != ["x86_64"]:
        raise SystemExit("Emulator controller verification is x86_64-only")
    if production and abis not in (["arm64-v8a"], ["x86_64"]):
        raise SystemExit("Select one production ABI")
    if digest(directory / "include/home_tunnel/remote.h") != lock["header_sha256"]:
        raise SystemExit("Native ABI header hash does not match the source lock")
    for abi in abis:
        record = json.loads((directory / abi / "remote-artifact.json").read_text())
        controller = record.get("status") == "webrtc-controller-linked-device-acceptance-required"
        if production:
            if (not controller or record.get("test_only") is True or record.get("available") is not True or
                    record.get("device_media_accepted") is not False or record.get("source_tree_dirty") is not False):
                raise SystemExit("Security-core or emulator artifacts are not a production controller")
            manifest_path = directory / "android-webrtc-build.json"
            if digest(manifest_path) != record.get("controller_manifest_sha256"):
                raise SystemExit("Production build manifest differs from the imported digest")
            manifest = json.loads(manifest_path.read_text())
            require_library(directory / abi / "libhome_tunnel_remote.so", abi)
            if (manifest.get("source_revision") != lock.get("source_revision") or manifest.get("source_tree_sha256") != lock.get("source_tree_sha256") or
                    manifest.get("source_files") != lock.get("source_files") or record.get("controller_source_revision") != lock.get("source_revision")):
                raise SystemExit("Production controller source does not match the app snapshot")
            if manifest.get("upstream_lock_sha256") != lock.get("deps_lock_sha256") or digest(directory / "include/home_tunnel/remote.h") != lock["header_sha256"]:
                raise SystemExit("Production controller header or dependency lock mismatch")
            recipe = ROOT / "native/remote-source/android/android-build.lock.json"
            recipe_body = json.loads(recipe.read_text())
            if manifest.get("recipe_sha256") != digest(recipe):
                raise SystemExit("Production recipe hash does not match the app snapshot")
            require_build_policy(manifest, abi, recipe_body, json.loads((ROOT / "native/remote-source/remote-deps.lock.json").read_text()))
            if manifest.get("files", {}).get(f"lib/{abi}/libhome_tunnel_remote.so") != record.get("library_sha256"):
                raise SystemExit("Production library differs from its manifest inventory")
        elif controller:
            manifest_path = directory / "android-webrtc-build.json"
            if abi != "arm64-v8a" or record.get("available") is not True or record.get("device_media_accepted") is not False or digest(manifest_path) != record.get("controller_manifest_sha256"):
                raise SystemExit("Unexpected controller ABI/capability or reviewed build manifest")
            manifest = json.loads(manifest_path.read_text())
            if manifest.get("source_revision") != lock.get("source_revision") or record.get("controller_source_revision") != lock.get("source_revision") or manifest.get("source_modified") is not False:
                raise SystemExit("Controller build commit differs from the immutable source snapshot")
            if manifest.get("source_files") != lock.get("source_files") or manifest.get("source_tree_sha256") != lock.get("source_tree_sha256") or manifest.get("upstream_lock_sha256") != lock.get("deps_lock_sha256"):
                raise SystemExit("Controller build has a different same-source identity")
            if manifest.get("files", {}).get("lib/arm64-v8a/libhome_tunnel_remote.so") != record.get("library_sha256"):
                raise SystemExit("Controller library differs from reviewed SDK inventory")
            if manifest.get("recipe_sha256") != digest(ROOT / "native/remote-source/android/android-build.lock.json") or manifest.get("controller_backend_linked") is not True:
                raise SystemExit("Controller build policy or linked backend mismatch")
        elif emulator_test:
            verify_manifest(directory, record, lock)
        elif record.get("status") != lock.get("status") or record.get("available") != lock.get("available"):
            raise SystemExit("Unknown native artifact capability")
        for key in ("abi", "source_revision", "source_tree_sha256", "source_archive_sha256", "deps_lock_sha256", "header_sha256"):
            if emulator_test and key == "source_tree_sha256":
                continue
            if record.get(key) != lock[key]:
                raise SystemExit(f"Native artifact provenance mismatch: {abi} {key}")
        if record.get("target") != abi or record.get("library_sha256") != digest(directory / abi / "libhome_tunnel_remote.so"):
            raise SystemExit(f"Native library hash/ABI mismatch: {abi}")
    if production:
        print(f"Production {abis[0]} controller identity verified; device acceptance remains required")
    else:
        print("Emulator-only native backend verified; arm64 runtime remains untested" if emulator_test else "Native ABI, source lock and library hashes verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--abis", nargs="+", default=["arm64-v8a", "x86_64"], choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--emulator-test", action="store_true")
    parser.add_argument("--production", action="store_true")
    args = parser.parse_args()
    verify(args.directory, args.abis, args.emulator_test, args.production)
