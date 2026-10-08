#!/usr/bin/env python3
from private_corpus_gate import require_private_corpus
require_private_corpus()
"""Offline direct-JVM acquisition acceptance. Never contacts a network service."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CP = (ROOT / 'app/target/runtime-classpath.txt').read_text()
results = []

def run(name, args, code, reason=None):
    p = subprocess.run(['java', '-cp', CP, 'lab.Main', 'chain-fetch', *map(str, args)],
                       cwd=ROOT, capture_output=True, text=True, timeout=30)
    assert p.returncode == code, (name, p.returncode, p.stdout, p.stderr)
    payload = json.loads(p.stdout) if p.stdout.strip().startswith('{') else None
    if reason:
        assert payload['reason'] == reason, (name, payload)
    if payload and 'bytesPreserved' in payload:
        assert payload['bytesPreserved'] is True
        for flag in ('networkAuthenticated', 'ledgerValidated', 'consensusValidated',
                     'mithrilAuthenticated', 'referenceReplayChecked'):
            assert payload[flag] is False, (name, flag)
    results.append({'case': name, 'exit': p.returncode, 'reason': reason})
    return payload

with tempfile.TemporaryDirectory(prefix='chain-fetch-cli-') as tmp:
    work = Path(tmp)
    for era in ('shelley', 'allegra', 'babbage'):
        output = work / era
        config = ROOT / f'fixtures/chain-fetch/{era}.tsv'
        args = ['run', '--config', config, '--output', output]
        first = run(f'{era}-run', args, 0, 'countReached')
        assert first['count'] == 4
        assert first['sourceLabel'] == ('preprod-source-provenanced' if era == 'babbage' else 'mainnet-labelled-unauthenticated')
        assert first['first'] and first['last']
        inspected = run(f'{era}-inspect', ['inspect', '--output', output, '--verify-bytes'], 0, 'inspected')
        assert inspected['status'] == 'inspected'
        second = run(f'{era}-resume', args + ['--resume'], 0, 'countReached')
        assert first['manifestSha256'] == inspected['manifestSha256'] == second['manifestSha256']
        run(f'{era}-no-overwrite', args, 6)
    source = work / 'source'
    shutil.copytree(ROOT / 'fixtures/chain-fetch', source)
    config = source / 'shelley.tsv'
    original = config.read_text()
    config.write_text(original.replace('count\t4', 'count\t5'))
    run('source-exhausted', ['run', '--config', config, '--output', work / 'exhausted'], 3, 'sourceExhausted')
    config.write_text(original.replace('maxBlocks\t64', 'maxBlocks\t1'))
    run('object-budget', ['run', '--config', config, '--output', work / 'bounded'], 3, 'objectBudget')
    config.write_text(original + 'unknown\tvalue\n')
    run('unknown-config', ['run', '--config', config, '--output', work / 'unknown'], 2)
    config.write_text(original + 'count\t4\n')
    run('duplicate-config', ['run', '--config', config, '--output', work / 'duplicate'], 2)
    config.write_text(original.replace('manifest\tshelley/source.tsv', 'manifest\t../source.tsv'))
    run('path-traversal', ['run', '--config', config, '--output', work / 'traversal'], 2)
    lines = original.splitlines()
    config.write_text('\n'.join('manifestSha256\t' + '0' * 64 if s.startswith('manifestSha256\t') else s for s in lines) + '\n')
    run('source-pin-mismatch', ['run', '--config', config, '--output', work / 'bad-pin'], 5)
    config.write_text(original.replace('count\t4', 'count\t3'))
    run('incompatible-resume', ['run', '--config', config, '--output', work / 'shelley', '--resume'], 6)
    object_file = next((work / 'allegra/objects').glob('*.cbor'))
    object_file.write_bytes(b'corrupt')
    run('corrupt-inspection', ['inspect', '--output', work / 'allegra'], 6)
    run('remote-flags-unsupported', ['run', '--host', 'example.invalid'], 2)

(ROOT / 'docs/cli-chain-fetch-verification.json').write_text(json.dumps(results, indent=2) + '\n')
print(f'{len(results)} direct-JVM local chain-fetch CLI cases passed')
