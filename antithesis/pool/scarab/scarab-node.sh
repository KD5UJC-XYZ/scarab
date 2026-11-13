#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Runs the Scarab pool s1 on pool 1's keys, with the flags of the devnet's `pool` scenario
# (`scripts/devnet/scenarios/pool.py`) and the JVM settings of `scala_container` in `scripts/devnet/cluster.py`.
# The image's own `java -jar` launch is kept; the classpath launch that coverage instrumentation needs comes later.
set -euo pipefail

keys=/data/keys
# The node evolves its KES key in place, so a restarted container keeps the evolved key rather than the original.
if [ ! -e "$keys/kes.skey" ]; then
  mkdir -p "$keys"
  cp /env/s1-keys/vrf.skey /env/s1-keys/kes.skey /env/s1-keys/opcert.cert "$keys/"
  chmod 0600 "$keys/kes.skey"
  chmod 0400 "$keys/vrf.skey" "$keys/opcert.cert"
fi
mkdir -p /data/db
# A killed node leaves its socket file behind.
rm -f /ipc/node.socket
exec java -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=20 --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  -jar /opt/scarab/scarab.jar \
  follow \
  --genesis-dir /env/genesis \
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
