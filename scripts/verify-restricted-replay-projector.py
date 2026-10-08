#!/usr/bin/env python3
"""Portable deterministic extraction and immutable-source/derived-output tamper checks."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/project-restricted-replay-fixtures.py'


def require(ok, detail):
    if not ok:
        raise ValueError(detail)


def run(script, cwd, source, output, expected):
    result = subprocess.run([sys.executable, str(script), '--check', '--source-root', str(source),
        '--output-dir', str(output)], cwd=cwd, capture_output=True, text=True, timeout=30)
    require((result.returncode == 0) == expected, result.stdout + result.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    args = parser.parse_args()
    sources = ('scripts/project-ledger-fixtures.py', 'fixtures/ledger/upstream/vectors.tar.gz',
        'fixtures/ledger/raw/shelley.cbor', 'fixtures/ledger/raw/pparams.cbor',
        'fixtures/ledger/upstream/amaru-fork-ShelleyUtxoSpec.hs',
        'fixtures/coverage/raw/missing-vkey.cbor', 'fixtures/coverage/upstream/generator-UtxowSpec.hs')
    count = 0
    with tempfile.TemporaryDirectory(prefix='restricted-replay-projector-') as name:
        root = Path(name)
        run(SCRIPT, root, args.source_root, ROOT / 'fixtures/restricted-replay', True)
        count += 1
        for source in sources:
            target = root / source
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(args.source_root / source, target)
        target_script = root / 'scripts/project-restricted-replay-fixtures.py'
        shutil.copyfile(SCRIPT, target_script)
        output = root / 'fixtures/restricted-replay'
        shutil.copytree(ROOT / 'fixtures/restricted-replay', output)
        run(target_script, root, root, output, True)
        count += 1
        artifacts = tuple('fixtures/restricted-replay/' + path.name for path in sorted(output.iterdir()) if path.is_file())
        for source in sources + artifacts:
            path = root / source
            original = path.read_bytes()
            path.write_bytes(original + b'\n')
            run(target_script, root, root, output, False)
            path.write_bytes(original)
            count += 1
            print('PASS: rejects changed ' + source)
        run(target_script, root, root, output, True)
        count += 1
    print(str(count) + ' portable/reproduction/tamper projector checks passed')


if __name__ == '__main__':
    main()
