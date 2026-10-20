# SPDX-License-Identifier: Apache-2.0
"""Replay byte-bound era-specific released Haskell contexts offline; never substitute Scalus."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DIRECTORY = ROOT / 'fixtures/reference/context-matrix'
spec = importlib.util.spec_from_file_location('context_matrix_prepare', HERE / 'prepare.py')
prepare_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_module)
prepare = prepare_module.prepare
BASE_IMAGE = 'scarab-plutus176-context-reference:local'
BASE_ID = 'sha256:66c862cc374749f3631e91114358f3c063a7b0ec144163e501f3d22ca47821ef'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def image_id(image):
    result = subprocess.run(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'],
                            capture_output=True, text=True, check=True)
    value = result.stdout.strip()
    if re.fullmatch(r'sha256:[0-9a-f]{64}', value) is None:
        raise ValueError('no immutable image ID')
    return value


def build(image):
    if image_id(BASE_IMAGE) != BASE_ID:
        raise ValueError('context reference base image changed; rebuild and reverify its pinned recipe')
    with tempfile.TemporaryDirectory(prefix='scarab-plutus-ctx-build-') as scratch:
        receipt = Path(scratch) / 'image.id'
        subprocess.run(['docker', 'build', '-f', str(HERE / 'Dockerfile'), '--iidfile', str(receipt),
                        '-t', image, str(ROOT)], check=True)
        built = receipt.read_text().strip()
        if image_id(image) != built:
            raise ValueError('built image differs from tag')
        return built


def image_binding(image):
    immutable = image_id(image)
    files = {'Main.hs': '/oracle/context-matrix/harness/Main.hs',
             'scarab-context-oracle.cabal': '/oracle/context-matrix/harness/scarab-context-oracle.cabal',
             'cabal.project': '/oracle/context-matrix/cabal.project',
             'cabal.project.freeze': '/oracle/context-matrix/cabal.project.freeze'}
    binary = '/usr/local/bin/scarab-context-matrix'
    measured = subprocess.run(['docker', 'run', '--rm', '--read-only', '--network', 'none',
                              '--entrypoint', 'sha256sum', immutable, *files.values(), binary],
                             capture_output=True, text=True, check=True)
    hashes = {line.split(maxsplit=1)[1].strip(): line.split(maxsplit=1)[0]
              for line in measured.stdout.splitlines()}
    for source, target in files.items():
        if hashes.get(target) != digest(HERE / source):
            raise ValueError(f'stale oracle wrapper or dependency pin: {source}')
    provenance = json.loads((DIRECTORY / 'provenance.json').read_text())
    if hashes.get(binary) != provenance['oracleBinarySha256']:
        raise ValueError('executable differs from accepted reference observer')
    return {'imageId': immutable, 'imageFilesSha256': hashes}


def validate():
    manifest = json.loads((DIRECTORY / 'manifest.json').read_text())
    for name, expected in manifest['files'].items():
        path = (DIRECTORY / name).resolve()
        if not path.is_relative_to(DIRECTORY.resolve()) or digest(path) != expected:
            raise ValueError(f'changed corpus file: {name}')
    provenance = json.loads((DIRECTORY / 'provenance.json').read_text())
    for name, expected in provenance['oracleSourcesSha256'].items():
        path = (ROOT / name).resolve()
        if not path.is_relative_to(ROOT) or digest(path) != expected:
            raise ValueError(f'changed observer source: {name}')
    rows = json.loads((DIRECTORY / 'matrix.json').read_text())
    inputs = [{k: v for k, v in row.items() if k != 'oracle'} for row in rows]
    if inputs != prepare():
        raise ValueError('matrix differs from original bytes and reproducible derivations')
    if len(rows) != 309 or len({row['name'] for row in rows}) != len(rows):
        raise ValueError('incomplete or duplicate context matrix')
    for pv in range(5, 12):
        era = 'Alonzo' if pv < 7 else 'Babbage' if pv < 9 else 'Conway'
        for language in range(1, 2 if pv < 7 else 3 if pv < 9 else 4):
            for purpose in ['spending', 'minting', 'certifying', 'rewarding'] + ([] if pv < 9 else ['voting', 'proposing']):
                name = f'api-{era.lower()}-pv{pv}-v{language}-{purpose}'
                if not any(row['name'] == name for row in rows):
                    raise ValueError(f'missing protocol/language/purpose cell: {name}')
    for row in rows:
        if row['provenance']['ledgerAdmissionClaim']:
            raise ValueError('API result cannot claim whole-ledger admission')
        if row['oracle']['name'] != row['name']:
            raise ValueError('unbound oracle result')
        for script in row['oracle'].get('scripts', []):
            if type(script['success']) is not bool or not script['contextCbor']:
                raise ValueError('missing complete request or verdict')
            if script['success']:
                if (type(script['used']) is not dict or set(script['used']) != {'mem', 'steps'}
                        or any(type(value) is not int or value < 0 for value in script['used'].values())):
                    raise ValueError('missing successful budget pair')
            elif script['used'] is not None:
                raise ValueError('reference API does not supply failure consumed units')
    return rows


def replay(rows, image, output):
    binding = image_binding(image)
    with tempfile.TemporaryDirectory(prefix='scarab-plutus-ctx-replay-') as scratch:
        inputs = Path(scratch) / 'inputs.json'
        inputs.write_text(json.dumps(rows))
        measured = subprocess.run(['docker', 'run', '--rm', '--read-only', '--network', 'none',
                                   '-v', f'{inputs}:/cases.json:ro', binding['imageId'], '/cases.json'],
                                  capture_output=True, text=True, check=True)
    results = json.loads(measured.stdout)
    if results != [row['oracle'] for row in rows]:
        raise ValueError('Haskell collection, complete arguments, order, budget or outcome changed')
    if output:
        output.write_text(json.dumps(results, indent=1) + '\n')
        receipt = dict(binding, corpusSha256=digest(DIRECTORY / 'matrix.json'), outputSha256=digest(output),
                       observerSourcesSha256={str(path.relative_to(ROOT)): digest(path)
                                              for path in HERE.iterdir() if path.is_file()})
        output.with_suffix('.manifest.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f'PASS: {len(rows)} independent era-specific Haskell results and complete request bytes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='scarab-plutus-ctx-matrix:local')
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    rows = validate()
    if args.verify_only:
        print(f'PASS: {len(rows)} original/derived inputs; all supported era/protocol/language/purpose cells present')
    else:
        replay(rows, build(args.image) if args.build else args.image, args.out)
