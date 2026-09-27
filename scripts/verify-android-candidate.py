"""Verify already-built production APKs/AAB and retain exact SDK and signing evidence."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "x86_64")
NOTICES = ("PROJECT-LICENSE", "WEBRTC-LICENSE.md", "NDK-NOTICE", "NDK-NOTICE.toolchain", "native-notices.json")
APPLICATION = "io.github.zhanry.hometunnel"


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / (name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True, encoding="utf-8", stderr=subprocess.STDOUT).strip()


def apk_certificate(output):
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in output:
        raise SystemExit("Every APK must have a verified v2 signature")
    certs = re.findall(r"^(?:Signer #1|V[0-9.]+ Signer):? certificate SHA-256 digest: ([0-9a-fA-F:]+)$", output, re.M)
    # apksigner uses 'Signer #1 certificate', while some versions add a colon.
    certs += re.findall(r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+)$", output, re.M)
    values = {c.replace(":", "").lower() for c in certs}
    if re.search(r"^Signer #(?!1\b)\d+", output, re.M) or len(values) != 1 or not re.fullmatch(r"[0-9a-f]{64}", next(iter(values), "")):
        raise SystemExit("APK must carry one unambiguous persistent signing identity")
    return next(iter(values))


def jar_certificate(output):
    values = {c.replace(":", "").lower() for c in re.findall(r"^\s*SHA256:\s*([0-9A-Fa-f:]+)\s*$", output, re.M)}
    if len(values) != 1 or not re.fullmatch(r"[0-9a-f]{64}", next(iter(values), "")):
        raise SystemExit("AAB signing identity is missing or ambiguous")
    return next(iter(values))


def validate_manifest(facts, version, code, expected_cert):
    if facts.get("application_id") != APPLICATION or facts.get("version_name") != version or facts.get("version_code") != code:
        raise SystemExit("Installable package identity/version differs from the fixed source")
    if facts.get("min_sdk") != 26 or type(facts.get("target_sdk")) is not int or facts["target_sdk"] < 35:
        raise SystemExit("Installable package does not target the supported Android versions")
    if facts.get("debuggable") is not False or facts.get("certificate_sha256") != expected_cert:
        raise SystemExit("Production packages must be non-debuggable and retain the existing signing certificate")


def tool(directory, stem):
    suffix = ".bat" if os.name == "nt" else ""
    matches = list(directory.glob(f"*/{stem}{suffix}"))
    def order(path):
        return tuple(int(part) for part in re.findall(r"\d+", path.parent.name))
    if not matches:
        raise SystemExit(f"Required Android package tool is missing: {stem}")
    return max(matches, key=order)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--controller-root", type=Path, default=ROOT / ".cache/remote-controller")
    parser.add_argument("--sdk-subjects", type=Path, default=ROOT / ".cache/sdk-candidate-download/subjects")
    parser.add_argument("--bundletool", type=Path, required=True)
    args = parser.parse_args()
    directory = args.directory.resolve()
    if (directory / "android-release-evidence.json").exists():
        raise SystemExit("Package verification evidence cannot be overwritten")
    revision = os.environ.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", revision) or run("git", "rev-parse", "HEAD") != revision or run("git", "status", "--porcelain"):
        raise SystemExit("Candidate verification requires the exact clean build commit")
    properties = (ROOT / "gradle.properties").read_text()
    version = re.search(r"^HOME_TUNNEL_VERSION_NAME=(.+)$", properties, re.M)[1]
    code = int(re.search(r"^HOME_TUNNEL_VERSION_CODE=(\d+)$", properties, re.M)[1])
    expected_cert = (ROOT / "release-signing-cert.sha256").read_text().strip().lower()
    lock = json.loads((ROOT / "native/controller-sdk-candidate.lock.json").read_text())
    source = json.loads((ROOT / "native/remote-source.lock.json").read_text())
    if lock.get("source_revision") != source.get("source_revision") or lock.get("matches_android_snapshot") is not True:
        raise SystemExit("Candidate and native source locks disagree")
    packages = module("verify-remote-packages")
    native_verifier = module("verify-remote-native")
    apksigner = tool(args.sdk_root / "build-tools", "apksigner")
    apkanalyzer = shutil.which("apkanalyzer.bat" if os.name == "nt" else "apkanalyzer")
    if not apkanalyzer:
        matches = list((args.sdk_root / "cmdline-tools").glob("*/bin/apkanalyzer*"))
        apkanalyzer = next((p for p in matches if p.name == ("apkanalyzer.bat" if os.name == "nt" else "apkanalyzer")), None)
    if not apkanalyzer:
        raise SystemExit("apkanalyzer is required")
    if digest(args.bundletool) != "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29":
        raise SystemExit("bundletool differs from pinned 1.18.3")
    run("java", "-jar", args.bundletool, "validate", f"--bundle={directory / f'HomeTunnel-Android-{version}.aab'}")
    sdk_index = json.loads((args.sdk_subjects / "android-sdk-candidate.json").read_text())
    if digest(args.sdk_subjects / "android-sdk-candidate.json") != lock["index_sha256"]:
        raise SystemExit("Restored SDK index differs from the verified candidate lock")
    records = {}
    natives = {}
    builds = {}
    for abi in ABIS:
        sdk = args.controller_root / abi
        native_verifier.verify(sdk, [abi], production=True)
        native = json.loads((sdk / abi / "remote-artifact.json").read_text())
        build = json.loads((sdk / "android-webrtc-build.json").read_text())
        if native["library_sha256"] != lock["abis"][abi]["library_sha256"] or digest(sdk / "android-webrtc-build.json") != lock["abis"][abi]["controller_manifest_sha256"]:
            raise SystemExit("Package SDK differs from the imported candidate")
        apk = directory / f"HomeTunnel-Android-{version}-{abi}.apk"
        signature = run(apksigner, "verify", "--verbose", "--print-certs", apk)
        facts = {"name": apk.name, "sha256": digest(apk), "bytes": apk.stat().st_size, "abi": abi,
                 "certificate_sha256": apk_certificate(signature)}
        for key, field, convert in (("application_id", "application-id", str), ("version_name", "version-name", str),
                                    ("version_code", "version-code", int), ("min_sdk", "min-sdk", int), ("target_sdk", "target-sdk", int)):
            facts[key] = convert(run(apkanalyzer, "manifest", field, apk))
        facts["debuggable"] = run(apkanalyzer, "manifest", "debuggable", apk) != "false"
        validate_manifest(facts, version, code, expected_cert)
        facts["libraries"] = packages.package_libraries(apk, f"lib/{abi}/", native["library_sha256"], abi)
        facts["notices"] = packages.package_notices(apk, "assets/licenses/", native, build)
        with zipfile.ZipFile(apk) as archive:
            if any("libhometunnel_agent" in n or "remote_acceptance" in n for n in archive.namelist()):
                raise SystemExit("Production package contains an Agent or emulator acceptance fixture")
            for name in NOTICES:
                (directory / f"android-native-{abi}-{name}").write_bytes(archive.read("assets/licenses/" + name))
        (directory / f"android-signature-{abi}.txt").write_text(signature + "\n", encoding="utf-8")
        shutil.copyfile(sdk / abi / "remote-artifact.json", directory / f"android-native-evidence-{abi}.json")
        shutil.copyfile(sdk / "android-webrtc-build.json", directory / f"android-controller-build-{abi}.json")
        provenance = args.sdk_subjects / sdk_index["abis"][abi]["provenance"]
        if digest(provenance) != lock["abis"][abi]["provenance_sha256"]:
            raise SystemExit("SDK provenance differs from its verified import")
        shutil.copyfile(provenance, directory / f"android-controller-sdk-provenance-{abi}.json")
        records[abi], natives[abi], builds[abi] = facts, native, build
    aab = directory / f"HomeTunnel-Android-{version}.aab"
    signature = run("jarsigner", "-verify", "-verbose", "-certs", aab)
    if "jar verified." not in signature:
        raise SystemExit("AAB JAR signature verification failed")
    facts = {"name": aab.name, "sha256": digest(aab), "bytes": aab.stat().st_size, "abi": "arm64-v8a",
             "certificate_sha256": jar_certificate(run("keytool", "-printcert", "-jarfile", aab))}
    for key, xpath, convert in (("application_id", "/manifest/@package", str), ("version_name", "/manifest/@android:versionName", str),
                               ("version_code", "/manifest/@android:versionCode", int), ("min_sdk", "/manifest/uses-sdk/@android:minSdkVersion", int),
                               ("target_sdk", "/manifest/uses-sdk/@android:targetSdkVersion", int)):
        facts[key] = convert(run("java", "-jar", args.bundletool, "dump", "manifest", f"--bundle={aab}", f"--xpath={xpath}"))
    # Require a parsed application element; missing android:debuggable defaults to false.
    from xml.etree import ElementTree
    xml = run("java", "-jar", args.bundletool, "dump", "manifest", f"--bundle={aab}")
    application = ElementTree.fromstring(xml).find("application")
    if application is None:
        raise SystemExit("AAB manifest has no application")
    facts["debuggable"] = application.get("{http://schemas.android.com/apk/res/android}debuggable", "false") != "false"
    validate_manifest(facts, version, code, expected_cert)
    facts["libraries"] = packages.package_libraries(aab, "base/lib/arm64-v8a/", natives["arm64-v8a"]["library_sha256"])
    facts["notices"] = packages.package_notices(aab, "base/assets/licenses/", natives["arm64-v8a"], builds["arm64-v8a"])
    if any(facts[key] != records["arm64-v8a"][key] for key in ("libraries", "notices")):
        raise SystemExit("The arm64 APK and AAB differ in their native libraries or notices")
    records["aab"] = facts
    shutil.copyfile(ROOT / "native/controller-sdk-candidate.lock.json", directory / "android-controller-sdk-candidate.lock.json")
    shutil.copyfile(ROOT / "native/remote-source.lock.json", directory / "android-native-source.lock.json")
    evidence = {"schema_version": 2, "status": "passed", "verification_stage": "package-verification",
                "repository_revision": revision, "sdk_revision": source["source_revision"], "version_name": version,
                "version_code": code, "application_id": APPLICATION, "signing_certificate_sha256": expected_cert,
                "abis": list(ABIS), "packages": records, "runtime_acceptance": "pending",
                "physical_arm64_install": "not_run_in_github_actions"}
    (directory / "android-release-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print("Verified production identities, persistent signatures, both native ABIs, 16 KiB pages and notices; runtime acceptance remains pending")


if __name__ == "__main__":
    main()
