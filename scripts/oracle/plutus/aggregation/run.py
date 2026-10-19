# SPDX-License-Identifier: Apache-2.0
"""Reproduce retained released-API aggregation results offline, including complete predicate bytes."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from oracle.plutus.aggregation.prepare import prepare

DIRECTORY = ROOT / 'fixtures/reference/script-results'


def validate_budget(script):
    if type(script.get('success')) is not bool or 'used' not in script:
        raise ValueError('each script must have a boolean verdict and explicit used-budget field')
    used = script['used']
    if script['success']:
        if (type(used) is not dict or set(used) != {'mem', 'steps'}
                or any(type(value) is not int or value < 0 for value in used.values())):
            raise ValueError('successful reference evaluation requires nonnegative integer mem/steps')
    elif used is not None:
        raise ValueError('the restricting API exposes no failed-evaluation consumed budget')


def image_id(image):
    result = subprocess.run(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'],
                            capture_output=True, text=True, check=True)
    digest = result.stdout.strip()
    if re.fullmatch(r'sha256:[0-9a-f]{64}', digest) is None:
        raise ValueError('Docker returned no immutable oracle image ID')
    return digest


def build_image(image):
    with tempfile.TemporaryDirectory(prefix='scarab-aggregation-build-') as directory:
        path = Path(directory) / 'image.id'
        subprocess.run(['docker', 'build', '-f', str(HERE / 'Dockerfile'), '--iidfile', str(path),
                        '-t', image, str(ROOT)], check=True)
        # Never resolve or execute the previous tag when the build fails.
        built = path.read_text().strip()
        resolved = image_id(image)
        if built != resolved:
            raise ValueError('built image ID differs from the tag after build')
        return built


def image_binding(image):
    digest = image_id(image)
    paths = {'Main.hs': '/oracle/aggregation/harness/Main.hs',
             'scarab-aggregation-oracle.cabal': '/oracle/aggregation/harness/scarab-aggregation-oracle.cabal',
             'cabal.project': '/oracle/aggregation/cabal.project',
             'cabal.project.freeze': '/oracle/aggregation/cabal.project.freeze'}
    measured = subprocess.run(['docker', 'run', '--rm', '--read-only', '--network', 'none',
                              '--entrypoint', 'sha256sum', digest, *paths.values(),
                              '/usr/local/bin/scarab-aggregation-oracle'],
                             capture_output=True, text=True, check=True)
    hashes = {line.split(maxsplit=1)[1].strip(): line.split(maxsplit=1)[0]
              for line in measured.stdout.splitlines()}
    for source, path in paths.items():
        if hashes.get(path) != hashlib.sha256((HERE / source).read_bytes()).hexdigest():
            raise ValueError(f'oracle image contains stale wrapper or dependencies: {source}')
    binary = hashes.get('/usr/local/bin/scarab-aggregation-oracle')
    if binary is None or re.fullmatch('[0-9a-f]{64}', binary) is None:
        raise ValueError('oracle image has no bound executable')
    expected = json.loads((DIRECTORY / 'provenance.json').read_text())['oracleBinarySha256']
    if binary != expected:
        raise ValueError('oracle executable differs from the retained accepted binary')
    return {'imageId': digest, 'imageFilesSha256': hashes}


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
        for script in row['oracle'].get('scripts', []):
            validate_budget(script)
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
    binding = image_binding(image)
    with tempfile.TemporaryDirectory(prefix='scarab-aggregation-oracle-') as directory:
        path = Path(directory) / 'inputs.json'
        path.write_text(json.dumps(corpus))
        result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--read-only',
                                 '-v', f'{path}:/cases.json:ro', binding['imageId'], '/cases.json'],
                                capture_output=True, text=True, check=True)
    measured = json.loads(result.stdout)
    if measured != [row['oracle'] for row in corpus]:
        raise ValueError('released reference results, logs, budgets or full predicate bytes changed')
    if output:
        output.write_text(json.dumps(measured, indent=1) + '\n')
        manifest = dict(binding, corpusSha256=hashlib.sha256((DIRECTORY / 'aggregation.json').read_bytes()).hexdigest(),
                        outputSha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                        wrapperSourcesSha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                              for path in sorted(HERE.iterdir()) if path.is_file()})
        output.with_suffix('.manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'PASS: {len(corpus)} unchanged-reference aggregate results and complete predicate byte vectors')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='scarab-plutus195-aggregation-reference:local')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--build', action='store_true', help='build successfully, bind its image ID, then replay; no stale-tag fallback')
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    rows = validate()
    if args.verify_only:
        print(f'PASS: {len(rows)} reproducible original-byte API inputs')
    else:
        selected = build_image(args.image) if args.build else args.image
        replay(rows, selected, args.out)
