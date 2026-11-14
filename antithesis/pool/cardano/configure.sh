#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Writes the cluster's genesis, keys and topologies to /env/net once, then exits 0.
#
# The genesis and keys come from `cardano-testnet create-env` with its defaults, as `scripts/devnet.py up` makes them.
# The start time is set here, at setup, a fixed lead ahead, in the Byron and the Shelley genesis together
# (`scripts/devnet/scenarios/pool.py`, `pool_environment`): every pool must be listening before the first slot, or an
# early refused dial can leave a pool forging its own chain from genesis (issue 609), which at k = 5 never rejoins.
#
# On Antithesis an earlier version's `chmod -R a+rX` of copied files failed with "Operation not permitted" (create-env's
# own mode and owner changes did not), so no file here changes its mode or owner after it is created: files are
# written by redirection and renamed into place, and every node runs as root.
set -euo pipefail

MAGIC=1082026
LEAD_SECONDS=60
NET=/env/net

if [ -e /env/ready ]; then
  echo "configurator: /env is already configured"
  exit 0
fi

# SECURITY_PARAM and EPOCH_LENGTH, when the compose file sets them, are the chain's k and epoch length: settings of
# the experiment suite, each with its own question. create-env's k = 5 turns a fork at the start into a permanent
# split; a large k asks whether the forks that faults cause heal. k goes into the Byron genesis too, as create-env
# writes it into both.
create=(--num-pool-nodes 3 --testnet-magic "$MAGIC")
if [ -n "${EPOCH_LENGTH:-}" ]; then
  create+=(--epoch-length "$EPOCH_LENGTH")
fi
rm -rf "$NET"
cardano-testnet create-env "${create[@]}" --output "$NET"
cd "$NET"

start=$(( $(date +%s) + LEAD_SECONDS ))
jq --arg s "$(date -u -d "@$start" +%Y-%m-%dT%H:%M:%SZ)" '.systemStart = $s' shelley-genesis.json > shelley.tmp
mv shelley.tmp shelley-genesis.json
jq --argjson t "$start" '.startTime = $t' byron-genesis.json > byron.tmp
mv byron.tmp byron-genesis.json

if [ -n "${SECURITY_PARAM:-}" ]; then
  jq --argjson k "$SECURITY_PARAM" '.securityParam = $k' shelley-genesis.json > shelley.tmp
  mv shelley.tmp shelley-genesis.json
  jq --argjson k "$SECURITY_PARAM" '.protocolConsts.k = $k' byron-genesis.json > byron.tmp
  mv byron.tmp byron-genesis.json
fi
# PROTOCOL_MAJOR, when the compose file sets it, is the genesis protocol version's major, as
# `scripts/devnet/dijkstra.py` sets it; without it the chain starts at create-env's.
if [ -n "${PROTOCOL_MAJOR:-}" ]; then
  jq --argjson m "$PROTOCOL_MAJOR" '.protocolParams.protocolVersion.major = $m' shelley-genesis.json > shelley.tmp
  mv shelley.tmp shelley-genesis.json
fi
# Settings that the compose file mounts as a Conway genesis overlay are merged into create-env's.
if [ -f /config/conway-genesis-overlay.json ]; then
  jq --slurpfile o /config/conway-genesis-overlay.json '. * $o[0]' conway-genesis.json > conway.tmp
  mv conway.tmp conway-genesis.json
fi

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
echo "configurator: systemStart $(jq -r .systemStart shelley-genesis.json), magic $MAGIC," \
  "k $(jq .securityParam shelley-genesis.json), epochs of $(jq .epochLength shelley-genesis.json) slots," \
  "protocol $(jq -c .protocolParams.protocolVersion shelley-genesis.json)," \
  "PlutusV3 cost model of $(jq '.plutusV3CostModel | length' conway-genesis.json)"
