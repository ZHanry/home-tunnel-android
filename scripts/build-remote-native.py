"""Build the hash-locked shared Home Tunnel core; no third-party WebRTC AAR is used."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_sources():
    lock = json.loads((ROOT / "native/remote-source.lock.json").read_text())
    source = ROOT / "native/remote-source"
    actual = {path.relative_to(source).as_posix() for path in source.rglob("*") if path.is_file()}
    if actual != set(lock["source_files"]):
        raise SystemExit("Shared core source file set differs from its reviewed lock")
    for name, checksum in lock["source_files"].items():
        if sha(source / name) != checksum:
            raise SystemExit(f"Shared core source hash mismatch: {name}")
    tree_hash = hashlib.sha256(json.dumps(lock["source_files"], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if tree_hash != lock["source_tree_sha256"] or sha(source / "remote-deps.lock.json") != lock["deps_lock_sha256"] or sha(source / "include/home_tunnel/remote.h") != lock["header_sha256"]:
        raise SystemExit("Shared core tree/dependency/header identity mismatch")
    if lock["abi"] != 1 or lock["available"] is not False or lock["status"] != "security-core-only-media-unavailable":
        raise SystemExit("Review native capability policy before changing the locked media backend")
    return lock, source


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--abis", nargs="+", choices=("arm64-v8a", "x86_64"), default=["arm64-v8a", "x86_64"])
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/remote-native")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    lock, source = verify_sources()
    if args.verify_only:
        print("Shared core source hashes verified; media backend unavailable")
        return
    if not args.sdk:
        raise SystemExit("Set ANDROID_HOME or pass --sdk")
    sdk = Path(args.sdk)
    ndk = sdk / "ndk/27.2.12479018"
    suffix = ".exe" if os.name == "nt" else ""
    cmake = sdk / f"cmake/3.22.1/bin/cmake{suffix}"
    ninja = sdk / f"cmake/3.22.1/bin/ninja{suffix}"
    if not (ndk / "build/cmake/android.toolchain.cmake").is_file() or not cmake.is_file():
        raise SystemExit("Install NDK 27.2.12479018 and CMake 3.22.1 using sdkmanager")
    output = args.output.resolve()
    for abi in args.abis:
        build = ROOT / f".cache/native-build/{abi}"
        subprocess.run([str(cmake), "-S", str(source), "-B", str(build), "-G", "Ninja",
                        f"-DCMAKE_MAKE_PROGRAM={ninja}", f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
                        f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_shared",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTING=OFF", "-DHT_RD_BUILD_WORKER=OFF"], check=True)
        subprocess.run([str(cmake), "--build", str(build), "--target", "home_tunnel_remote", "--parallel", "2"], check=True)
        target = output / abi
        target.mkdir(parents=True, exist_ok=True)
        library = target / "libhome_tunnel_remote.so"
        shutil.copyfile(build / library.name, library)
        record = {key: lock[key] for key in ("schema_version", "abi", "status", "source_revision", "source_tree_dirty",
                                           "source_tree_sha256", "source_archive_sha256", "deps_lock_sha256", "header_sha256", "available")}
        record.update(target=abi, library_sha256=sha(library), ndk_version="27.2.12479018", android_api=26, flexible_page_sizes=True)
        (target / "remote-artifact.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    header = output / "include/home_tunnel/remote.h"
    header.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / "include/home_tunnel/remote.h", header)
    shutil.copyfile(ROOT / "native/remote-source.lock.json", output / "remote-source.lock.json")
    print("Native security core built. Media availability remains false.")


if __name__ == "__main__":
    main()
