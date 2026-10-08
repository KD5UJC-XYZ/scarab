#!/usr/bin/env python3
"""Serial direct-JVM read-only trace audit. Requires resolved application classpath."""
from pathlib import Path
import hashlib
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKET = ROOT / 'fixtures/restricted-replay'
CP = (ROOT / 'app/target/runtime-classpath.txt').read_text().strip()
CMD = ['java', '-cp', CP, 'lab.Main', 'restricted-replay']
COUNT = 0


def require(ok, detail):
    if not ok:
        raise ValueError(detail)


def check(name, args, expected, fragments=(), cwd=ROOT):
    global COUNT
    result = subprocess.run(CMD + list(map(str, args)), cwd=cwd, capture_output=True, text=True, timeout=30)
    require(result.returncode == expected, name + ': ' + result.stdout + result.stderr)
    for fragment in fragments:
        require(fragment in result.stdout, name + ': missing ' + fragment)
    COUNT += 1
    print('PASS:', name, 'exit', expected)


before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in PACKET.iterdir() if p.is_file()}
for name, stage in (('value-conservation', 'balance'), ('missing-vkey', 'coverage')):
    check(name, [PACKET / (name + '.trace.tsv')], 1,
          ('outcome=ProjectionApplied', 'outcome=Rejected:' + stage, 'feesSinceCheckpoint=167041',
           'revision=1', 'fullLedger=false', 'tickExecuted=false', 'persistence=false'))
with tempfile.TemporaryDirectory(prefix='restricted-replay-cli-') as temporary:
    root = Path(temporary)
    target = root / 'trace.tsv'
    original = (PACKET / 'value-conservation.trace.tsv').read_text()
    lines = original.splitlines()
    target.write_text(original)
    check('portable explicit path', [target], 1, cwd=root)
    target.write_text(lines[0] + '\n')
    check('empty trace no-op', [target], 0, ('feesSinceCheckpoint=0', 'revision=0'))
    target.write_text('\n'.join(lines[:2]) + '\n')
    check('accepted setup only', [target], 0, ('feesSinceCheckpoint=167041', 'revision=1'))
    target.write_text('\n'.join([lines[0], lines[2], lines[1]]) + '\n')
    check('reversed dependency sees unresolved input', [target], 1, ('outcome=Rejected:inputs', 'outcome=ProjectionApplied'))
    target.write_text('\n'.join([lines[0], lines[1], lines[1]]) + '\n')
    check('duplicate setup cannot charge twice', [target], 1, ('outcome=Rejected:inputs', 'feesSinceCheckpoint=167041'))
    target.write_text(lines[0] + '\n' + lines[1] + '\ttrue\n')
    check('expected success flags are not runtime inputs', [target], 2)
    target.write_text(lines[0] + '\ntick\t1\n')
    check('tick unsupported at input boundary', [target], 2)
    target.write_text(original.replace('3883681', '3883680'))
    check('slot cannot silently change', [target], 2)
    target.write_text(original.replace('conway-pv9-transfer-projection-v1', 'other-profile'))
    check('explicit profile admission', [target], 2)
    target.write_text('\n'.join([lines[0]] + [lines[1]] * 65) + '\n')
    check('event count bound', [target], 2)
    target.write_bytes(b'x' * (20 * 1048576 + 1))
    check('trace byte bound', [target], 2)
    target.unlink()
    target.symlink_to(PACKET / 'value-conservation.trace.tsv')
    check('trace symlink rejected', [target], 2)
    check('directory rejected', [root], 2)
    check('missing input rejected', [root / 'missing'], 2)
check('help', ['--help'], 0)
check('unknown option', ['--unknown'], 2)
check('no arguments', [], 2)
after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in PACKET.iterdir() if p.is_file()}
require(before == after, 'read-only CLI changed fixture data')
print(COUNT, 'direct JVM restricted replay CLI checks passed')
