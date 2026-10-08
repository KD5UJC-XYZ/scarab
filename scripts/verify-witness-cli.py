#!/usr/bin/env python3
"""Offline direct-JVM witness harness admission regressions; no native verifier or secrets."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CLASSPATH = (ROOT / 'app/target/runtime-classpath.txt').read_text()
COMMAND = ['java', '-cp', CLASSPATH, 'lab.Main', 'witness-demo']
FILES = ['vectors.tsv', 'status.tsv', 'ledger-witnesses.tsv']


def check(name, cwd, wanted):
    result = subprocess.run(COMMAND, cwd=cwd, capture_output=True, text=True, timeout=120)
    assert result.returncode == wanted, (name, result.returncode, result.stdout, result.stderr)
    if wanted == 0:
        assert '2219/2219' in result.stdout
        assert '8/8 genuine ledger witnesses' in result.stdout
        assert 'SignatureVerified is not transaction validity' in result.stdout
        assert 'not exact Cardano-fork binary conformance' in result.stdout
    print(f'PASS: {name}, exit={wanted}')


check('pinned public-only witness corpus', ROOT, 0)
with tempfile.TemporaryDirectory(prefix='witness-cli-') as temporary:
    work = Path(temporary)
    directory = work / 'fixtures/witness'
    directory.mkdir(parents=True)
    original = {name: (ROOT / 'fixtures/witness' / name).read_bytes() for name in FILES}
    for name, data in original.items():
        (directory / name).write_bytes(data)
    check('immutable corpus in independent directory', work, 0)
    for name in FILES:
        path = directory / name
        path.write_bytes(original[name] + b'\n')
        check(f'modified {name} rejected', work, 2)
        (directory / 'SHA256SUMS').write_text('0' * 64 + '  ' + name + '\n')
        check(f'editable manifest cannot authorize {name}', work, 2)
        path.write_bytes(original[name])
    (directory / FILES[0]).write_bytes(b'x' * (8 * 1024 * 1024 + 1))
    check('oversize rejected before parsing', work, 2)
    (directory / FILES[0]).unlink()
    check('missing projection rejected', work, 2)
print('10 direct-JVM witness CLI checks passed')
