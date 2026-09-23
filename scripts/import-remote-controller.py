"""Import a reviewed, same-source arm64 controller SDK built by pinned Linux CI.

This does not establish physical-device acceptance. The default security-core
build remains available separately and is never silently replaced.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def verify_sdk(directory, expected_manifest):
    directory = directory.resolve()
    manifest_path = directory / "android-webrtc-build.json"
    if not re.fullmatch(r"[0-9a-f]{64}", expected_manifest) or sha(manifest_path) != expected_manifest:
        raise SystemExit("SDK manifest does not match its explicitly reviewed SHA-256")
    manifest = json.loads(manifest_path.read_text())
    lock = json.loads((ROOT / "native/remote-source.lock.json").read_text())
    if manifest.get("source_modified") is not False or lock.get("source_tree_dirty") is not False:
        raise SystemExit("Modified source builds cannot be imported")
    if manifest.get("source_revision") != lock.get("source_revision") or not re.fullmatch(r"[0-9a-f]{40}", str(lock.get("source_revision", ""))):
        raise SystemExit("Controller commit differs from the app's immutable source snapshot")
    if manifest.get("target") != "arm64-v8a" or manifest.get("android_api") != 26 or manifest.get("controller_backend_linked") is not True:
        raise SystemExit("SDK has no reviewed Android arm64/API26 backend")
    if manifest.get("source_files") != lock.get("source_files") or manifest.get("source_tree_sha256") != lock.get("source_tree_sha256"):
        raise SystemExit("Controller source tree differs from the app's immutable same-source lock")
    if manifest.get("upstream_lock_sha256") != lock.get("deps_lock_sha256"):
        raise SystemExit("Controller WebRTC dependency lock mismatch")
    recipe = ROOT / "native/remote-source/android/android-build.lock.json"
    if sha(recipe) != manifest.get("recipe_sha256") or json.loads(recipe.read_text())["gn_args"] != manifest.get("gn_args"):
        raise SystemExit("Controller toolchain or GN build policy mismatch")
    files = manifest.get("files")
    if not isinstance(files, dict) or not files:
        raise SystemExit("Missing SDK inventory")
    actual = {path.relative_to(directory).as_posix() for path in directory.rglob("*") if path.is_file() and path != manifest_path}
    if actual != set(files):
        raise SystemExit("SDK inventory mismatch")
    for name, checksum in files.items():
        path = directory / name
        if not path.resolve().is_relative_to(directory) or path.is_symlink() or not re.fullmatch(r"[0-9a-f]{64}", checksum) or sha(path) != checksum:
            raise SystemExit("SDK path or file hash mismatch")
    if sha(directory / "include/home_tunnel/remote.h") != lock["header_sha256"]:
        raise SystemExit("Controller C ABI header mismatch")
    if not (directory / "LICENSE.md").is_file() or not (directory / "source-manifest.json").is_file():
        raise SystemExit("Controller dependency notices or provenance missing")
    library = directory / "lib/arm64-v8a/libhome_tunnel_remote.so"
    header = library.read_bytes()[:64]
    if len(header) != 64 or header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != 183:
        raise SystemExit("Controller is not an AArch64 ELF library")
    return manifest, lock


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sdk", type=Path)
    parser.add_argument("--reviewed-manifest-sha256", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/remote-controller")
    args = parser.parse_args()
    manifest, lock = verify_sdk(args.sdk, args.reviewed_manifest_sha256)
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Choose a new empty output directory; existing native products are never overwritten")
    target = output / "arm64-v8a"
    target.mkdir(parents=True)
    library = target / "libhome_tunnel_remote.so"
    shutil.copyfile(args.sdk / "lib/arm64-v8a" / library.name, library)
    header = output / "include/home_tunnel/remote.h"
    header.parent.mkdir(parents=True)
    shutil.copyfile(args.sdk / "include/home_tunnel/remote.h", header)
    for name in ("LICENSE.md", "source-manifest.json", "android-webrtc-build.json"):
        shutil.copyfile(args.sdk / name, output / name)
    record = {key: lock[key] for key in ("schema_version", "abi", "source_revision", "source_tree_dirty", "source_tree_sha256", "source_archive_sha256", "deps_lock_sha256", "header_sha256")}
    record.update(status="webrtc-controller-linked-device-acceptance-required", available=True, target="arm64-v8a",
                  library_sha256=sha(library), controller_manifest_sha256=args.reviewed_manifest_sha256,
                  controller_source_revision=manifest["source_revision"], device_media_accepted=False,
                  android_api=26, flexible_page_sizes=True)
    (target / "remote-artifact.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print("Imported real arm64 controller. Physical-device media acceptance remains required.")
    print(f"Build release ABI with -PremoteNativeRoot={output} -PremoteControllerArm64=true")


if __name__ == "__main__":
    main()
