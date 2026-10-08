#!/usr/bin/env python3
"""Offline session self-test command admission and independent fixture-peer checks."""
import json
import subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[1]
cp = (root / 'app/target/runtime-classpath.txt').read_text()
results = []
for name, args, expected in [
    ('selftest', ['chain-sync-session-selftest'], 0),
    ('extra-args', ['chain-sync-session-selftest', 'example.com'], 2),
    ('endpoint-option', ['chain-sync-session-selftest', '--host', '127.0.0.1'], 2),
]:
    p = subprocess.run(['java', '-cp', cp, 'lab.Main'] + args, cwd=root,
                       capture_output=True, text=True, timeout=30)
    (root / f'docs/cli-chain-sync-session-{name}.log').write_text(p.stdout + p.stderr + f'\nexit={p.returncode}; expected={expected}\n')
    assert p.returncode == expected, (name, p.returncode, p.stdout, p.stderr)
    if expected == 0:
        for flag in ['clientForkScenarios=2', 'responderBoundaryScenarios=2',
                     'payloadPreservationChecked=true', 'doneConsumedBeforeClose=true',
                     'referenceRuntimeChecked=false', 'cardanoHeaderValidated=false',
                     'cardanoBlockValidated=false', 'ledgerRollbackImplemented=false',
                     'livePeerChecked=false', 'finalState=Done']:
            assert flag in p.stdout, flag
        assert 'transportReads=0' not in p.stdout
        assert 'transportWrites=0' not in p.stdout
    results.append({'case': name, 'exit': p.returncode, 'expected': expected})
(root / 'docs/cli-chain-sync-session-verification.json').write_text(json.dumps(results, indent=2) + '\n')
print('3 direct-JVM ChainSync session CLI cases passed')
