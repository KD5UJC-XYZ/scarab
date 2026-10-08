#!/usr/bin/env python3
"""Optional offline public-key hash reproduction using an explicitly supplied CLI.

The CLI is not a project/runtime dependency. Accepts only the exact pinned public
reference binary. No keys generated, no signing, no socket variables or node calls.
Prints observations to stdout; modifies neither fixtures nor reference binaries.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CLI_SHA256 = '0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed'
MAX_BINARY_BYTES = 512_000_000


def check_binary(path):
    digest = hashlib.sha256()
    total = 0
    with path.open('rb') as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_BINARY_BYTES:
                raise ValueError('reference CLI exceeds byte limit')
            digest.update(chunk)
    if digest.hexdigest() != CLI_SHA256:
        raise ValueError('reference CLI SHA256 mismatch; refusing execution')


def main():
    if sys.flags.optimize:
        raise ValueError("Do not disable validation with -O")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli', required=True, type=Path, help='path to separately acquired pinned cardano-cli binary')
    args = parser.parse_args()
    cli = args.cli.resolve(strict=True)
    check_binary(cli)
    spec = importlib.util.spec_from_file_location('coverage_public_hash_projector', ROOT / 'scripts/project-coverage-fixtures.py')
    p = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = p
    spec.loader.exec_module(p)
    inputs = p.read_inputs()
    artifacts = p.project(inputs, p.load_decoder(inputs))
    p.require({name: hashlib.sha256(data).hexdigest() for name, data in artifacts.items()} == p.EXPECTED_OUTPUTS,
              'projection fixed hashes')
    rows = json.loads(artifacts['projection.json'])
    results = []
    for row in rows:
        event = row['event_index']
        envelope = ROOT / f'fixtures/coverage/oracle/event-{event}-witness-0.vkey'
        command = [str(cli), 'address', 'key-hash', '--payment-verification-key-file', str(envelope)]
        result = subprocess.run(command, cwd=ROOT, env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'},
                                close_fds=True, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=20)
        expected = row['provided_witnesses'][0]['key_hash_blake2b224']
        p.require(result.returncode == 0 and result.stdout == expected + '\n' and result.stderr == '',
                  'public CLI hash mismatch: ' + row['name'])
        results.append({'case': row['name'], 'binary_sha256': CLI_SHA256, 'exit_code': result.returncode,
                        'public_key_hex': row['provided_witnesses'][0]['public_key_hex'],
                        'expected_blake2b224': expected, 'stdout': result.stdout, 'matches': True})
    print(json.dumps(results, indent=2, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print('public coverage hash check failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
