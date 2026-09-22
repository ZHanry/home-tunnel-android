"""Check every release native library, including transitive C++ runtime and 16 KiB ELF pages."""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import zipfile


def package_libraries(path, prefix, core_hash):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise SystemExit(f"Duplicate archive entries: {path}")
        libraries = {name: archive.read(name) for name in names if name.endswith(".so")}
    if not libraries or any(not name.startswith(prefix) for name in libraries):
        raise SystemExit(f"Release package must contain arm64-v8a libraries only: {path}")
    required = {"libhome_tunnel_remote.so", "libhome_tunnel_remote_jni.so", "libc++_shared.so"}
    if not required.issubset({Path(name).name for name in libraries}):
        raise SystemExit(f"Missing shared core, JNI bridge or C++ runtime: {path}")
    records = {}
    for name, data in libraries.items():
        if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
            raise SystemExit(f"Expected an AArch64 ELF64 library: {name}")
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        if entry_size != 56 or not count or offset + entry_size * count > len(data):
            raise SystemExit(f"Invalid ELF program headers: {name}")
        alignments = [struct.unpack_from("<Q", data, offset + index * entry_size + 48)[0]
                      for index in range(count)
                      if struct.unpack_from("<I", data, offset + index * entry_size)[0] == 1]
        if not alignments or any(value < 16384 or value & (value - 1) for value in alignments):
            raise SystemExit(f"Native library does not support 16 KiB pages: {name}")
        records[Path(name).name] = {"sha256": hashlib.sha256(data).hexdigest(), "load_alignments": alignments}
    if records["libhome_tunnel_remote.so"]["sha256"] != core_hash:
        raise SystemExit(f"Packaged shared core differs from its provenance: {path}")
    return records


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("aab", type=Path)
    parser.add_argument("native_manifest", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.native_manifest.read_text())
    apk = package_libraries(args.apk, "lib/arm64-v8a/", manifest["library_sha256"])
    aab = package_libraries(args.aab, "base/lib/arm64-v8a/", manifest["library_sha256"])
    if apk != aab:
        raise SystemExit("APK and AAB must carry the identical native library set")
    print(json.dumps({"status": "passed", "abi": "arm64-v8a", "page_size": 16384, "libraries": apk}, indent=2))


if __name__ == "__main__":
    main()
