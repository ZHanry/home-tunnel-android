"""Install debug fixtures on one explicit emulator and verify AndroidJUnitRunner results."""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--output", type=Path, default=ROOT / "instrumentation-evidence")
args = parser.parse_args()
if not re.fullmatch(r"emulator-\d+", args.serial):
    parser.error("This fixture runner installs only on an explicitly selected emulator")
args.output.mkdir(parents=True, exist_ok=True)
adb = [args.adb, "-s", args.serial]
for apk in ("app/build/outputs/apk/debug/app-debug.apk",
            "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
    subprocess.run([*adb, "install", "-r", "-t", str(ROOT / apk)], check=True, timeout=120)
result = subprocess.run(
    [*adb, "shell", "am", "instrument", "-w", "-r",
     "io.github.zhanry.hometunnel.debug.test/androidx.test.runner.AndroidJUnitRunner"],
    text=True, encoding="utf-8", errors="replace", stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT, timeout=300,
)
(args.output / "instrumentation.log").write_text(result.stdout, encoding="utf-8")
print(result.stdout, flush=True)
match = re.search(r"OK \((\d+) tests?\)", result.stdout)
passed = (result.returncode == 0 and match is not None and int(match[1]) >= 8
          and "SecureStateStoreTest" in result.stdout and "ManagementUiTest" in result.stdout
          and "FAILURES!!!" not in result.stdout and "INSTRUMENTATION_FAILED" not in result.stdout)
api_level = subprocess.check_output([*adb, "shell", "getprop", "ro.build.version.sdk"], text=True).strip()
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
report = {"status": "passed" if passed else "failed", "api_level": int(api_level),
          "tests": int(match[1]) if match else 0, "repository_revision": revision,
          "android_keystore": True, "variant": "debug"}
(args.output / "instrumentation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
if not passed:
    raise SystemExit("Android instrumentation did not pass the storage and management UI checks")
