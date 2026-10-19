# SPDX-License-Identifier: Apache-2.0
"""Reproduce retained released-API aggregation results offline, including complete predicate bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(HERE))
from prepare import prepare

DIRECTORY = ROOT / 'fixtures/reference/script-results'


def validate():
    manifest = json.loads((DIRECTORY / 'manifest.json').read_text())
    for name, expected in manifest['files'].items():
        path = (DIRECTORY / name).resolve()
        if not path.is_relative_to(DIRECTORY.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f'fixture hash mismatch: {name}')
    provenance = json.loads((DIRECTORY / 'provenance.json').read_text())
    for name, expected in provenance['oracleSourcesSha256'].items():
        path = (ROOT / name).resolve()
        if not path.is_relative_to(ROOT) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f'wrapper source hash mismatch: {name}')
    corpus = json.loads((DIRECTORY / 'aggregation.json').read_text())
    inputs = [{key: value for key, value in row.items() if key != 'oracle'} for row in corpus]
    if inputs != [{key: value for key, value in row.items() if key != 'oracle'} for row in prepare()]:
        raise ValueError('original bytes or derivations differ from reproducible inputs')
    for row in corpus:
        if row['oracle']['name'] != row['name'] or not row['oracle']['collected']:
            raise ValueError('unexpected oracle name or collection failure')
        if row['name'].startswith('api-'):
            observed = [label for script in row['oracle']['scripts'] for label in script['verboseLogs']]
            if observed != row['expectedExecutionLabels']:
                raise ValueError('trace order differs from explicit execution order')
            if len(row['oracle']['failures']) != len(row['expectedFailures']):
                raise ValueError('reference failed to retain every failing script')
            if row['provenance']['ledgerAdmissionClaim'] or row['provenance']['signaturesRevalidated']:
                raise ValueError('derived API cases cannot claim signed admission')
    return corpus


def replay(corpus, image, output):
    with tempfile.TemporaryDirectory(prefix='scarab-aggregation-oracle-') as directory:
        path = Path(directory) / 'inputs.json'
        path.write_text(json.dumps(corpus))
        result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--read-only',
                                 '-v', f'{path}:/cases.json:ro', image, '/cases.json'],
                                capture_output=True, text=True, check=True)
    measured = json.loads(result.stdout)
    if measured != [row['oracle'] for row in corpus]:
        raise ValueError('released reference results, logs, budgets or full predicate bytes changed')
    if output:
        output.write_text(json.dumps(measured, indent=1) + '\n')
    print(f'PASS: {len(corpus)} unchanged-reference aggregate results and complete predicate byte vectors')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='scarab-plutus195-aggregation-reference:local')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    rows = validate()
    if args.verify_only:
        print(f'PASS: {len(rows)} reproducible original-byte API inputs')
    else:
        replay(rows, args.image, args.out)
