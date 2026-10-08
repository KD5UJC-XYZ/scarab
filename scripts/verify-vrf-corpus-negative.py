#!/usr/bin/env python3
"""Exercise corpus integrity failures and independent affine regeneration in a temp copy."""
from pathlib import Path
import shutil
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parent.parent
with tempfile.TemporaryDirectory(prefix='vrf-corpus-') as tmp:
    work = Path(tmp)
    (work / 'scripts').mkdir()
    shutil.copy(ROOT / 'scripts/verify-vrf-corpus.py', work / 'scripts')
    shutil.copytree(ROOT / 'fixtures/vrf', work / 'fixtures/vrf')
    target = work / 'fixtures/vrf/vectors.tsv'
    original = target.read_bytes()
    def check(label, wanted):
        result = subprocess.run(['python3', str(work / 'scripts/verify-vrf-corpus.py')], capture_output=True, text=True, timeout=30)
        if (result.returncode == 0) != wanted:
            raise RuntimeError((label, result.stdout, result.stderr))
        print('PASS:', label)
    check('portable copied corpus', True)
    target.write_bytes(original + b'\n')
    check('input mutation rejected', False)
    target.write_bytes(original)
    target.unlink()
    check('missing input rejected', False)
    target.write_bytes(original)
    extra = target.with_name('unexpected.tsv')
    extra.write_text('unexpected')
    check('unmanifested input rejected', False)
    extra.unlink()
    # Historical independent affine generator uses only its own directory, not the external oracle.
    generator = ROOT / 'fixtures/vrf/evidence/affine_check.py'
    shutil.copy(generator, work / 'affine_check.py')
    subprocess.run(['python3', str(work / 'affine_check.py')], check=True, capture_output=True, timeout=60)
    for name, expected in [('affine-inputs.tsv', ROOT / 'fixtures/vrf/affine-inputs.tsv'), ('affine-expected.json', ROOT / 'fixtures/vrf/evidence/affine-expected.json')]:
        if (work / name).read_bytes() != expected.read_bytes():
            raise RuntimeError(f'affine regeneration mismatch: {name}')
    print('PASS: independent affine regeneration exact')
print('5 portable corpus checks passed')
