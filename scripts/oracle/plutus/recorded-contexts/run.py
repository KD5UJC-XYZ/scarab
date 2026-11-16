# SPDX-License-Identifier: Apache-2.0
"""Collect the script contexts of the recorded Preview and preprod transactions and of the
several-voter probes with the unchanged context-matrix observer, offline, and check them.

--verify-only checks the fixture without Docker (with --full, also a kept copy of the complete
answers); the default replays the inputs through the bound image and requires the retained answers;
--record writes them anew (a new fixture) and the complete answers to --out."""
import argparse
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
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
MINIMUM = {'preview': 45, 'preprod': 4, 'voters': 4}
# Retained as digests: the 158 requests of this 159-input transaction carry its whole context each,
# 19.6 MB of answer, ten times all the others together. Each request keeps its protocol version,
# language, budget and cost model, and the SHA-256 of its script and of each argument's bytes; the
# complete answers stay outside the repository, bound by fullAnswersSha256 in provenance.json.
DIGESTED = {'preprod/preprod-c70243bb'}
sys.path.insert(0, str(ROOT / 'scripts'))
from devnet.ntcquery import decode  # noqa: E402


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


def array(buffer, position):
    """The items of the definite-length CBOR array at position, as (start, end) offsets, and its end."""
    head, info = buffer[position] >> 5, buffer[position] & 31
    require(head == 4 and info < 28, 'not a definite-length array')
    width = {24: 1, 25: 2, 26: 4, 27: 8}.get(info, 0)
    count = info if width == 0 else int.from_bytes(buffer[position + 1:position + 1 + width], 'big')
    start, spans = position + 1 + width, []
    for _ in range(count):
        _, end = decode(buffer, start)
        spans.append((start, end))
        start = end
    return spans, start


def request_digests(context):
    """A `PlutusWithContext` encoding as digests: [version, [language, script], arguments, budget,
    cost model], each argument by the SHA-256 of its bytes (a list for PlutusV1 and V2, the context
    alone from PlutusV3)."""
    pwc = bytes.fromhex(context)
    (version, script, arguments, budget, model), end = array(pwc, 0)
    require(end == len(pwc), 'trailing bytes after a request')
    major, _ = decode(pwc, version[0])
    (language, code), _ = decode(pwc, script[0])
    spans = array(pwc, arguments[0])[0] if language in (0, 1) else [arguments]
    (mem, steps), _ = decode(pwc, budget[0])
    values, _ = decode(pwc, model[0])
    return {'protocolMajor': major, 'language': language, 'scriptSha256': hashlib.sha256(code).hexdigest(),
            'argumentsSha256': [hashlib.sha256(pwc[a:b]).hexdigest() for a, b in spans],
            'budget': {'mem': mem, 'steps': steps}, 'costModel': values}


def kept(name, oracle):
    """The answer as the fixture keeps it: whole, or with each request as its digests."""
    if name not in DIGESTED or not oracle['collected']:
        return oracle
    scripts = [{key: value for key, value in script.items() if key != 'contextCbor'}
               | {'request': request_digests(script['contextCbor'])} for script in oracle['scripts']]
    return dict(oracle, scripts=scripts)


def answers_digest(results):
    return hashlib.sha256((json.dumps(results, indent=1) + '\n').encode()).hexdigest()


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
            form = 'request' if row['name'] in DIGESTED else 'contextCbor'
            require(all(script[form] for script in oracle['scripts']), 'missing complete request')
        else:
            require(oracle['collectErrorsCbor'] and oracle['failureKinds'], 'refusal lacks its errors')
    return inputs, rows


def check_full(rows, path):
    """A kept copy of the complete answers: the one provenance binds, and the source of the fixture."""
    provenance = json.loads((DIRECTORY / 'provenance.json').read_text())
    require(digest(path) == provenance['fullAnswersSha256'], 'not the complete answers provenance binds')
    results = json.loads(path.read_text())
    require([kept(result['name'], result) for result in results] == [row['oracle'] for row in rows],
            'the retained answers are not those of the complete answers')


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
    require(output is not None, 'the complete answers must be kept: give --out')
    inputs = prepare()
    binding, results = collect(inputs, image)
    rows = [dict(retained(row), oracle=kept(row['name'], result)) for row, result in zip(inputs, results)]
    (DIRECTORY / 'contexts.json').write_text(json.dumps(rows, indent=1) + '\n')
    path = DIRECTORY / 'provenance.json'
    provenance = json.loads(path.read_text())
    provenance.update(date=datetime.date.today().isoformat(), oracleImage=binding['imageId'],
                      oracleBinarySha256=binding['imageFilesSha256']['/usr/local/bin/scarab-context-matrix'],
                      oracleSourcesSha256=sources(), fullAnswersSha256=answers_digest(results),
                      observations=len(rows))
    path.write_text(json.dumps(provenance, indent=1) + '\n')
    files = {name: digest(DIRECTORY / name) for name in ('contexts.json', 'provenance.json')}
    (DIRECTORY / 'manifest.json').write_text(json.dumps({'files': files}, indent=1) + '\n')
    receipt(binding, results, output)
    print(f'RECORDED: {len(rows)} reference collections')


def replay(inputs, rows, image, output):
    binding, results = collect(inputs, image)
    if output:
        receipt(binding, results, output)
    require([kept(result['name'], result) for result in results] == [row['oracle'] for row in rows],
            'Haskell collection, request bytes, order or outcome changed')
    provenance = json.loads((DIRECTORY / 'provenance.json').read_text())
    require(answers_digest(results) == provenance['fullAnswersSha256'], 'complete answers changed')
    print(f'PASS: {len(rows)} reference collections reproduced byte for byte')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='scarab-plutus-ctx-matrix:local')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--record', action='store_true')
    parser.add_argument('--out', type=Path, help='raw answers, with a receipt beside them')
    parser.add_argument('--full', type=Path, help='with --verify-only: a kept copy of the complete answers')
    args = parser.parse_args()
    if args.record:
        record(args.image, args.out)
    else:
        inputs, rows = validate()
        if args.verify_only:
            if args.full:
                check_full(rows, args.full)
            print(f'PASS: {len(rows)} inputs bound to the recorded transactions and reproducible derivations'
                  + (', and the complete answers kept' if args.full else ''))
        else:
            replay(inputs, rows, args.image, args.out)
