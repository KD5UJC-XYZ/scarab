#!/usr/bin/env python3
"""Portable deterministic projector and fail-closed tamper tests; no native/network IO."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/project-fee-size-fixtures.py'

def require(ok, message):
    if not ok:
        raise ValueError(message)

def run(script, cwd, expected):
    r = subprocess.run([sys.executable, str(script), '--check'], cwd=cwd,
                       capture_output=True, text=True, timeout=30)
    require((r.returncode == 0) == expected, r.stdout + r.stderr)

with tempfile.TemporaryDirectory(prefix='fee-size-projector-') as temp:
    root = Path(temp)
    run(SCRIPT, root, True)
    for rel in ('scripts/project-fee-size-fixtures.py', 'scripts/project-ledger-fixtures.py',
                'fixtures/ledger/upstream/vectors.tar.gz', 'fixtures/ledger/raw/shelley.cbor',
                'fixtures/ledger/raw/pparams.cbor'):
        dest = root / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / rel, dest)
    shutil.copytree(ROOT / 'fixtures/fee-size', root / 'fixtures/fee-size')
    script = root / 'scripts/project-fee-size-fixtures.py'
    run(script, root, True)
    cases = ('scripts/project-ledger-fixtures.py', 'fixtures/ledger/upstream/vectors.tar.gz',
             'fixtures/ledger/raw/shelley.cbor', 'fixtures/ledger/raw/pparams.cbor',
             'fixtures/fee-size/transfer-event-1.cbor', 'fixtures/fee-size/transfer-event-1.resolved.cbor',
             'fixtures/fee-size/fee-size-vectors.tsv', 'fixtures/fee-size/projection.json')
    for rel in cases:
        file = root / rel
        original = file.read_bytes()
        file.write_bytes(original + b'\n')
        run(script, root, False)
        file.write_bytes(original)
        print('PASS: rejects changed ' + rel)
    run(script, root, True)
print('11 portable/reproduction/tamper projector checks passed')
