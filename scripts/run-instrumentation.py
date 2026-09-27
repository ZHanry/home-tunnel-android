"""Install debug fixtures on one explicit emulator and verify AndroidJUnitRunner results."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--output", type=Path, default=ROOT / "instrumentation-evidence")
parser.add_argument("--test-class", choices=["RemoteDocumentIoTest"], help="Run the bounded file/provider checks only; not full app acceptance")
args = parser.parse_args()
if not re.fullmatch(r"emulator-\d+", args.serial):
    parser.error("This fixture runner installs only on an explicitly selected emulator")
args.output.mkdir(parents=True, exist_ok=True)
adb = [args.adb, "-s", args.serial]
tracked = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
source_files = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
                for name in sorted(set(filter(None, tracked))) if (ROOT / name).is_file()}
source_bytes = (json.dumps(source_files, sort_keys=True, separators=(",", ":")) + "\n").encode()
(args.output / "source-manifest.json").write_bytes(source_bytes)
packages = {}
for apk in ("app/build/outputs/apk/debug/app-debug.apk",
            "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
    with (ROOT / apk).open("rb") as stream:
        packages[apk] = {"sha256": hashlib.file_digest(stream, "sha256").hexdigest(), "bytes": (ROOT / apk).stat().st_size}
    subprocess.run([*adb, "install", "-r", "-t", str(ROOT / apk)], check=True, timeout=120)
selected = ["-e", "class", "io.github.zhanry.hometunnel.remote." + args.test_class] if args.test_class else []
result = subprocess.run(
    [*adb, "shell", "am", "instrument", "-w", "-r", *selected,
     "io.github.zhanry.hometunnel.debug.test/androidx.test.runner.AndroidJUnitRunner"],
    text=True, encoding="utf-8", errors="replace", stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT, timeout=300,
)
(args.output / "instrumentation.log").write_text(result.stdout, encoding="utf-8")
for filename, command in (("logcat.txt", ["logcat", "-d", "-t", "2500"]),
                          ("audio-flinger.txt", ["dumpsys", "media.audio_flinger"]),
                          ("audio-policy.txt", ["dumpsys", "media.audio_policy"])):
    diagnostic = subprocess.run([*adb, "shell", *command], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=30)
    (args.output / filename).write_bytes(diagnostic.stdout)
print(result.stdout, flush=True)
match = re.search(r"OK \((\d+) tests?\)", result.stdout)
outcomes = [int(value) for value in re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)", result.stdout, re.M)]
expected = [args.test_class] if args.test_class else ["SecureStateStoreTest", "ManagementUiTest", "AdminUiTest", "AdminIdentityTest", "RemoteIdentityTest", "RemoteDocumentIoTest"]
passed = (result.returncode == 0 and match is not None and int(match[1]) >= (4 if args.test_class else 22)
          and all(name in result.stdout for name in expected)
          and not any(value in (-1, -2) for value in outcomes)
          and "FAILURES!!!" not in result.stdout and "INSTRUMENTATION_FAILED" not in result.stdout)
api_level = subprocess.check_output([*adb, "shell", "getprop", "ro.build.version.sdk"], text=True).strip()
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
fingerprint = subprocess.check_output([*adb, "shell", "getprop", "ro.build.fingerprint"], text=True).strip()
dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT).strip())
report = {"status": "passed" if passed else "failed", "api_level": int(api_level),
          "tests": sum(value <= 0 for value in outcomes), "passed_tests": outcomes.count(0),
          "failed_tests": sum(value in (-1, -2) for value in outcomes),
          "skipped_tests": sum(value in (-3, -4) for value in outcomes), "repository_revision": revision,
          "source_dirty": dirty, "source_manifest_sha256": hashlib.sha256(source_bytes).hexdigest(),
          "packages": packages, "serial": args.serial, "system_fingerprint": fingerprint,
          "test_classes": expected, "full_app_acceptance": False,
          "remote_media_acceptance": "not_run", "variant": "debug"}
(args.output / "instrumentation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
if not passed:
    raise SystemExit("Android instrumentation did not pass every selected check")
