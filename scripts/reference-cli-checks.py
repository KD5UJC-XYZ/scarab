#!/usr/bin/env python3
"""Optional pinned native Haskell decode/hash checks; never download or start a node."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BINARY_SHA256 = '0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed'
MANIFEST_SHA256 = '822fbb5afa481e795e5fbaae97e7c13986a9b86f01bef5af138338bf68c53806'
VERSION = 'cardano-cli 11.2.3.0 - linux-x86_64 - ghc-9.6\ngit rev 938cba990357ae7c4b7f95c8f75dd9d31174bbeb\n'
TIMEOUT = 20
ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'}

class CheckError(Exception):
    """Setup, tool or comparison failure; never a ledger rejection verdict."""

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def require(condition, message):
    if not condition:
        raise CheckError(message)

def invoke(cli, args, cwd):
    try:
        result = subprocess.run([str(cli), *args], cwd=cwd, env=ENV,
                                stdin=subprocess.DEVNULL, capture_output=True,
                                text=True, encoding='utf-8', timeout=TIMEOUT,
                                close_fds=True, check=False)
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        raise CheckError(f'tool execution failed ({type(exc).__name__}): {exc}') from exc
    return result

def inputs(fixtures):
    manifest_path = fixtures / 'extraction-manifest.json'
    require(digest(manifest_path) == MANIFEST_SHA256, 'fixture manifest checksum mismatch')
    manifest = json.loads(manifest_path.read_text())
    require(len(manifest['records']) == 3, 'expected exactly three corpus records')
    cases = []
    for rec in manifest['records']:
        txs = [a for a in rec['artifacts'] if a['kind'] == 'transaction-envelope']
        bodies = [a for a in rec['artifacts'] if a['kind'] == 'transaction-body']
        require(len(txs) == len(bodies) == 1, 'expected one full transaction and one body')
        tx, body = txs[0], bodies[0]
        for artifact in (tx, body):
            path = (fixtures / artifact['path']).resolve()
            require(path.is_relative_to(fixtures.resolve()), 'fixture path escapes root')
            require(path.stat().st_size == artifact['bytes'], f"fixture size mismatch: {artifact['path']}")
            require(digest(path) == artifact['sha256'], f"fixture checksum mismatch: {artifact['path']}")
        cases.append((rec, tx, body, (fixtures / tx['path']).read_bytes()))
    return cases

def checks(cli, fixtures, out):
    require(platform.system() == 'Linux' and platform.machine() in ('x86_64', 'AMD64'),
            'pinned binary requires Linux x86-64')
    require(cli.is_file() and os.access(cli, os.X_OK), 'CLI must be an existing executable file')
    require(digest(cli) == BINARY_SHA256, 'CLI checksum mismatch; no alternate binary is admitted')
    cases = inputs(fixtures)  # Reject corrupt inputs before any native execution.
    out.mkdir(parents=True, exist_ok=False)  # Never mix stale evidence with this run.
    report = {'schema_version': 1, 'status': 'running', 'scope': 'offline decode and transaction ID only',
              'binary_sha256': BINARY_SHA256, 'manifest_sha256': MANIFEST_SHA256,
              'environment': ENV, 'timeout_seconds': TIMEOUT, 'results': []}
    try:
        version = invoke(cli, ['--version'], out)
        (out / 'version.stdout').write_text(version.stdout)
        (out / 'version.stderr').write_text(version.stderr)
        require(version.returncode == 0 and not version.stderr and version.stdout == VERSION,
                'CLI version check failed')
        for rec, tx, body, raw in cases:
            name = Path(tx['path']).stem
            envelope = out / (name + '.json')
            envelope.write_text(json.dumps({'type': 'Tx ConwayEra',
                'description': 'Unmodified upstream ledger fixture for offline decoding',
                'cborHex': raw.hex()}, indent=2) + '\n')
            require(bytes.fromhex(json.loads(envelope.read_text())['cborHex']) == raw,
                    'text envelope changed original bytes')
            for operation, args in [('txid', ['conway', 'transaction', 'txid', '--tx-file', envelope.name, '--output-text']),
                                    ('view', ['debug', 'transaction', 'view', '--tx-file', envelope.name, '--output-json'])]:
                entry = {'fixture': tx['path'], 'sha256': tx['sha256'], 'bytes': len(raw),
                         'raw_body_path': body['path'], 'raw_body_sha256': body['sha256'],
                         'text_envelope_path': envelope.name, 'text_envelope_sha256': digest(envelope),
                         'operation': operation, 'argv': args, 'expected_txid': rec['expected_txid'],
                         'status': 'running'}
                report['results'].append(entry)
                result = invoke(cli, args, out)
                entry.update(exit_code=result.returncode, stdout_file=name + '.' + operation + '.stdout',
                             stderr_file=name + '.' + operation + '.stderr')
                (out / entry['stdout_file']).write_text(result.stdout)
                (out / entry['stderr_file']).write_text(result.stderr)
                require(result.returncode == 0 and not result.stderr, f'{name} {operation}: tool failed or emitted stderr')
                if operation == 'txid':
                    entry['actual_txid'] = result.stdout.strip()
                    require(entry['actual_txid'] == rec['expected_txid'], f'{name}: transaction ID mismatch')
                    entry['matches_expected'] = True
                else:
                    try:
                        view = json.loads(result.stdout)
                    except json.JSONDecodeError as exc:
                        raise CheckError(f'{name}: invalid JSON view') from exc
                    require(isinstance(view, dict) and view.get('era') == 'Conway', f'{name}: invalid Conway view')
                    entry.update(decoded_era=view['era'], decoded_fee=view.get('fee'), json_valid=True)
                entry['status'] = 'passed'
        report['status'] = 'passed'
    except (CheckError, OSError, ValueError) as exc:
        report.update(status='harness_error', error=str(exc))
        if report['results'] and report['results'][-1]['status'] == 'running':
            report['results'][-1]['status'] = 'harness_error'
        raise
    finally:
        (out / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    return report

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli', type=Path, required=True, help='explicit path to independently trusted pinned executable')
    parser.add_argument('--output', type=Path, required=True, help='new evidence directory; must not exist')
    args = parser.parse_args(argv)
    try:
        checks(args.cli.resolve(), ROOT / 'fixtures', args.output.resolve())
    except (CheckError, OSError, ValueError) as exc:
        print(f'ERROR: {exc}. No ledger acceptance/rejection verdict.', file=sys.stderr)
        return 2
    print('PASS: 6 offline CLI checks; 3 exact transaction IDs and 3 Conway JSON views.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
