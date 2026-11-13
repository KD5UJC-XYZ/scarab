#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Runs the Scarab pool s1 on pool 1's keys, with the flags of the devnet's `pool` scenario
# (`scripts/devnet/scenarios/pool.py`) and the JVM settings of `scala_container` in `scripts/devnet/cluster.py`.
# It starts from the classpath, as Antithesis's coverage instrumentation needs: the manifest's Enable-Native-Access
# does not apply there, so the flag says it (as `scala_container` does).
set -euo pipefail

pool=/env/net/pools-keys/pool1
keys=/data/keys
# No `chmod` (one failed on Antithesis): the copies are created owner-only by the umask, since the node refuses a
# VRF key that "other" can read, as the reference does. The node evolves its KES key in place, so a restarted
# container keeps the evolved key; the KES key is copied last and renamed into place, so a copy cut short by a kill
# is redone.
umask 077
if [ ! -e "$keys/kes.skey" ]; then
  mkdir -p "$keys"
  cat "$pool/vrf.skey" > "$keys/vrf.skey"
  cat "$pool/opcert.cert" > "$keys/opcert.cert"
  cat "$pool/kes.skey" > "$keys/kes.skey.copy"
  mv "$keys/kes.skey.copy" "$keys/kes.skey"
fi
mkdir -p /data/db
# A killed node leaves its socket file behind.
rm -f /ipc/node.socket
exec java -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=20 --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  -cp '/opt/scarab/*' xyz.kd5ujc.scarab.node.Main \
  follow \
  --genesis-dir /env/net/genesis \
  --listen 0.0.0.0:3001 \
  --peer h2:3001 \
  --peer h3:3001 \
  --db /data/db \
  --local-socket /ipc/node.socket \
  --metrics-listen 0.0.0.0:12798 \
  --forge \
  --vrf-skey "$keys/vrf.skey" \
  --kes-skey "$keys/kes.skey" \
  --opcert "$keys/opcert.cert"
