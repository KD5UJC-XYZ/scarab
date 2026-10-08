#!/usr/bin/env python3
"""Portable retained fixture admission and offline direct-JVM CLI checks."""
import hashlib
import json
import subprocess
from pathlib import Path
root = Path(__file__).resolve().parent.parent
fixtures = root / 'fixtures/chain-sync'
for fixture in json.loads((fixtures / 'envelope-manifest.json').read_text())['fixtures']:
    literal = (fixtures / (fixture['id'] + '.hex')).read_text().strip()
    assert literal == fixture['hex']
    assert hashlib.sha256(bytes.fromhex(literal)).hexdigest() == fixture['sha256']
manifest = json.loads((fixtures / 'payload-manifest.json').read_text())
for fixture in manifest['files'] + manifest['licenses']:
    data = (fixtures / fixture['localPath']).read_bytes()
    assert len(data) == fixture['bytes']
    assert hashlib.sha256(data).hexdigest() == fixture['sha256']
    assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == fixture['gitBlobSha1']
cp = (root / 'app/target/runtime-classpath.txt').read_text()
results = []
for name, args, expected in [
    ('selftest', ['chain-sync-selftest'], 0),
    ('extra-args', ['chain-sync-selftest', 'example.com'], 2),
    ('endpoint-option', ['chain-sync-selftest', '--host', '127.0.0.1'], 2),
]:
    p = subprocess.run(['java', '-cp', cp, 'lab.Main'] + args, cwd=root,
                       capture_output=True, text=True, timeout=30)
    (root / f'docs/cli-chain-sync-{name}.log').write_text(p.stdout + p.stderr + f'\nexit={p.returncode}; expected={expected}\n')
    assert p.returncode == expected, (name, p.returncode, p.stdout, p.stderr)
    if expected == 0:
        for flag in ['transportExchanges=0', 'referenceRuntimeChecked=false',
                     'cardanoHeaderValidated=false', 'cardanoBlockValidated=false',
                     'ledgerRollbackImplemented=false', 'livePeerChecked=false', 'finalState=Done']:
            assert flag in p.stdout
    results.append({'case': name, 'exit': p.returncode, 'expected': expected})
(root / 'docs/cli-chain-sync-verification.json').write_text(json.dumps(results, indent=2) + '\n')
print('13 immutable ChainSync fixtures and 3 direct-JVM CLI cases passed')
