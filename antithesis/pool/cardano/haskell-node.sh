#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Runs Haskell pool <n> (2 or 3) of the configured cluster, with the command line `scripts/devnet/cluster.py`
# (`start_nodes`) gives it, on 0.0.0.0:3001 and its local socket in /ipc.
set -euo pipefail

n=$1
pool=/env/net/pools-keys/pool$n
mkdir -p /data
# Where the compose file mounts /logs, the node's trace also goes to a file there, for the driver to read; stdout
# still carries it.
if [ -d /logs ]; then
  exec > >(tee -a "/logs/h$n.log")
fi
# A killed node leaves its socket file behind.
rm -f /ipc/node.socket
exec cardano-node run \
  --config /env/net/configuration.yaml \
  --topology "/env/net/topology-h$n.json" \
  --database-path /data/db \
  --shelley-kes-key "$pool/kes.skey" \
  --shelley-vrf-key "$pool/vrf.skey" \
  --shelley-operational-certificate "$pool/opcert.cert" \
  --byron-delegation-certificate "$pool/byron-delegation.cert" \
  --byron-signing-key "$pool/byron-delegate.key" \
  --socket-path /ipc/node.socket \
  --host-addr 0.0.0.0 \
  --port 3001
