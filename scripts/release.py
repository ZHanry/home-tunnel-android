"""Build, verify and publish a component; preserve sealed engineering evidence in Releases."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
PROJECT = json.loads((ROOT / "compatibility.json").read_text())
COMPONENT = PROJECT["component"]
REPO = os.environ["GITHUB_REPOSITORY"]
SHA = os.environ["GITHUB_SHA"]
TAG = os.environ["GITHUB_REF_NAME"]

def run(*args, capture=False):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE if capture else None).stdout

def api(endpoint):
    return json.loads(run("gh", "api", endpoint, capture=True))

def local_version():
    if COMPONENT == "server":
        return json.loads((ROOT / "control-center/package.json").read_text())["version"]
    if COMPONENT == "client":
        return re.search(r'const Version = "([^"]+)"', (ROOT / "internal/model/model.go").read_text()).group(1)
    return re.search(r'^HOME_TUNNEL_VERSION_NAME=(.+)$', (ROOT / "gradle.properties").read_text(), re.M).group(1)

def validate_release_tag(tag, source_version, stage):
    if stage not in ("internal-testing", "public-release"):
        raise SystemExit("Unknown release stage; set compatibility.json explicitly")
    match = re.fullmatch(r"v(\d+\.\d+\.\d+)(?:-rc\.([1-9]\d*))?", tag)
    if not match:
        raise SystemExit("Release tags must be vX.Y.Z or vX.Y.Z-rc.N")
    version, candidate = match.groups()
    if tag.removeprefix("v") != source_version:
        raise SystemExit("Tag does not match this component's source version")
    if stage == "internal-testing" and candidate is None:
        raise SystemExit("Internal testing publishes prereleases only; use vX.Y.Z-rc.N")
    return version, candidate


def release_version():
    """Full identity shown inside APK/AAB and in asset filenames, including RC suffix."""
    validate_release_tag(TAG, local_version(), PROJECT.get("stage"))
    return TAG.removeprefix("v")


def validate_android_version_code(code, previous):
    if type(code) is not int or not 1 <= code <= 2_100_000_000:
        raise SystemExit("Android versionCode must be a positive integer <= 2100000000")
    if any(type(value) is not int or not 1 <= value <= 2_100_000_000 for value in previous):
        raise SystemExit("Published Android version evidence is invalid")
    if previous and code <= max(previous):
        raise SystemExit("Every new Android RC and stable release must have a larger versionCode than all published packages")


def validate_frozen_contract(project, locks):
    """Candidates can use a draft; publication requires one frozen server source."""
    ref = project.get("contract_ref")
    number = r"(?:0|[1-9][0-9]*)"
    if (not isinstance(ref, str) or
            re.fullmatch(rf"api-v{number}\.{number}\.{number}(?:-rc\.[1-9][0-9]*)?", ref) is None or
            project.get("contract_status") != "frozen" or project.get("frozen_tag") != ref):
        raise SystemExit("Publication requires a frozen contract; proposed API contracts are candidate-only")
    if len(locks) != 2:
        raise SystemExit("Publication requires both API and remote contract locks")
    revisions = set()
    for lock in locks:
        if (lock.get("repository") != "ZHanry/home-tunnel-server" or lock.get("contract_status") != "frozen" or
                lock.get("frozen_tag") != ref or lock.get("published_contract_ref") != ref or
                lock.get("ref", ref) != ref or lock.get("source_tree_dirty") is not False or
                re.fullmatch(r"[0-9a-f]{40}", str(lock.get("source_revision", ""))) is None):
            raise SystemExit("Publication contract locks must pin the same frozen, clean server source")
        revisions.add(lock["source_revision"])
    if len(revisions) != 1:
        raise SystemExit("API and remote contract locks refer to different server commits")
    return ref, revisions.pop()


def check_frozen_contract():
    ref, revision = validate_frozen_contract(PROJECT, [
        json.loads((ROOT / "contracts" / name).read_text(encoding="utf-8"))
        for name in ("lock.json", "remote.lock.json")
    ])
    repository = "repos/ZHanry/home-tunnel-server"
    target = api(f"{repository}/git/ref/tags/{ref}").get("object", {})
    visited = set()
    for _ in range(8):
        if target.get("type") == "commit":
            if target.get("sha") != revision:
                raise SystemExit("Frozen contract tag does not identify the locked server commit")
            return
        digest = target.get("sha")
        if (target.get("type") != "tag" or not isinstance(digest, str) or
                re.fullmatch(r"[0-9a-f]{40}", digest) is None or digest in visited):
            break
        visited.add(digest)
        target = api(f"{repository}/git/tags/{digest}").get("object", {})
    raise SystemExit("Frozen contract tag could not be resolved to the locked server commit")


def check_android_upgrade_sequence():
    if COMPONENT != "android":
        return
    code = int(re.search(r'^HOME_TUNNEL_VERSION_CODE=(\d+)$', (ROOT / "gradle.properties").read_text(), re.M).group(1))
    previous = [7_000_000]
    page = 1
    while True:
        releases = api(f"repos/{REPO}/releases?per_page=100&page={page}")
        for release in releases:
            if release.get("draft"):
                continue
            if release["tag_name"] == TAG:
                raise SystemExit("This Android release is already public; published artifacts cannot be rebuilt or replaced")
            evidence = next((asset for asset in release.get("assets", []) if asset["name"] == "android-release-evidence.json"), None)
            # Pre-7 releases had no durable evidence; the known 7.0 signer baseline bounds them.
            if evidence is None:
                if re.fullmatch(r"v(?:[0-6])\.\d+\.\d+(?:-rc\.\d+)?", release["tag_name"]):
                    continue
                raise SystemExit("Published Android release lacks versionCode evidence")
            with tempfile.TemporaryDirectory(prefix="ht-android-evidence-") as directory:
                run("gh", "release", "download", release["tag_name"], "--repo", REPO,
                    "--pattern", "android-release-evidence.json", "--dir", directory)
                record = json.loads((Path(directory) / "android-release-evidence.json").read_text())
                if record.get("application_id") != "io.github.zhanry.hometunnel":
                    raise SystemExit("Published Android application identity changed")
                previous.append(record.get("version_code"))
        if len(releases) < 100:
            break
        page += 1
    validate_android_version_code(code, previous)

def metadata():
    version, candidate = validate_release_tag(TAG, local_version(), PROJECT.get("stage"))
    check_frozen_contract()
    run("python3", "scripts/check-repository.py")
    check_android_upgrade_sequence()
    run("git", "fetch", "--tags", "origin", "main")
    run("git", "merge-base", "--is-ancestor", SHA, "origin/main")
    checks = api(f"repos/{REPO}/commits/{SHA}/check-runs?per_page=100")["check_runs"]
    gates = [c for c in checks if c["name"] == "Quality Gate" and c.get("app", {}).get("slug") == "github-actions"]
    if not gates or max(gates, key=lambda c:c["id"])["conclusion"] != "success":
        raise SystemExit("The tagged commit must first pass its component CI Quality Gate on main")
    security_runs = api(f"repos/{REPO}/actions/runs?head_sha={SHA}&per_page=100")["workflow_runs"]
    for workflow_name in ("CodeQL", "Secret scan"):
        matching = [r for r in security_runs if r["name"] == workflow_name and r["head_sha"] == SHA]
        if not matching or max(matching, key=lambda r:r["id"])["conclusion"] != "success":
            raise SystemExit(f"The tagged commit must pass {workflow_name} before release")
    rc_tag = TAG
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for key, value in {"version":TAG.removeprefix('v'),"base-version":version,
                           "stable":str(not candidate).lower(),"rc-version":rc_tag.removeprefix('v'),"rc-tag":rc_tag}.items():
            output.write(f"{key}={value}\n")

def required_assets(directory):
    version = release_version() if COMPONENT == "android" else local_version()
    if COMPONENT == "client":
        expected = [f"HomeTunnel-Setup-{version}-x64.exe", f"HomeTunnel-Windows-{version}-x64.zip"]
        expected += [f"home-tunnel-{platform}-{version}-{arch}.tar.gz" for platform in ("linux","macos") for arch in ("amd64","arm64")]
        expected += ["agent-provenance.json"]
    elif COMPONENT == "android":
        from android_release_candidate import ACCEPTANCE, MANIFEST, package_names
        expected = list(package_names(version).values()) + [MANIFEST, ACCEPTANCE, "android-release-evidence.json",
                    "android-native-source.lock.json", "android-controller-sdk-candidate.lock.json",
                    "android-candidate-download.json", "android-acceptance-origin.json"]
        for abi in ("arm64-v8a", "x86_64"):
            expected += [f"android-native-evidence-{abi}.json", f"android-controller-build-{abi}.json",
                         f"android-controller-sdk-provenance-{abi}.json", f"android-signature-{abi}.txt"]
            expected += [f"android-native-{abi}-" + name for name in
                         ("PROJECT-LICENSE", "WEBRTC-LICENSE.md", "NDK-NOTICE", "NDK-NOTICE.toolchain", "native-notices.json")]
    else:
        expected = ["image-control-center.json", "image-traffic-gateway.json", "home-tunnel.v1.json"]
        for name in ("control-center", "traffic-gateway"):
            record = json.loads((directory / f"image-{name}.json").read_text())
            if record["revision"] != SHA or not re.fullmatch(r"sha256:[a-f0-9]{64}", record["digest"]):
                raise SystemExit("Invalid server image identity")
    for name in expected:
        if not (directory/name).is_file() or not (directory/name).stat().st_size:
            raise SystemExit(f"Missing release asset: {name}")
    if COMPONENT == "android":
        verify_controller_evidence(directory, version)
        verify_staged_candidate(directory, version)


def verify_staged_candidate(directory, version):
    """Publish the bytes that the candidate workflow already signed. Do not rebuild them."""
    evidence_path = directory / "android-release-candidate.json"
    lock_path = ROOT / "native/controller-sdk-candidate.lock.json"
    if not evidence_path.is_file() or not lock_path.is_file():
        raise SystemExit("Stable publication requires the sealed candidate and its SDK lock")
    from android_release_candidate import verify_publication
    verify_publication(json.loads(evidence_path.read_text()), directory, SHA, json.loads(lock_path.read_text())["source_revision"], version, json.loads(lock_path.read_text()))


def verify_controller_evidence(directory, version):
    """Recheck both package payloads against the confirmed SDK and source locks."""
    import importlib.util
    from android_release_candidate import digest, read_json, local_file
    spec = importlib.util.spec_from_file_location("android_package_payloads", ROOT / "scripts/verify-remote-packages.py")
    payloads = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(payloads)
    lock = read_json(ROOT / "native/controller-sdk-candidate.lock.json")
    source = read_json(ROOT / "native/remote-source.lock.json")
    for published, original in (("android-controller-sdk-candidate.lock.json", "controller-sdk-candidate.lock.json"),
                                ("android-native-source.lock.json", "remote-source.lock.json")):
        if local_file(directory, published).read_bytes() != (ROOT / "native" / original).read_bytes():
            raise SystemExit("Android release changed its committed native source or SDK lock")
    facts = read_json(local_file(directory, "android-release-evidence.json"))
    certificate = (ROOT / "release-signing-cert.sha256").read_text().strip()
    version_code = int(re.search(r'^HOME_TUNNEL_VERSION_CODE=(\d+)$', (ROOT / "gradle.properties").read_text(), re.M).group(1))
    if facts.get("signing_certificate_sha256") != certificate or facts.get("version_code") != version_code:
        raise SystemExit("Candidate persistent signing identity or versionCode changed")
    for abi in ("arm64-v8a", "x86_64"):
        pinned = lock["abis"][abi]
        build_path = local_file(directory, f"android-controller-build-{abi}.json")
        provenance_path = local_file(directory, f"android-controller-sdk-provenance-{abi}.json")
        if digest(build_path) != pinned["controller_manifest_sha256"] or digest(provenance_path) != pinned["provenance_sha256"]:
            raise SystemExit("Candidate SDK build or provenance changed")
        build, provenance = read_json(build_path), read_json(provenance_path)
        native = read_json(local_file(directory, f"android-native-evidence-{abi}.json"))
        if (any(build.get(k) != source.get(k) for k in ("source_revision", "source_tree_sha256", "source_files")) or
                build.get("source_modified") is not False or build.get("target") != abi or
                build.get("controller_backend_linked") is not True or build.get("device_media_accepted") is not False or
                provenance.get("source_revision") != source["source_revision"] or provenance.get("target") != abi or
                provenance.get("archive_sha256") != pinned["archive_sha256"] or native.get("target") != abi or
                native.get("source_revision") != source["source_revision"] or native.get("available") is not True or
                native.get("controller_manifest_sha256") != pinned["controller_manifest_sha256"] or
                native.get("library_sha256") != pinned["library_sha256"] or native.get("device_media_accepted") is not False):
            raise SystemExit("Candidate library/source identity differs from its imported SDK")
        packages = [(abi, f"HomeTunnel-Android-{version}-{abi}.apk", f"lib/{abi}/", "assets/licenses/")]
        if abi == "arm64-v8a":
            packages.append(("aab", f"HomeTunnel-Android-{version}.aab", "base/lib/arm64-v8a/", "base/assets/licenses/"))
        for key, name, prefix, notices in packages:
            package = local_file(directory, name)
            libraries = payloads.package_libraries(package, prefix, pinned["library_sha256"], abi)
            notice_record = payloads.package_notices(package, notices, native, build)
            checked = facts["packages"][key]
            if (checked.get("libraries") != libraries or checked.get("notices") != notice_record or
                    checked.get("certificate_sha256") != certificate or checked.get("application_id") != "io.github.zhanry.hometunnel" or
                    checked.get("version_code") != version_code or checked.get("version_name") != version or checked.get("debuggable") is not False):
                raise SystemExit("Candidate package verification differs from its original payloads")
            with zipfile.ZipFile(package) as archive:
                for notice in ("PROJECT-LICENSE", "WEBRTC-LICENSE.md", "NDK-NOTICE", "NDK-NOTICE.toolchain", "native-notices.json"):
                    if local_file(directory, f"android-native-{abi}-{notice}").read_bytes() != archive.read(notices + notice):
                        raise SystemExit("Candidate native notices differ from the package")


def seal():
    directory = ROOT / "release"
    required_assets(directory)
    manifest={"component":COMPONENT,"version":local_version(),"release_version":release_version(),"repository":REPO,"revision":SHA,"api_major":1,"rc_tag":TAG}
    (directory/'release-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    if COMPONENT == 'server':
        records = [json.loads((directory/f'image-{name}.json').read_text()) for name in ('control-center','traffic-gateway')]
        lines = ['services:']
        for record in records:
            lines += [f"  {record['name']}:", f"    image: {record['image']}@{record['digest']}"]
        (directory/'compose.release.yaml').write_text('\n'.join(lines)+'\n')
    lines=[]
    for path in sorted(directory.iterdir()):
        if path.is_file() and path.name not in ('SHA256SUMS.txt','SHA256SUMS.txt.sigstore.json'):
            lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")
    (directory/'SHA256SUMS.txt').write_text(''.join(lines), encoding='utf-8')

def verify(directory, rc_tag):
    identity = f"https://github.com/{REPO}/.github/workflows/release.yml@refs/tags/{rc_tag}"
    run("cosign","verify-blob","--bundle",str(directory/'SHA256SUMS.txt.sigstore.json'),
        "--certificate-identity",identity,"--certificate-oidc-issuer","https://token.actions.githubusercontent.com",str(directory/'SHA256SUMS.txt'))
    listed = set()
    for line in (directory/'SHA256SUMS.txt').read_text().splitlines():
        checksum, name = line.split('  ',1)
        if Path(name).name != name or not re.fullmatch(r'[a-f0-9]{64}',checksum):
            raise SystemExit('Invalid checksum manifest path or hash')
        if hashlib.sha256((directory/name).read_bytes()).hexdigest() != checksum:
            raise SystemExit(f'Checksum mismatch: {name}')
        listed.add(name)
    actual={p.name for p in directory.iterdir() if p.is_file()}-{'SHA256SUMS.txt','SHA256SUMS.txt.sigstore.json'}
    if actual != listed:
        raise SystemExit('Unsealed or missing release assets')
    manifest=json.loads((directory/'release-manifest.json').read_text())
    for key,value in {'repository':REPO,'revision':SHA,'version':local_version(),'release_version':release_version(),'component':COMPONENT,'rc_tag':rc_tag}.items():
        if manifest.get(key)!=value: raise SystemExit(f'Release manifest mismatch: {key}')
    required_assets(directory)
    return identity

def public_asset_names(component, version):
    if component == "android":
        return [f"HomeTunnel-Android-{version}-arm64-v8a.apk", f"HomeTunnel-Android-{version}-x86_64.apk"]
    if component == "client":
        return [f"HomeTunnel-Setup-{version}-x64.exe", f"HomeTunnel-Windows-{version}-x64.zip"] + [
            f"home-tunnel-{platform}-{version}-{arch}.tar.gz"
            for platform in ("linux", "macos") for arch in ("amd64", "arm64")]
    return [f"home-tunnel-server-{version}.tar.gz", "compose.release.yaml"]

def publish(stable=False):
    directory=ROOT/'release'
    stable = re.fullmatch(r"v\d+\.\d+\.\d+", TAG) is not None
    identity=verify(directory,TAG)
    if COMPONENT=='server' and stable:
        for name in ('control-center','traffic-gateway'):
            record=json.loads((directory/f'image-{name}.json').read_text())
            reference=f"{record['image']}@{record['digest']}"
            run('cosign','verify',reference,'--certificate-identity',identity,'--certificate-oidc-issuer','https://token.actions.githubusercontent.com',capture=True)
    import shutil
    public = ROOT/'release-public'
    public.mkdir(exist_ok=True)
    # Publish the exact sealed set, including SBOMs, scan results and signatures.
    # Keeping the signed checksum manifest unchanged makes evidence independently verifiable.
    selected=sorted(path.name for path in directory.iterdir() if path.is_file())
    asset_version = release_version() if COMPONENT == "android" else local_version()
    missing=set(public_asset_names(COMPONENT,asset_version))-set(selected)
    if missing: raise SystemExit(f'Missing public deliverables: {missing}')
    for name in selected:
        shutil.copyfile(directory/name,public/name)
    packages=public_asset_names(COMPONENT,asset_version)
    downloads='\n'.join(f'- [{name}](https://github.com/{REPO}/releases/download/{TAG}/{name})' for name in packages)
    checksums=''.join(f"{hashlib.sha256((public/name).read_bytes()).hexdigest()}  {name}\n" for name in packages)
    title=f'Home Tunnel {COMPONENT} {local_version()}' + ('' if stable else f' ({TAG.rsplit("-",1)[1]})')
    notes=ROOT/'release-notes.md'
    summary=(ROOT/'docs/RELEASE_NOTES.md').read_text(encoding='utf-8')
    run_url=f"https://github.com/{REPO}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    notes.write_text(summary + "\n\n## Downloads\n\n" + downloads + "\n\n```text\n" + checksums + "```\n" + f"\n\nSource: `{SHA}`. [Build, verification and signing evidence]({run_url}).\n\n" +
        "Packages and durable verification evidence are covered by SHA256SUMS.txt and its Sigstore bundle.\n",encoding='utf-8')
    created=False
    try:
        run('gh','release','create',TAG,'--repo',REPO,'--verify-tag','--target',SHA,'--draft','--title',title,'--notes-file',str(notes))
        created=True
        run('gh','release','upload',TAG,'--repo',REPO,*[str(p) for p in sorted(public.iterdir()) if p.is_file()])
        flags=['--draft=false','--latest=true'] if stable else ['--draft=false','--prerelease','--latest=false']
        run('gh','release','edit',TAG,'--repo',REPO,*flags)
    except BaseException:
        if created:
            current=json.loads(run('gh','release','view',TAG,'--repo',REPO,'--json','isDraft',capture=True))
            if current['isDraft']: run('gh','release','delete',TAG,'--repo',REPO,'--yes')
        raise

if __name__=='__main__':
    action=sys.argv[1]
    if action=='metadata': metadata()
    elif action=='seal': seal()
    elif action=='rc': publish()
    elif action=='stable': publish(stable=True)
    else: raise SystemExit('Unknown release action')
