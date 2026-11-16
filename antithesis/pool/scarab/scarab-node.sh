#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Runs the Scarab pool s1 on pool 1's keys, with the flags of the devnet's `pool` scenario
# (`scripts/devnet/scenarios/pool.py`) and the JVM settings of `scala_container` in `scripts/devnet/cluster.py`.
# It starts from the classpath, as Antithesis's coverage instrumentation needs: the manifest's Enable-Native-Access
# does not apply there, so the flag says it (as `scala_container` does).
# One setting is the harness's alone: cats-effect's fiber tracing is off (`-Dcats.effect.tracing.mode=none`). Its
# default, `cached`, records a trace event for each `flatMap`, `map` and `delay` a fiber runs, which cost s1 about 18%
# of its CPU in a local probe (mostly allocation), and the platform simulates the whole cluster more slowly for it.
# The price is diagnostic: an error's stack trace has no fiber frames, and a stall dump (`--stall-dump`) has no fiber
# snapshot, which cats-effect 3.7.1 takes only while tracing is on (`FiberMonitor.scala` 185-186, 290-295). A node
# run outside this harness keeps the default (#1024).
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
# WAIT_FOR, when the compose file sets it (every config but config-late-start), names the host:port pairs that must
# accept a TCP connection before the node starts: the container starts before the pools', so that its name resolves at
# their first DNS lookup, and the node still dials them only once they listen (issue 609).
for target in ${WAIT_FOR:-}; do
  until (exec 3<> "/dev/tcp/${target%:*}/${target#*:}") 2> /dev/null; do
    sleep 1
  done
done
# Where the compose file mounts /logs, the node's log also goes to a file there, for the driver's detectors; stdout
# still carries it.
if [ -d /logs ]; then
  exec > >(tee -a /logs/s1.log) 2>&1
fi
# A killed node leaves its socket file behind.
rm -f /ipc/node.socket
# Where Antithesis injects ANTITHESIS_OUTPUT_DIR (every container it runs), the node writes its own assertions there in
# the fallback SDK's format (`--assertions-file`, #1032); the platform keeps each container's file apart from the
# driver's. A local run sets the variable itself to read them. SCARAB_ASSERTIONS=off, set on s1 in the compose file,
# is for a build that has no such flag (before #1032, such as v0.2.0-alpha.1), which would refuse to start with it.
# The file is made readable, as the driver's is, before the node appends to it: the umask above is for the keys.
assertions=()
if [ -n "${ANTITHESIS_OUTPUT_DIR:-}" ] && [ "${SCARAB_ASSERTIONS:-on}" != off ]; then
  (umask 022 && mkdir -p "$ANTITHESIS_OUTPUT_DIR" && touch "$ANTITHESIS_OUTPUT_DIR/sdk.jsonl")
  assertions=(--assertions-file "$ANTITHESIS_OUTPUT_DIR/sdk.jsonl")
fi
exec java -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=20 --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  -Dcats.effect.tracing.mode=none \
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
  --opcert "$keys/opcert.cert" \
  "${assertions[@]}"
