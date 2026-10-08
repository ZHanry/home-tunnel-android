"""Verify the actual universal APK, its unchanged signing identity and both native engines."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('--unsigned-debug', action='store_true')
args = parser.parse_args()
sdk = Path(os.environ['ANDROID_HOME'])
tools = sdk/'build-tools/35.0.0'
version = json.loads((ROOT/'compatibility.json').read_text())['version']
identity = (ROOT/'release-signing-cert.sha256').read_text().strip()
revision = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
core = subprocess.check_output(['git','-C','homedesk-core','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
signature = subprocess.check_output([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(args.apk)],text=True)
match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',signature)
if not match or (not args.unsigned_debug and match[1] != identity):
    raise SystemExit('APK signing identity differs from the existing release certificate')
badging = subprocess.check_output([str(tools/'aapt'),'dump','badging',str(args.apk)],text=True)
if not re.search(r"package: name='io.github.zhanry.hometunnel'.*versionCode='11000001'.*versionName='"+re.escape(version)+r"'",badging):
    raise SystemExit('Application ID, version or monotonic versionCode mismatch')
if "sdkVersion:'26'" not in badging or "targetSdkVersion:'35'" not in badging:
    raise SystemExit('Android API level mismatch')
with zipfile.ZipFile(args.apk) as archive:
    engines = {}
    abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
    if abis != {'arm64-v8a','x86_64'}:
        raise SystemExit('Universal APK must contain exactly arm64-v8a and x86_64; found: ' + ', '.join(sorted(abis)))
    for abi in sorted(abis):
        for library in ('librustdesk.so','libflutter.so','libc++_shared.so'):
            name = f'lib/{abi}/{library}'
            payload = archive.read(name)
            if len(payload)<4096 or payload[:4]!=b'\x7fELF':
                raise SystemExit('Missing or invalid native engine: '+name)
            machine = int.from_bytes(payload[18:20],'little')
            if machine != (183 if abi=='arm64-v8a' else 62):
                raise SystemExit('Wrong ELF architecture: '+name)
            engines[name]=hashlib.sha256(payload).hexdigest()
record={'revision':revision,'core_revision':core,'version':version,'application_id':'io.github.zhanry.hometunnel',
        'version_code':11000001,'certificate_sha256':match[1],'variant':'debug' if args.unsigned_debug else 'release',
        'apk':args.apk.name,'sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'native_sha256':engines,
        'policy':'require_direct','acceptance':'APK packaging/signature only; real-device and NAT/media acceptance pending'}
output=ROOT/'material-input'
output.mkdir(exist_ok=True)
(output/('android-debug.json' if args.unsigned_debug else 'android-build.json')).write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record))
