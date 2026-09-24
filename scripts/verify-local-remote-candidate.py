"""Verify a dirty-tree native SDK for local debug testing, never for release."""

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path


EXPECTED_EXPORTS = {
    "ht_rd_abi_version", "ht_rd_create", "ht_rd_get_capabilities", "ht_rd_start",
    "ht_rd_on_signal", "ht_rd_submit_input", "ht_rd_set_surface", "ht_rd_pause",
    "ht_rd_close", "ht_rd_release",
}
EXPECTED_DEPENDENCIES = {"libandroid.so", "liblog.so", "libdl.so", "libm.so", "libc.so"}
NDK_VERSION = "27.2.12479018"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def source_digest(source):
    entries = list(source.rglob("*"))
    files = sorted(path for path in entries if path.is_file())
    if any(path.is_symlink() for path in entries) or not 60 <= len(files) <= 100 or any(path.stat().st_size > 10_000_000 for path in files):
        raise SystemExit("Local source tree contains unexpected files")
    entries = {path.relative_to(source).as_posix(): digest(path) for path in files}
    return hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def verify_elf(library, abi, ndk):
    header = library.read_bytes()[:64]
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    if len(header) != 64 or header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[16:18], "little") != 3 or int.from_bytes(header[18:20], "little") != machine:
        raise SystemExit(f"Invalid local candidate ELF: {abi}")
    platform = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    binary = ndk / "toolchains/llvm/prebuilt" / platform / "bin"
    suffix = ".exe" if os.name == "nt" else ""

    def output(tool, *arguments):
        return subprocess.run([str(binary / (tool + suffix)), *arguments, str(library)],
                              check=True, capture_output=True, text=True, timeout=30).stdout

    segments = output("llvm-readelf", "--wide", "--program-headers")
    alignments = [int(value, 16) for value in re.findall(r"^\s*LOAD\s+.*\s+(0x[0-9a-fA-F]+)\s*$", segments, re.MULTILINE)]
    if not alignments or any(value < 16384 or value % 16384 for value in alignments):
        raise SystemExit(f"Local candidate lacks 16 KiB page alignment: {abi}")
    dynamic = output("llvm-readelf", "--dynamic")
    dependencies = set(re.findall(r"Shared library: \[(.*?)\]", dynamic))
    if "TEXTREL" in dynamic or dependencies - EXPECTED_DEPENDENCIES:
        raise SystemExit(f"Unexpected native dependencies or text relocations: {abi}")
    exported = output("llvm-nm", "--dynamic", "--defined-only")
    symbols = {line.split()[-1].split("@")[0] for line in exported.splitlines() if line.strip()}
    if symbols - {"HT_REMOTE_ANDROID_1"} != EXPECTED_EXPORTS:
        raise SystemExit(f"Unexpected local candidate C ABI: {abi}")


def expected_args(source, abi):
    recipe = json.loads((source / "android/android-build.lock.json").read_text(encoding="utf-8"))
    args = dict(recipe["gn_args"])
    args["target_cpu"] = "arm64" if abi == "arm64-v8a" else "x64"
    return "".join(f"{key} = {json.dumps(value)}\n" for key, value in args.items())


def inspect(sdk, source, ndk, abis):
    if source.name != "remote" or source.parent.name != "native" or not (source / "include/home_tunnel/remote.h").is_file():
        raise SystemExit("Select the client native/remote source directory")
    for path in (sdk, source, sdk / "LICENSE.md", sdk / "include/home_tunnel/remote.h", sdk / "local-candidate.json"):
        if path.is_symlink():
            raise SystemExit("Local candidate inputs cannot be symlinks")
    if not re.search(r"^Pkg\.Revision\s*=\s*" + re.escape(NDK_VERSION) + r"\s*$",
                     (ndk / "source.properties").read_text(encoding="utf-8"), re.MULTILINE):
        raise SystemExit("Local candidate requires the pinned NDK")
    hashes = {"source_tree_sha256": source_digest(source),
              "header_sha256": digest(source / "include/home_tunnel/remote.h"),
              "notice_sha256": digest(sdk / "LICENSE.md"), "abis": {}}
    if digest(sdk / "include/home_tunnel/remote.h") != hashes["header_sha256"]:
        raise SystemExit("Local candidate header differs from current native source")
    for abi in abis:
        args = sdk / ("arm64-args.gn" if abi == "arm64-v8a" else "x86_64-args.gn")
        library = sdk / abi / "libhome_tunnel_remote.so"
        if args.is_symlink() or library.is_symlink():
            raise SystemExit("Local candidate inputs cannot be symlinks")
        if args.read_text(encoding="utf-8") != expected_args(source, abi):
            raise SystemExit(f"Local candidate GN recipe differs from current source: {abi}")
        verify_elf(library, abi, ndk)
        hashes["abis"][abi] = {"library_sha256": digest(library), "args_sha256": digest(args)}
    return hashes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--inspect", action="store_true")
    args = parser.parse_args()
    abis = [args.abi] if args.abi else ["arm64-v8a", "x86_64"]
    hashes = inspect(args.sdk.resolve(), args.source.resolve(), args.ndk.resolve(), abis)
    if args.inspect:
        print(json.dumps(hashes, indent=2, sort_keys=True))
        return
    manifest = json.loads((args.sdk / "local-candidate.json").read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("status") != "local-test-only" or manifest.get("release_eligible") is not False or manifest.get("arm64_runtime_verified") is not False or manifest.get("version") != "9.0.0":
        raise SystemExit("Local candidate is not explicitly marked as non-release")
    if any(manifest.get(key) != value for key, value in hashes.items()):
        if args.abi:
            expected_abis = manifest.get("abis", {})
            if any(manifest.get(key) != value for key, value in hashes.items() if key != "abis") or expected_abis.get(args.abi) != hashes["abis"][args.abi]:
                raise SystemExit("Local candidate source or binary hash mismatch")
        else:
            raise SystemExit("Local candidate source or binary hash mismatch")
    print("Verified local test-only native SDK; arm64 runtime remains unverified")


if __name__ == "__main__":
    main()
