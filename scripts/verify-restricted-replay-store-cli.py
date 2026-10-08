#!/usr/bin/env python3
"""Serial direct-JVM local store checks. No network, signatures, invented fixtures or power-loss claim."""
from pathlib import Path
import hashlib
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
PACKET = ROOT / 'fixtures/restricted-replay'
CP = (ROOT / 'app/target/runtime-classpath.txt').read_text().strip()
CMD = ['java', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-cp', CP, 'lab.Main', 'restricted-replay-store']
COUNT = 0


def require(ok, detail):
    if not ok:
        raise ValueError(detail)


def check(name, args, expected=0, fragments=(), cwd=ROOT):
    global COUNT
    started = time.monotonic()
    try:
        result = subprocess.run(CMD + list(map(str, args)), cwd=cwd, capture_output=True, text=True, timeout=30)
    except subprocess.TimeoutExpired as error:
        print('TIMEOUT:', name, 'elapsed', round(time.monotonic() - started, 3), flush=True)
        for label, partial in (('stdout', error.stdout), ('stderr', error.stderr)):
            if isinstance(partial, bytes):
                partial = partial.decode('utf-8', errors='replace')
            print(label + ':', partial or '<empty>', flush=True)
        raise
    require(result.returncode == expected, name + ': ' + result.stdout + result.stderr)
    for fragment in fragments:
        require(fragment in result.stdout, name + ': missing ' + fragment)
    COUNT += 1
    print('PASS:', name, 'exit', expected, 'elapsed', round(time.monotonic() - started, 3), flush=True)
    return dict(field.split('=', 1) for line in result.stdout.splitlines()
                for field in line.split('\t') if '=' in field)


before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in PACKET.iterdir() if p.is_file()}
with tempfile.TemporaryDirectory(prefix='durable-replay-cli-') as temporary:
    root = Path(temporary)
    directory = root / 'store'
    checkpoint = root / 'checkpoint.trace.tsv'
    batch = root / 'batch.trace.tsv'
    rows = (PACKET / 'value-conservation.trace.tsv').read_text().splitlines()
    checkpoint.write_text(rows[0] + '\n')
    batch.write_text('\n'.join(rows[:2]) + '\n')
    genesis = check('initialize checkpoint only', ['init', directory, checkpoint], fragments=(
        'revision=0', 'feesSinceCheckpoint=0', 'localPersistence=true', 'fullLedger=false',
        'hardwarePowerLossGuarantee=false'))
    check('refuse reinitialize nonempty store', ['init', directory, checkpoint], 5)
    check('inspect portable explicit path', ['inspect', directory], cwd=root, fragments=('revision=0',))
    check('atomic accepted-rejected batch publishes nothing', ['commit', directory, '0', genesis['head'],
          PACKET / 'value-conservation.trace.tsv'], 1)
    unchanged = check('reopen after atomic rejection', ['inspect', directory], fragments=('revision=0', 'feesSinceCheckpoint=0'))
    require(unchanged['head'] == genesis['head'], 'atomic rejection advanced head')
    applied = check('commit accepted setup', ['commit', directory, '0', genesis['head'], batch], fragments=('revision=1', 'feesSinceCheckpoint=167041'))
    check('uncertain retry rejected by old fence', ['commit', directory, '0', genesis['head'], batch], 2)
    check('repeated transaction cannot charge twice', ['commit', directory, '1', applied['head'], batch], 1)
    reopened = check('reopen preserves exact state and undo', ['inspect', directory])
    for field in ('head', 'revision', 'undo', 'state', 'feesSinceCheckpoint', 'utxoCbor'):
        require(reopened[field] == applied[field], 'reopen changed ' + field)
    check('wrong transition rejected', ['rollback', directory, '1', applied['head'], '0' * 64], 2)
    restored = check('checked rollback', ['rollback', directory, '1', applied['head'], applied['undo']], fragments=('revision=2', 'feesSinceCheckpoint=0'))
    require(restored['state'] == genesis['state'] and restored['utxoCbor'] == genesis['utxoCbor'], 'rollback did not restore exact content')
    check('ABA stale head rejected', ['commit', directory, '0', genesis['head'], batch], 2)
    reapplied = check('explicit reapply at newer revision', ['commit', directory, '2', restored['head'], batch], fragments=('revision=3', 'feesSinceCheckpoint=167041'))
    require(reapplied['undo'] != applied['undo'], 'transition did not bind new revision')
    check('noncanonical revision rejected', ['commit', directory, '03', reapplied['head'], batch], 2)
    check('required fence cannot be omitted', ['commit', directory, batch], 2)
    check('init does not implicitly apply trace events', ['init', root / 'other', PACKET / 'value-conservation.trace.tsv'], 2)
    (directory / 'head').write_bytes(b'corrupt')
    check('corrupt selected head never falls back', ['inspect', directory], 5)
check('help', ['--help'])
check('unknown option', ['--unknown'], 2)
after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in PACKET.iterdir() if p.is_file()}
require(before == after, 'store CLI changed fixture data')
print(COUNT, 'direct JVM durable replay CLI checks passed; local process/filesystem semantics only')
