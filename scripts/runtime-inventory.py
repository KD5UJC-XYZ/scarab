#!/usr/bin/env python3
"""Audit resolved application runtime only. First: ./scripts/sbtw app/runtimeClasspathFile."""
import hashlib, json, os, pathlib, zipfile
root = pathlib.Path(__file__).resolve().parent.parent
entries = (root / 'app/target/runtime-classpath.txt').read_text().split(os.pathsep)
records = []
weave_pin = '256048db6904c00832ab6045c624c69844d7617f719e3cd446691257aab8ffcb'
weave_seen = False
bc_seen = False
bc_pin = '986b0fb92ec10e0c66b43e036ce0077e6150cfaecd1db9fb92b56672e157afe5'
for entry in entries:
    path = pathlib.Path(entry)
    if path.suffix != '.jar':
        continue
    with zipfile.ZipFile(path) as jar:
        native = [name for name in jar.namelist() if name.lower().endswith(('.so', '.dll', '.dylib', '.jnilib'))]
    if native or 'blst-java' in path.name or 'scalus-secp256k1-jni' in path.name:
        raise SystemExit(f'Native runtime dependency rejected: {path.name}: {native}')
    relative = str(path).split('/maven2/', 1)[-1]
    if path.name.startswith('curve25519-elisabeth-'):
        if path.name != 'curve25519-elisabeth-0.1.3.jar' or hashlib.sha256(path.read_bytes()).hexdigest() != weave_pin:
            raise SystemExit('Weavechain runtime artifact differs from inspected immutable pin')
        weave_seen = True
    if path.name.startswith('bcprov-'):
        if path.name != 'bcprov-jdk18on-1.85.2.jar' or hashlib.sha256(path.read_bytes()).hexdigest() != bc_pin:
            raise SystemExit('Bouncy Castle runtime artifact differs from inspected immutable pin')
        bc_seen = True
    records.append({'mavenPath': relative, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'bundledNativeFiles': native})
if not weave_seen:
    raise SystemExit('Pinned Weavechain runtime artifact absent')
if not bc_seen:
    raise SystemExit('Pinned Bouncy Castle runtime artifact absent')
if not records:
    raise SystemExit('Empty runtime inventory')
(root / 'docs/runtime-dependencies.json').write_text(json.dumps({'scope': 'app / Runtime / fullClasspath jar files', 'artifacts': records}, indent=2) + '\n')
print(f'Audited {len(records)} runtime jars: no excluded native artifacts or bundled native library files')
