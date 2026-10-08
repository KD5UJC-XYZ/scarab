#!/usr/bin/env python3
"""Portable offline BlockFetch fixture/source/license audit and direct-JVM CLI checks."""
import hashlib
import json
import subprocess
from pathlib import Path
root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'fixtures/network/block-fetch/manifest.json').read_text())
for row in manifest['files']:
    path = root / row['path']
    assert path.resolve().is_relative_to(root.resolve())
    data = path.read_bytes()
    assert len(data) == row['bytes'], path
    assert hashlib.sha256(data).hexdigest() == row['sha256'], path
    if 'wireSha256' in row:
        wire = bytes.fromhex(data.decode().strip())
        assert len(wire) == row['wireBytes'], path
        assert hashlib.sha256(wire).hexdigest() == row['wireSha256'], path
wire = bytes.fromhex((root / 'fixtures/network/block-fetch/conway-block-source-derived.hex').read_text().strip())
assert wire[:2] == bytes.fromhex('8204')
assert wire[2:] == (root / 'fixtures/chain-sync/ntc-block-conway.cbor').read_bytes()
assert hashlib.sha256(wire[7:]).hexdigest() == '0b7c8bdb99cf28f5e73769733abb4d4630013c5cf9368ed3176717841f95d8f3'
cp = (root / 'app/target/runtime-classpath.txt').read_text()
results = []
for name, args, expected in [
    ('selftest', ['block-fetch-selftest'], 0),
    ('extra-args', ['block-fetch-selftest', 'example.com'], 2),
    ('endpoint-option', ['block-fetch-selftest', '--host', '127.0.0.1'], 2),
]:
    p = subprocess.run(['java', '-cp', cp, 'lab.Main'] + args, cwd=root, capture_output=True, text=True, timeout=30)
    (root / f'docs/cli-block-fetch-{name}.log').write_text(p.stdout + p.stderr + f'\nexit={p.returncode}; expected={expected}\n')
    assert p.returncode == expected, (name, p.stdout, p.stderr)
    if expected == 0:
        for flag in ['miniProtocol=3', 'transportExchanges=0', 'transportSessionImplemented=false',
                     'referenceRuntimeChecked=false', 'livePeerChecked=false', 'cardanoHeaderValidated=false',
                     'cardanoBlockValidated=false', 'ledgerValidated=false', 'blockIdentityVerified=false',
                     'originalRawBytesPreserved=true', 'batchCompleteOnlyAfterBatchDone=true']:
            assert flag in p.stdout, flag
    results.append(dict(case=name, exit=p.returncode, expected=expected))
(root / 'docs/cli-block-fetch-verification.json').write_text(json.dumps(results, indent=2) + '\n')
print(f"{len(manifest['files'])} BlockFetch fixture/source/license audits and 3 direct-JVM CLI cases passed")
