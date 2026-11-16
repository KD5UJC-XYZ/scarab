# SPDX-License-Identifier: Apache-2.0
"""Collect the script contexts of the recorded Preview and preprod transactions and of the
several-voter probes with the unchanged context-matrix observer, offline, and check them.

--verify-only checks the fixture without Docker; the default replays the inputs through the bound
image and requires the retained answers; --record writes them anew (a new fixture)."""
import argparse
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DIRECTORY = ROOT / 'fixtures/reference/recorded-contexts'
OBSERVER = HERE.parent / 'context-matrix'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load('recorded_contexts_prepare', HERE / 'prepare.py').prepare
# The observer's own binding: its embedded sources and accepted executable (context-matrix provenance).
MATRIX = load('context_matrix_run', OBSERVER / 'run.py')
# The transaction, the outputs and the cost models of a recorded case stay in its recorded file
# (one copy); the fixture binds them by the hash of the observer input built from that file.
INLINE = ('transaction', 'utxo', 'costModels')
MINIMUM = {'preview': 45, 'preprod': 3, 'voters': 4}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def input_digest(row):
    return hashlib.sha256(json.dumps(row, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def retained(row):
    recorded = row['provenance']['kind'] == 'recorded-chain-transaction'
    kept = {key: value for key, value in row.items() if not (recorded and key in INLINE)}
    kept['inputSha256'] = input_digest(row)
    return kept


def sources():
    files = [HERE / 'prepare.py', HERE / 'run.py', ROOT / 'scripts/oracle/plutus/context/prepare.py']
    files += [OBSERVER / name for name in ('Main.hs', 'scarab-context-oracle.cabal', 'cabal.project',
                                           'cabal.project.freeze', 'Dockerfile', 'Dockerfile.dockerignore', 'prepare.py', 'run.py')]
    return {str(path.relative_to(ROOT)): digest(path) for path in sorted(files)}


def validate():
    manifest = json.loads((DIRECTORY / 'manifest.json').read_text())
    for name, expected in manifest['files'].items():
        path = (DIRECTORY / name).resolve()
        require(path.is_relative_to(DIRECTORY.resolve()) and digest(path) == expected, f'changed fixture file: {name}')
    provenance = json.loads((DIRECTORY / 'provenance.json').read_text())
    require(provenance['oracleSourcesSha256'] == sources(), 'observer or preparation source changed')
    matrix = json.loads((ROOT / 'fixtures/reference/context-matrix/provenance.json').read_text())
    require(provenance['oracleBinarySha256'] == matrix['oracleBinarySha256'], 'not the accepted observer executable')
    rows = json.loads((DIRECTORY / 'contexts.json').read_text())
    inputs = prepare()
    require([{k: v for k, v in row.items() if k != 'oracle'} for row in rows] == [retained(row) for row in inputs],
            'retained inputs differ from the recorded transactions and reproducible derivations')
    require(provenance['observations'] == len(rows), 'observation count differs')
    for group, minimum in MINIMUM.items():
        require(sum(row['name'].startswith(group + '/') for row in rows) >= minimum, f'too few {group} cases')
    for row in rows:
        oracle = row['oracle']
        require(oracle['name'] == row['name'], 'unbound oracle result')
        require(not row['provenance']['ledgerAdmissionClaim'], 'API result cannot claim ledger admission')
        if oracle['collected']:
            require(all(script['contextCbor'] for script in oracle['scripts']), 'missing complete request')
        else:
            require(oracle['collectErrorsCbor'] and oracle['failureKinds'], 'refusal lacks its errors')
    return inputs, rows


def collect(inputs, image):
    binding = MATRIX.image_binding(image)
    with tempfile.TemporaryDirectory(prefix='scarab-recorded-contexts-') as scratch:
        path = Path(scratch) / 'inputs.json'
        path.write_text(json.dumps(inputs))
        measured = subprocess.run(['docker', 'run', '--rm', '--read-only', '--network', 'none',
                                   '-v', f'{path}:/cases.json:ro', binding['imageId'], '/cases.json'],
                                  capture_output=True, text=True, check=True)
    results = json.loads(measured.stdout)
    require([result['name'] for result in results] == [row['name'] for row in inputs], 'results out of order')
    return binding, results


def receipt(binding, results, output):
    output.write_text(json.dumps(results, indent=1) + '\n')
    output.with_suffix('.manifest.json').write_text(json.dumps(
        dict(binding, outputSha256=digest(output), observerSourcesSha256=sources()), indent=2) + '\n')


def record(image, output):
    inputs = prepare()
    binding, results = collect(inputs, image)
    rows = [dict(retained(row), oracle=result) for row, result in zip(inputs, results)]
    (DIRECTORY / 'contexts.json').write_text(json.dumps(rows, indent=1) + '\n')
    path = DIRECTORY / 'provenance.json'
    provenance = json.loads(path.read_text())
    provenance.update(date=datetime.date.today().isoformat(), oracleImage=binding['imageId'],
                      oracleBinarySha256=binding['imageFilesSha256']['/usr/local/bin/scarab-context-matrix'],
                      oracleSourcesSha256=sources(), observations=len(rows))
    path.write_text(json.dumps(provenance, indent=1) + '\n')
    files = {name: digest(DIRECTORY / name) for name in ('contexts.json', 'provenance.json')}
    (DIRECTORY / 'manifest.json').write_text(json.dumps({'files': files}, indent=1) + '\n')
    if output:
        receipt(binding, results, output)
    print(f'RECORDED: {len(rows)} reference collections')


def replay(inputs, rows, image, output):
    binding, results = collect(inputs, image)
    if output:
        receipt(binding, results, output)
    require(results == [row['oracle'] for row in rows], 'Haskell collection, request bytes, order or outcome changed')
    print(f'PASS: {len(rows)} reference collections reproduced byte for byte')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='scarab-plutus-ctx-matrix:local')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--record', action='store_true')
    parser.add_argument('--out', type=Path, help='raw answers, with a receipt beside them')
    args = parser.parse_args()
    if args.record:
        record(args.image, args.out)
    else:
        inputs, rows = validate()
        if args.verify_only:
            print(f'PASS: {len(rows)} inputs bound to the recorded transactions and reproducible derivations')
        else:
            replay(inputs, rows, args.image, args.out)
