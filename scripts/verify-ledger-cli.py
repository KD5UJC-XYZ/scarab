#!/usr/bin/env python3
"""Offline direct-JVM ledger-demo admission regression; run after app/runtimeClasspathFile."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CLASSPATH = (ROOT / 'app/target/runtime-classpath.txt').read_text()
COMMAND = ['java', '-cp', CLASSPATH, 'lab.Main', 'ledger-demo']


def check(name, cwd, wanted):
    result = subprocess.run(COMMAND, cwd=cwd, capture_output=True, text=True, timeout=30)
    assert result.returncode == wanted, (name, result.returncode, result.stdout, result.stderr)
    if wanted == 0:
        assert result.stdout.count('PASS\t') == 4
        assert 'PredicateSatisfied is not transaction validity' in result.stdout
        assert 'no fresh target ledger oracle' in result.stdout
    print(f'PASS: {name}, exit={wanted}')


check('pinned four upstream cases', ROOT, 0)
with tempfile.TemporaryDirectory(prefix='ledger-cli-') as temporary:
    work = Path(temporary)
    fixture = work / 'fixtures/ledger/ledger-vectors.tsv'
    fixture.parent.mkdir(parents=True)
    source = (ROOT / 'fixtures/ledger/ledger-vectors.tsv').read_bytes()
    fixture.write_bytes(source)
    check('same immutable projection, independent working directory', work, 0)
    fixture.write_bytes(source + b'\n')
    check('changed projection rejected', work, 2)
    # An adjacent attacker-edited manifest cannot authorize changed bytes.
    (fixture.parent / 'SHA256SUMS').write_text('0' * 64 + '  ledger-vectors.tsv\n')
    check('editable manifest cannot replace immutable pin', work, 2)
    fixture.write_bytes(b'x' * 1048577)
    check('oversize rejected before parsing', work, 2)
    fixture.unlink()
    check('missing projection rejected', work, 2)
print('6 direct-JVM ledger CLI checks passed')
