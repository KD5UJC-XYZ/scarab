# SPDX-License-Identifier: Apache-2.0
"""Capture or replay unchanged released Shelley/Allegra/Mary auxiliary-data decoder/predicate results.

The verdicts are only ever produced by the Haskell executable in the pinned image; this script
binds inputs, image, executable and sources, and re-derives what is independent of the ledger
(the Blake2b-256 of the original bytes and the expected hash/presence failure set).
"""
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
DIRECTORY = ROOT / 'fixtures/reference/auxiliary-historical'
spec = importlib.util.spec_from_file_location('historical_auxiliary_prepare', HERE / 'prepare.py')
prepare_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_module)
prepare = prepare_module.prepare
BASE_IMAGE = 'scarab-plutus176-context-reference:local'
BASE_ID = 'sha256:66c862cc374749f3631e91114358f3c063a7b0ec144163e501f3d22ca47821ef'
BINARY = '/usr/local/bin/scarab-historical-auxiliary-oracle'
SOURCES = ('Dockerfile', 'Dockerfile.dockerignore', 'Main.hs', 'cabal.project', 'cabal.project.freeze',
           'scarab-historical-auxiliary-oracle.cabal', 'prepare.py', 'run.py')


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
    with tempfile.TemporaryDirectory(prefix='scarab-historical-aux-build-') as scratch:
        receipt = Path(scratch) / 'image.id'
        subprocess.run(['docker', 'build', '-f', str(HERE / 'Dockerfile'), '--iidfile', str(receipt),
                        '-t', image, str(ROOT)], check=True)
        built = receipt.read_text().strip()
        if image_id(image) != built:
            raise ValueError('built image differs from tag')
        return built


def image_binding(image):
    immutable = image_id(image)
    files = {'Main.hs': '/oracle/historical-auxiliary/harness/Main.hs',
             'scarab-historical-auxiliary-oracle.cabal':
                 '/oracle/historical-auxiliary/harness/scarab-historical-auxiliary-oracle.cabal',
             'cabal.project': '/oracle/historical-auxiliary/cabal.project',
             'cabal.project.freeze': '/oracle/historical-auxiliary/cabal.project.freeze'}
    measured = subprocess.run(['docker', 'run', '--rm', '--read-only', '--network', 'none',
                              '--entrypoint', 'sha256sum', immutable, *files.values(), BINARY],
                             capture_output=True, text=True, check=True)
    hashes = {line.split(maxsplit=1)[1].strip(): line.split(maxsplit=1)[0]
              for line in measured.stdout.splitlines()}
    for source, target in files.items():
        if hashes.get(target) != digest(HERE / source):
            raise ValueError(f'stale oracle wrapper or dependency pin: {source}')
    return {'imageId': immutable, 'imageFilesSha256': hashes}


def execute(binding, rows):
    inputs = json.dumps([{k: v for k, v in row.items() if k != 'oracle'} for row in rows])
    measured = subprocess.run(['docker', 'run', '--rm', '--read-only', '--network', 'none', '-i',
                               binding['imageId']], input=inputs, capture_output=True, text=True, check=True)
    return json.loads(measured.stdout)


def expected_failures(row):
    """The hash/presence failures of Shelley/Rules/Utxow.hs 427-443, derived without the ledger."""
    body, aux = row['bodyAuxHash'], row['auxiliary']
    if body is None and aux is None:
        return []
    if aux is None:
        return ['MissingTxMetadata']
    if body is None:
        return ['MissingTxBodyMetadataHash']
    return [] if body == row['expectedAuxHash'] else ['ConflictingMetadataHash']


def check_row(row):
    oracle = row['oracle']
    if oracle['name'] != row['name'] or type(oracle['decoded']) is not bool:
        raise ValueError(f"unbound decoder verdict: {row['name']}")
    if row['provenance']['ledgerAdmissionClaim']:
        raise ValueError('API result cannot claim whole-ledger admission')
    if not oracle['decoded']:
        if set(oracle) != {'name', 'decoded'}:
            raise ValueError(f"decoder refusal cannot supply other verdicts: {row['name']}")
        return
    if row['mode'] == 'aux':
        if type(oracle.get('valid')) is not bool or oracle['originalBytesPreserved'] is not True:
            raise ValueError(f"incomplete decoded verdict: {row['name']}")
        if oracle['auxHash'] != row['expectedAuxHash']:
            raise ValueError(f"hash is not over the original bytes: {row['name']}")
        return
    if row['auxiliary'] is None:
        if 'auxHash' in oracle:
            raise ValueError(f"unexpected auxiliary data: {row['name']}")
    elif oracle['auxHash'] != row['expectedAuxHash'] or oracle['originalBytesPreserved'] is not True:
        raise ValueError(f"transaction auxiliary bytes or hash changed: {row['name']}")
    # InvalidMetadata is not derived: it is the ledger's own validateTxAuxData verdict.
    seen = [f for f in oracle['failures'] if f != 'InvalidMetadata']
    if seen != expected_failures(row):
        raise ValueError(f"hash/presence failure set differs from the independent derivation: {row['name']}")


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
    rows = json.loads((DIRECTORY / 'auxiliary.json').read_text())
    if [{k: v for k, v in row.items() if k != 'oracle'} for row in rows] != prepare():
        raise ValueError('matrix differs from original bytes and reproducible derivations')
    if len({row['name'] for row in rows}) != len(rows) or len(rows) != provenance['observations']:
        raise ValueError('incomplete or duplicate auxiliary matrix')
    for row in rows:
        check_row(row)
    return rows


def sources():
    return {str((HERE / name).relative_to(ROOT)): digest(HERE / name) for name in SOURCES}


def capture(image, comparison):
    """First capture: run the unchanged reference once and write the fixture directory."""
    rows = prepare()
    binding = image_binding(image)
    results = execute(binding, rows)
    if len(results) != len(rows):
        raise ValueError('oracle dropped rows')
    for row, result in zip(rows, results):
        row['oracle'] = result
    for row in rows:
        check_row(row)
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    (DIRECTORY / 'auxiliary.json').write_text(json.dumps(rows, indent=1) + '\n')
    provenance = {
        'date': '2026-10-21', 'apiOnly': True, 'wholeLedgerAdmissionClaim': False, 'observations': len(rows),
        'ledgerSourceRevision': 'f649f9751074d2ab3de033fc3912f29c9862c1f5', 'referenceNode': '11.1.3',
        'referenceGitRevision': '938cba990357ae7c4b7f95c8f75dd9d31174bbeb', 'oracleCompiler': 'GHC9.10.3',
        'oracleBaseImage': BASE_ID, 'oracleImage': binding['imageId'],
        'oracleBinarySha256': binding['imageFilesSha256'][BINARY],
        'oracleSourcesSha256': sources(),
        'releasedPackages': json.loads(comparison.read_text()),
    }
    (DIRECTORY / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    manifest = {'files': {name: digest(DIRECTORY / name) for name in ('auxiliary.json', 'provenance.json')}}
    (DIRECTORY / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'CAPTURED: {len(rows)} observations')


def replay(rows, image):
    binding = image_binding(image)
    provenance = json.loads((DIRECTORY / 'provenance.json').read_text())
    if binding['imageFilesSha256'][BINARY] != provenance['oracleBinarySha256']:
        raise ValueError('executable differs from accepted reference observer')
    if execute(binding, rows) != [row['oracle'] for row in rows]:
        raise ValueError('Haskell historical auxiliary decoder admission or predicate changed')
    print(f'PASS: {len(rows)} independent Haskell historical auxiliary results')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='scarab-plutus-historical-auxiliary-reference:local')
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--capture', type=Path, metavar='PACKAGES_JSON',
                        help='write the fixtures from a fresh run; PACKAGES_JSON is the released/pinned source comparison')
    args = parser.parse_args()
    if args.build:
        build(args.image)
    if args.capture:
        capture(args.image, args.capture)
    else:
        loaded = validate()
        if args.verify_only:
            print(f'PASS: {len(loaded)} original/derived inputs; all auxiliary input bytes and outcomes bound')
        else:
            replay(loaded, args.image)
