"""Import a real WebRTC controller for local x86_64 emulator testing only."""

from pathlib import Path
import argparse
import json
import os
import re
import shutil
import subprocess

from remote_emulator_policy import ROOT, gn_args, gn_text, sha, source_files

EXPORTS = {
    "ht_rd_abi_version", "ht_rd_create", "ht_rd_get_capabilities", "ht_rd_start",
    "ht_rd_on_signal", "ht_rd_submit_input", "ht_rd_set_surface", "ht_rd_pause",
    "ht_rd_close", "ht_rd_release",
}


def output(command):
    return subprocess.run(command, check=True, text=True, capture_output=True).stdout


def verify_elf(library, ndk):
    header = library.read_bytes()[:64]
    if (len(header) != 64 or header[:6] != b"\x7fELF\x02\x01" or
            int.from_bytes(header[16:18], "little") != 3 or int.from_bytes(header[18:20], "little") != 62):
        raise SystemExit("Emulator controller is not a 64-bit x86_64 shared ELF library")
    tools = ndk / "toolchains/llvm/prebuilt" / ("windows-x86_64" if os.name == "nt" else "linux-x86_64") / "bin"
    suffix = ".exe" if os.name == "nt" else ""
    readelf = tools / ("llvm-readelf" + suffix)
    nm = tools / ("llvm-nm" + suffix)
    segments = output([str(readelf), "--wide", "--program-headers", str(library)])
    alignments = [int(value, 16) for value in re.findall(r"^\s*LOAD\s+.*\s+(0x[0-9a-fA-F]+)\s*$", segments, re.MULTILINE)]
    if not alignments or any(value < 16384 or value % 16384 for value in alignments):
        raise SystemExit("Emulator controller does not support Android 16 KiB pages")
    dynamic = output([str(readelf), "--dynamic", str(library)])
    dependencies = set(re.findall(r"Shared library: \[(.*?)\]", dynamic))
    if "TEXTREL" in dynamic or dependencies - {"libandroid.so", "liblog.so", "libdl.so", "libm.so", "libc.so"}:
        raise SystemExit("Emulator controller has unsafe native dependencies")
    exported = output([str(nm), "--dynamic", "--defined-only", str(library)])
    symbols = {line.split()[-1].split("@")[0] for line in exported.splitlines() if line.strip()}
    if symbols - {"HT_REMOTE_ANDROID_1"} != EXPORTS:
        raise SystemExit("Emulator controller exports an unexpected ABI")


def import_controller(library, source, args_file, build_log, notices, ndk, destination):
    lock = json.loads((ROOT / "native/remote-source.lock.json").read_text())
    expected_files, tree = source_files(lock)
    actual = {path.relative_to(source).as_posix(): sha(path) for path in source.rglob("*") if path.is_file()}
    if actual != expected_files:
        raise SystemExit("Emulator source differs from the reviewed source plus the exact x64 GN override")
    if args_file.read_text(encoding="utf-8") != gn_text(gn_args()):
        raise SystemExit("Emulator GN arguments differ from the reviewed Android recipe")
    if not build_log.is_file() or build_log.stat().st_size < 1000 or not notices.is_file() or notices.stat().st_size < 1000:
        raise SystemExit("Emulator build log or dependency notices are missing")
    verify_elf(library, ndk)
    if destination.exists() and any(destination.iterdir()):
        raise SystemExit("Choose a new empty emulator artifact directory")
    target = destination / "x86_64"
    target.mkdir(parents=True)
    imported_library = target / "libhome_tunnel_remote.so"
    shutil.copyfile(library, imported_library)
    header = destination / "include/home_tunnel/remote.h"
    header.parent.mkdir(parents=True)
    shutil.copyfile(source / "include/home_tunnel/remote.h", header)
    shutil.copyfile(build_log, destination / "emulator-build.log")
    shutil.copyfile(args_file, destination / "emulator-args.gn")
    shutil.copyfile(notices, destination / "LICENSE.md")
    manifest = {
        "schema_version": 1, "status": "emulator-test-only", "test_only": True,
        "target": "x86_64", "android_api": 26, "controller_backend_linked": True,
        "device_media_accepted": False, "source_modified": True,
        "source_revision": lock["source_revision"], "source_files": expected_files,
        "source_tree_sha256": tree, "upstream_lock_sha256": lock["deps_lock_sha256"],
        "webrtc_revision": json.loads((source / "remote-deps.lock.json").read_text())["webrtc"]["revision"],
        "gn_args": gn_args(), "library_sha256": sha(imported_library),
        "build_log_sha256": sha(destination / "emulator-build.log"),
    }
    manifest_path = destination / "emulator-webrtc-build.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    record = {key: lock[key] for key in (
        "schema_version", "abi", "source_revision", "source_archive_sha256",
        "deps_lock_sha256", "header_sha256",
    )}
    record.update(status="webrtc-controller-linked-emulator-test", available=True,
                  target="x86_64", library_sha256=sha(imported_library),
                  source_tree_dirty=True, source_tree_sha256=tree,
                  base_source_tree_sha256=lock["source_tree_sha256"],
                  controller_manifest_sha256=sha(manifest_path), test_only=True,
                  device_media_accepted=False, android_api=26, flexible_page_sizes=True)
    (target / "remote-artifact.json").write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Imported test-only x86_64 WebRTC controller; arm64 APK runtime is not verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--gn-args", required=True, type=Path)
    parser.add_argument("--build-log", required=True, type=Path)
    parser.add_argument("--notices", required=True, type=Path)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    options = parser.parse_args()
    import_controller(options.library, options.source, options.gn_args, options.build_log,
                      options.notices, options.ndk, options.output)
