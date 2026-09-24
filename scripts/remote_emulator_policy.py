"""Provenance rules for an x86_64 emulator-only WebRTC controller build."""

from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
ARM64_ASSERT = 'assert(is_android && current_cpu == "arm64")'
EMULATOR_ASSERT = 'assert(is_android && (current_cpu == "arm64" || current_cpu == "x64"))'


def sha(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def source_files(lock):
    path = ROOT / "native/remote-source/android/BUILD.gn"
    original = path.read_text(encoding="utf-8")
    if original.count(ARM64_ASSERT) != 1 or sha(path) != lock["source_files"]["android/BUILD.gn"]:
        raise SystemExit("The reviewed Android GN source has changed")
    patched = original.replace(ARM64_ASSERT, EMULATOR_ASSERT)
    expected = dict(lock["source_files"])
    expected["android/BUILD.gn"] = hashlib.sha256(patched.encode()).hexdigest()
    tree = hashlib.sha256(json.dumps(expected, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return expected, tree


def gn_args():
    recipe = json.loads((ROOT / "native/remote-source/android/android-build.lock.json").read_text())
    args = dict(recipe["gn_args"])
    if args.get("target_os") != "android" or args.get("target_cpu") != "arm64" or args.get("default_min_sdk_version") != 26:
        raise SystemExit("The reviewed Android GN recipe has changed")
    args["target_cpu"] = "x64"
    return args


def gn_text(args):
    return "".join(f"{key} = {json.dumps(value)}\n" for key, value in args.items())


def verify_manifest(directory, record, lock):
    manifest_path = directory / "emulator-webrtc-build.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected_files, tree = source_files(lock)
    upstream = json.loads((ROOT / "native/remote-source/remote-deps.lock.json").read_text())
    if (directory / "emulator-args.gn").read_text(encoding="utf-8") != gn_text(gn_args()):
        raise SystemExit("Emulator GN argument file differs from the reviewed recipe")
    if (record.get("status") != "webrtc-controller-linked-emulator-test" or
            record.get("target") != "x86_64" or record.get("available") is not True or
            record.get("test_only") is not True or record.get("device_media_accepted") is not False or
            record.get("source_tree_dirty") is not True or record.get("source_tree_sha256") != tree or
            record.get("base_source_tree_sha256") != lock["source_tree_sha256"] or
            record.get("controller_manifest_sha256") != sha(manifest_path)):
        raise SystemExit("An emulator-only controller requires explicit test provenance")
    if (manifest.get("schema_version") != 1 or manifest.get("status") != "emulator-test-only" or
            manifest.get("test_only") is not True or manifest.get("source_modified") is not True or
            manifest.get("target") != "x86_64" or manifest.get("android_api") != 26 or
            manifest.get("controller_backend_linked") is not True or manifest.get("device_media_accepted") is not False or
            manifest.get("source_revision") != lock["source_revision"] or
            manifest.get("upstream_lock_sha256") != lock["deps_lock_sha256"] or
            manifest.get("webrtc_revision") != upstream["webrtc"]["revision"] or
            manifest.get("source_files") != expected_files or manifest.get("source_tree_sha256") != tree or
            manifest.get("gn_args") != gn_args() or
            manifest.get("library_sha256") != record.get("library_sha256") or
            manifest.get("build_log_sha256") != sha(directory / "emulator-build.log")):
        raise SystemExit("Emulator controller build provenance does not match the reviewed source override")
