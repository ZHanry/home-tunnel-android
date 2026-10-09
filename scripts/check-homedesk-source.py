"""Check shared-source ownership, contract bytes, application identity and direct-only release metadata."""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT=Path(__file__).resolve().parents[1]
metadata=json.loads((ROOT/'compatibility.json').read_text())
core=ROOT/'homedesk-core'
locked=json.loads((ROOT/'homedesk-core.lock.json').read_text())
revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=core,text=True).strip()
assert locked['repository']=='ZHanry/home-tunnel-client' and revision==locked['revision']
assert metadata['version']=='13.0.0' and metadata['contract_ref']=='api-v2.0.0'
assert metadata['remote_policy']=='require_direct' and metadata['relay_enabled'] is False
assert json.loads((core/'compatibility.json').read_text())['version']==metadata['version']
for name in ('home-tunnel.v1.json','openapi.v1.json','api.schema.json','home-tunnel.v2.json','openapi.v2.json','api.v2.schema.json','nestlink-auth.v2-vectors.json','lock.json','nestlink-browser.v1.json','browser.lock.json'):
    assert (ROOT/'contracts'/name).read_bytes()==(core/'contracts'/name).read_bytes(), 'Shared contract mismatch: '+name
assert (ROOT/'release-signing-cert.sha256').read_text().strip()=='d7779e338be1039acee6dda9a43417cbf2baf4b0c9995578d9708501e95af702'
android=core/'client/flutter/android'
assert hashlib.sha256((ROOT/locked['gradle_wrapper_source']).read_bytes()).hexdigest()==locked['gradle_wrapper_sha256']
assert 'gradle-8.9-bin.zip' in (android/'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'applicationId "io.github.zhanry.hometunnel"' in (android/'app/build.gradle').read_text()
assert 'signingConfig signingConfigs.release' in (android/'app/build.gradle').read_text()
assert 'version: 13.0.0+13000000' in (core/'client/flutter/pubspec.yaml').read_text()
print('Exact shared source, unchanged release identity, direct-only metadata and API bytes verified')
