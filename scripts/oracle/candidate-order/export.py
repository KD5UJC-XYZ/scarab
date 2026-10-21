#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Export native preference eligibility and global stable ordering from the consensus pin."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess

HERE = pathlib.Path(__file__).resolve().parent
PIN = '82ecba329d7d054340bf707d44fe6e9ac27cec40'
FILES = [
    'ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Protocol/Abstract.hs',
    'ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Protocol/PBFT.hs',
    'ouroboros-consensus-protocol/src/ouroboros-consensus-protocol/Ouroboros/Consensus/Protocol/Praos/Common.hs',
    'ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/ChainDB/Impl/ChainSel.hs',
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=pathlib.Path, required=True)
    parser.add_argument('--probe-dir', type=pathlib.Path, required=True)
    parser.add_argument('--output-dir', type=pathlib.Path, required=True)
    parser.add_argument('--image', default='scarab-33-genesis-reference-cache:local')
    parser.add_argument('--container', default='scarab-33-candidate-order-native')
    args = parser.parse_args()
    probe = args.probe_dir.resolve()
    probe.mkdir(parents=True, exist_ok=False)
    commands = []

    def run(argv):
        log = probe / f'command-{len(commands)}.log'
        with log.open('w') as out:
            result = subprocess.run(argv, stdout=out, stderr=subprocess.STDOUT, text=True)
        commands.append({'argv': argv, 'exitCode': result.returncode, 'logSha256': sha(log)})
        (probe / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        if result.returncode:
            raise RuntimeError(f'command failed: {log}')
        return log.read_text().strip()

    if run(['git', '-C', str(args.source), 'rev-parse', 'HEAD']) != PIN:
        raise ValueError('source pin differs')
    archive = probe / 'source.tar'
    run(['git', '-C', str(args.source), 'archive', '--format=tar', '-o', str(archive), PIN])
    source = probe / 'source'
    source.mkdir()
    run(['tar', '-xf', str(archive), '-C', str(source)])
    archive.unlink()
    observer = source / 'scarab-candidate-order'
    observer.mkdir()
    shutil.copyfile(HERE / 'Main.hs', observer / 'Main.hs')
    shutil.copyfile(HERE / 'inputs.jsonl', probe / 'inputs.jsonl')
    with (source / 'ouroboros-consensus.cabal').open('a') as out:
        out.write('''
executable scarab-candidate-order
  import: common-test
  hs-source-dirs: scarab-candidate-order
  main-is: Main.hs
  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive
  build-depends:
    base, aeson, bytestring, cardano-base, cardano-crypto-class, cardano-ledger-core,
    cardano-protocol-tpraos, ouroboros-consensus:{ouroboros-consensus,protocol}
''')
    created = False
    try:
        run(['docker', 'create', '--network=none', '--name', args.container,
             '--entrypoint', 'tail', '-v', f'{probe}:/probe', '-w', '/probe/source',
             args.image, '-f', '/dev/null'])
        created = True
        run(['docker', 'start', args.container])
        base = ['docker', 'exec', args.container]
        run(base + ['sh', '-c', "sed 's/packages: harness/packages: ./' /oracle/project/cabal.project > cabal.project; printf '\\npackage blockio\\n  flags: +serialblockio\\n' >> cabal.project"])
        run(base + ['cabal', 'build', '--offline', 'exe:scarab-candidate-order', '--builddir=/probe/builddir', '--jobs=2'])
        exe = run(base + ['cabal', 'list-bin', 'exe:scarab-candidate-order', '--builddir=/probe/builddir']).splitlines()[-1]
        run(base + [exe, '/probe/inputs.jsonl', '/probe/native.jsonl', '/probe/sort.jsonl'])
        args.output_dir.mkdir(parents=True, exist_ok=True)
        payload = args.output_dir / 'native.jsonl'
        shutil.copyfile(probe / 'native.jsonl', payload)
        shutil.copyfile(probe / 'sort.jsonl', args.output_dir / 'sort.jsonl')
        rows = [json.loads(line) for line in payload.read_text().splitlines()]
        original = [json.loads(line) for line in (HERE / 'inputs.jsonl').read_text().splitlines()]
        if [row['input'] for row in rows] != original:
            raise ValueError('native export changed inputs')
        image = run(['docker', 'image', 'inspect', args.image, '--format', '{{.Id}}'])
        manifest = {
            'schema': 'scarab-native-candidate-order-v1',
            'sourceCommit': PIN,
            'sourceFiles': {name: sha(source / name) for name in FILES},
            'inputSha256': sha(HERE / 'inputs.jsonl'),
            'observerSha256': sha(HERE / 'Main.hs'),
            'exporterSha256': sha(pathlib.Path(__file__)),
            'payloadSha256': sha(payload),
            'sortPayloadSha256': sha(args.output_dir / 'sort.jsonl'),
            'syntheticSortCaseCount': 7,
            'executableSha256': run(base + ['sha256sum', exe]).split()[0],
            'buildPlanSha256': sha(probe / 'builddir/cache/plan.json'),
            'image': image,
            'caseCount': len(rows),
            'inputDomain': '24 constructed finite cases; no upstream generator claim; native unrestricted Ord and separately configured preferCandidate',
            'commands': commands,
        }
        (args.output_dir / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    finally:
        if created:
            run(['docker', 'rm', '-f', args.container])


if __name__ == '__main__':
    main()
