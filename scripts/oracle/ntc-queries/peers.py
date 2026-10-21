#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fresh, isolated reference input with real relays; preserve only public query/genesis evidence."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import time
from types import SimpleNamespace
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3]))
from scripts.devnet import cluster, util

parser = argparse.ArgumentParser()
parser.add_argument('--name', required=True)
parser.add_argument('--out', required=True, type=pathlib.Path)
args = parser.parse_args()
name = args.name
container = util.ref(name)
out = args.out
if out.exists() or util.data_root(name).exists():
    raise SystemExit('owned output/run directory already exists')
out.mkdir(parents=True)
try:
    image = util.docker('image', 'inspect', util.REFERENCE_IMAGE, '--format', '{{.Id}}').stdout
    (out / 'reference-image-id.txt').write_text(image)
    cluster.start_reference(name)
    util.in_ref(name, f'cardano-testnet create-env --num-pool-nodes 3 --testnet-magic {util.MAGIC} --output {util.ENV} > /work/create-env.log 2>&1')
    original = subprocess.run(['docker', 'exec', container, 'cat', f'{util.ENV}/shelley-genesis.json'], capture_output=True, check=True).stdout
    genesis = json.loads(original)
    ids = sorted(genesis['extraConfig']['stakePools']['data'])
    relays = [
        [{'single host address': {'port': 3001, 'IPv4': '192.0.2.10', 'IPv6': '2001:db8::10'}},
         {'single host name': {'port': 3002, 'dnsName': 'relay.example.'}}],
        [{'single host name': {'port': None, 'dnsName': 'srv.example.'}}],
        [{'single host address': {'port': 3003, 'IPv4': None, 'IPv6': '2001:db8::c'}},
         {'multi host name': {'dnsName': 'multi.example.'}}],
    ]
    if len(ids) != len(relays):
        raise SystemExit('expected exactly three generated pools')
    for pool, value in zip(ids, relays):
        genesis['extraConfig']['stakePools']['data'][pool]['relays'] = value
    changed = (json.dumps(genesis, indent=2) + '\n').encode()
    subprocess.run(['docker', 'exec', '-i', container, 'tee', f'{util.ENV}/shelley-genesis.json'], input=changed, stdout=subprocess.DEVNULL, check=True)
    (out / 'input-changes.json').write_text(json.dumps({'scope': 'only relays of three generated pools, before native cluster startup',
        'originalShelleySha256': hashlib.sha256(original).hexdigest(), 'modifiedShelleyBeforeStartupSha256': hashlib.sha256(changed).hexdigest(),
        'poolRelays': dict(zip(ids, relays))}, indent=2) + '\n')
    util.docker('exec', '-d', container, '/bin/sh', '-c', f'cd {util.ENV} && cardano-testnet cardano --node-env {util.ENV} > /work/cluster.log 2>&1')
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        response = util.docker('exec', container, '/bin/sh', '-c', f'cardano-cli conway query tip {util.SOCKET}', check=False)
        if response.returncode == 0 and json.loads(response.stdout).get('block', 0) > 0:
            break
        time.sleep(1)
    else:
        raise SystemExit('no native block by deadline')
    safe = out / 'genesis'
    safe.mkdir()
    for filename in ['shelley-genesis.json', 'byron-genesis.json', 'alonzo-genesis.json', 'conway-genesis.json', 'configuration.yaml']:
        data = subprocess.run(['docker', 'exec', container, 'cat', f'{util.ENV}/{filename}'], capture_output=True, check=True).stdout
        (safe / filename).write_bytes(data)
    subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('capture.py')),
                    '--container', container, '--out', str(out / 'capture')], check=True)
    versions = util.in_ref(name, 'cardano-node --version && cardano-cli --version && cardano-testnet version').stdout
    (out / 'versions.txt').write_text(versions)
    print(json.dumps({'captured': str(out)}))
finally:
    cluster.down(SimpleNamespace(name=name))
