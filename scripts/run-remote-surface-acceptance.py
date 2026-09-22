#!/usr/bin/env python3
"""Run opt-in real media acceptance on an explicitly selected, connected Android device."""

import argparse
import json
import subprocess
from pathlib import Path


PACKAGE = "io.github.zhanry.hometunnel.debug"
TEST = "io.github.zhanry.hometunnel.remote.RemoteSurfaceAcceptanceTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--fixture", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    payload = args.fixture.read_bytes()
    if not 0 < len(payload) <= 16384:
        raise SystemExit("Fixture must be a JSON object no larger than 16 KiB")
    fixture = json.loads(payload)
    if not isinstance(fixture, dict) or fixture.get("allow_test_pairing") is not True:
        raise SystemExit("Fixture must explicitly authorize test pairing")
    command = [args.adb, "-s", args.serial]

    def adb(*parts, data=None, timeout=30):
        result = subprocess.run(command + list(parts), input=data, capture_output=True, timeout=timeout, check=False)
        if result.returncode:
            # Credentials are supplied only over stdin; never include command output on failure.
            raise RuntimeError("ADB operation failed; verify selected device, installed debug APKs, and run-as availability")
        return result.stdout

    if adb("get-state").strip() != b"device":
        raise SystemExit("Selected Android device is not ready")
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
    for name in ("remote-surface-evidence.json", "remote-surface-pairing.json", "remote-surface-fixture.json"):
        adb("shell", "run-as", PACKAGE, "rm", "-f", "files/" + name)
    try:
        # Static remote shell program, with fixture bytes over stdin rather than process arguments.
        adb("shell", "run-as", PACKAGE, "sh", "-c", "'umask 077; cat > files/remote-surface-fixture.json'", data=payload)
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "remoteSurfaceAcceptance", "true",
                     "-e", "class", TEST, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=240)
        if b"FAILURES!!!" in output or b"OK (1 test)" not in output:
            raise RuntimeError("Surface acceptance did not pass; inspect the device test result, not the private fixture")
        evidence = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/remote-surface-evidence.json"))
        if not (evidence.get("passed") is True and evidence.get("frames_observed", 0) >= 40
                and evidence.get("varied_frames", 0) >= 30 and evidence.get("distinct_sample_hashes", 0) >= 2
                and evidence.get("width") == fixture.get("width") and evidence.get("height") == fixture.get("height")
                and all(evidence.get(key) is True for key in ("background_stopped", "foreground_resumed", "close_stopped"))):
            raise RuntimeError("Device evidence is incomplete")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print("Real decoded Surface acceptance passed; redacted evidence saved")
    finally:
        adb("shell", "run-as", PACKAGE, "rm", "-f", "files/remote-surface-fixture.json")


if __name__ == "__main__":
    main()
