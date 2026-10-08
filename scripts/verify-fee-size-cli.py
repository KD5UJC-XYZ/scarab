#!/usr/bin/env python3
"""Direct JVM fee/size demo admission checks; no live network/native oracle."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CP = (ROOT / 'app/target/runtime-classpath.txt').read_text()
CMD = ['java', '-cp', CP, 'lab.Main', 'fee-size-demo']

def require(ok, message):
    if not ok:
        raise ValueError(message)

def check(name, cwd, expected, suffix=()):
    r = subprocess.run(CMD + list(suffix), cwd=cwd, capture_output=True, text=True, timeout=30)
    require(r.returncode == expected, name + ': ' + r.stdout + r.stderr)
    if expected == 0:
        require(r.stdout.count('PASS\t') == 2 and r.stdout.count('fee=Satisfied') == 2 and
                r.stdout.count('size=Satisfied') == 2 and 'balance=ValueNotConserved' in r.stdout and
                'archivedWholeLedgerSuccess=false' in r.stdout and 'not a raw-size oracle' in r.stdout,
                'missing separated predicate report')
    print('PASS: ' + name)

check('two original fee/size predicates and independent balance rejection', ROOT, 0)
check('extra argument rejects', ROOT, 2, ('extra',))
with tempfile.TemporaryDirectory(prefix='fee-size-cli-') as temporary:
    work = Path(temporary)
    fixture = work / 'fixtures/fee-size/fee-size-vectors.tsv'
    fixture.parent.mkdir(parents=True)
    raw = (ROOT / 'fixtures/fee-size/fee-size-vectors.tsv').read_bytes()
    fixture.write_bytes(raw)
    check('same pinned bytes portable cwd', work, 0)
    fixture.write_bytes(raw + b'\n')
    check('modified projection rejects', work, 2)
    (fixture.parent / 'SHA256SUMS').write_text('0' * 64 + '  fee-size-vectors.tsv\n')
    check('editable manifest cannot bypass immutable pin', work, 2)
    fixture.write_bytes(b'x' * 1048577)
    check('oversized projection rejects', work, 2)
    fixture.unlink()
    check('missing projection rejects', work, 2)
print('7 direct-JVM fee/size CLI checks passed')
