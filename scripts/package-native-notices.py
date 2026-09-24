"""Carry the exact linked WebRTC and pinned NDK notices inside Android packages."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
NDK_VERSION = "27.2.12479018"


def notice(path):
    if not path.is_file() or path.is_symlink() or not 0 < path.stat().st_size <= 4 * 1024 * 1024:
        raise SystemExit(f"Required native notice is missing, linked or oversized: {path.name}")
    data = path.read_bytes()
    data.decode("utf-8")
    if b"\0" in data:
        raise SystemExit("Native notice contains binary data")
    return data


def package(sdk, ndk, output, local_candidate=False):
    build = json.loads((sdk / ("local-candidate.json" if local_candidate else "android-webrtc-build.json")).read_text())
    if local_candidate:
        if build.get("status") != "local-test-only" or build.get("release_eligible") is not False:
            raise SystemExit("Local candidate cannot provide release notices")
        build = {"source_revision": "local-test-only", "files": {
            "LICENSE.md": build["notice_sha256"],
            "lib/arm64-v8a/libhome_tunnel_remote.so": build["abis"]["arm64-v8a"]["library_sha256"],
        }}
    properties = (ndk / "source.properties").read_text()
    if not re.search(r"^Pkg\.Revision\s*=\s*" + re.escape(NDK_VERSION) + r"\s*$", properties, re.MULTILINE):
        raise SystemExit("Native runtime notices must come from the pinned NDK")
    sources = {"PROJECT-LICENSE": ROOT / "LICENSE", "WEBRTC-LICENSE.md": sdk / "LICENSE.md",
               "NDK-NOTICE": ndk / "NOTICE", "NDK-NOTICE.toolchain": ndk / "NOTICE.toolchain"}
    content = {name: notice(path) for name, path in sources.items()}
    files = {name: hashlib.sha256(data).hexdigest() for name, data in content.items()}
    if files["WEBRTC-LICENSE.md"] != build.get("files", {}).get("LICENSE.md"):
        raise SystemExit("Linked engine notices differ from the reviewed SDK")
    output.mkdir(parents=True, exist_ok=True)
    if {path.name for path in output.iterdir()} - (set(content) | {"native-notices.json"}):
        raise SystemExit("Unexpected generated native notice output")
    for name, data in content.items():
        destination = output / name
        if destination.is_symlink():
            raise SystemExit("Native notice output cannot be a symlink")
        destination.write_bytes(data)
    record = {"schema_version": 1, "ndk_version": NDK_VERSION, "source_revision": build["source_revision"],
              "library_sha256": build["files"]["lib/arm64-v8a/libhome_tunnel_remote.so"], "files": files}
    (output / "native-notices.json").write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--local-candidate", action="store_true")
    args = parser.parse_args()
    package(args.sdk, args.ndk, args.output, args.local_candidate)
