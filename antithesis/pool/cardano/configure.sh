#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Writes the cluster's genesis, keys and topologies to /env/net once, then exits 0.
#
# The genesis and keys come from `cardano-testnet create-env` with its defaults, as `scripts/devnet.py up` makes them.
# The start time is set here, at setup, a fixed lead ahead, in the Byron and the Shelley genesis together
# (`scripts/devnet/scenarios/pool.py`, `pool_environment`): every pool must be listening before the first slot, or an
# early refused dial can leave a pool forging its own chain from genesis (issue 609), which at k = 5 never rejoins.
#
# Antithesis refuses `chmod` (and so `chown` and `cp -a`) even to root, so no file here changes its mode or owner
# after it is created: files are written by redirection and renamed into place, and every node runs as root.
set -euo pipefail

MAGIC=1082026
LEAD_SECONDS=60
NET=/env/net

if [ -e /env/ready ]; then
  echo "configurator: /env is already configured"
  exit 0
fi

rm -rf "$NET"
cardano-testnet create-env --num-pool-nodes 3 --testnet-magic "$MAGIC" --output "$NET"
cd "$NET"

start=$(( $(date +%s) + LEAD_SECONDS ))
jq --arg s "$(date -u -d "@$start" +%Y-%m-%dT%H:%M:%SZ)" '.systemStart = $s' shelley-genesis.json > shelley.tmp
mv shelley.tmp shelley-genesis.json
jq --argjson t "$start" '.startTime = $t' byron-genesis.json > byron.tmp
mv byron.tmp byron-genesis.json

# cardano-node stops when its resource-statistics thread cannot read /proc/self/io (`without_resource_statistics`
# in `scripts/devnet/util.py`); frequency 0 starts no such thread.
if [ ! -e /proc/self/io ]; then
  jq '.TraceOptionResourceFrequency = 0' configuration.yaml > configuration.tmp
  mv configuration.tmp configuration.yaml
fi

# Each Haskell pool peers with the other Haskell pool and with the Scarab pool s1.
topology() {
  jq -n --arg a "$1" --arg b "$2" '{
    bootstrapPeers: null,
    localRoots: [{accessPoints: [{address: $a, port: 3001}, {address: $b, port: 3001}],
                  advertise: false, behindFirewall: false, diffusionMode: "InitiatorAndResponder",
                  hotValency: 2, trustable: true, warmValency: 2}],
    publicRoots: [{accessPoints: [], advertise: false}],
    useLedgerAfterSlot: -1}'
}
topology h3 s1 > topology-h2.json
topology h2 s1 > topology-h3.json

# Scarab reads the genesis directory the devnet gives it: these five files, byte for byte.
mkdir genesis
for f in shelley-genesis.json byron-genesis.json alonzo-genesis.json conway-genesis.json configuration.yaml; do
  cat "$f" > "genesis/$f"
done

touch /env/ready
echo "configurator: systemStart $(jq -r .systemStart shelley-genesis.json), magic $MAGIC"
