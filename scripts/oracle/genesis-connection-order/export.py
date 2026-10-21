#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe the pinned native Ord(ConnectionId SockAddr) without copying its comparison code."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--probe-dir', type=Path, required=True)
    p.add_argument('--output-dir', type=Path, required=True)
    p.add_argument('--image', default='scarab-33-genesis-reference-cache:local')
    p.add_argument('--container', default='scarab-33-connection-order')
    args = p.parse_args()
    root = args.probe_dir.resolve()
    root.mkdir(parents=True, exist_ok=False)
    source = root / 'source'
    source.mkdir()
    shutil.copyfile(HERE / 'Main.hs', source / 'Main.hs')
    commands = []
    def run(command):
        commands.append(command)
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (root / f'command-{len(commands)}.log').write_text(result.stdout)
        (root / 'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
        if result.returncode:
            raise RuntimeError(f'command failed: {root}/command-{len(commands)}.log')
        return result.stdout.strip()
    made = False
    try:
        run(['docker','create','--network=none','--name',args.container,'--entrypoint','tail','-v',f'{root}:/probe','-w','/probe/source',args.image,'-f','/dev/null'])
        made = True
        run(['docker','start',args.container])
        base = ['docker','exec',args.container]
        store = run(base+['sh','-c','ls -d /root/.local/state/cabal/store/ghc-*/package.db'])
        units = {}
        for name in ['z-ouroboros-network-z-framework','network','aeson','bytestring']:
            units[name] = run(base+['ghc-pkg',('--global' if name == 'bytestring' else f'--package-db={store}'),'field',name,'id','--simple-output']).split()[0]
        unit_dump = {name: run(base+['ghc-pkg',('--global' if name == 'bytestring' else f'--package-db={store}'),'--ipid','describe',unit]) for name,unit in units.items()}
        artifacts = {}
        for name,unit in units.items():
            query = ['ghc-pkg', ('--global' if name == 'bytestring' else f'--package-db={store}'), '--ipid']
            directories = run(base+query+['field',unit,'library-dirs','--simple-output']).split()
            found = []
            for directory in directories:
                paths = run(base+['find',directory,'-maxdepth','1','-type','f','-name','*.a']).splitlines()
                if paths:
                    found.extend(run(base+['sha256sum',*paths]).splitlines())
            artifacts[name] = found
        binary = '/probe/connection-order'
        run(base+['ghc','-hide-all-packages',f'-package-db={store}','-package','base',*[argument for unit in units.values() for argument in ['-package-id',unit]],'-optl-Wl,--whole-archive','-optl/usr/local/lib/libsodium.a','-optl-Wl,--no-whole-archive','Main.hs','-outputdir','/probe/objects','-o',binary])
        run(base+[binary,'/probe/ordering.jsonl'])
        args.output_dir.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(root/'ordering.jsonl',args.output_dir/'ordering.jsonl')
        manifest = {'schema':'scarab-native-connection-order-v1','image':run(['docker','inspect',args.container,'--format','{{.Image}}']),'observerSha256':digest(HERE/'Main.hs'),'exporterSha256':digest(Path(__file__)),'nativeLibraryArtifacts':artifacts,'nativePackageUnits':units,'nativePackageMetadata':unit_dump,'executableSha256':run(base+['sha256sum',binary]).split()[0],'corpusSha256':digest(root/'ordering.jsonl'),'cases':len((root/'ordering.jsonl').read_text().splitlines())}
        (args.output_dir/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    finally:
        if made:
            run(['docker','rm','-f',args.container])
if __name__ == '__main__':
    main()
