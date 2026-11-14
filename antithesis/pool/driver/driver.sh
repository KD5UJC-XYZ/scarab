#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# The driver container's main process. It declares the assertion catalogue, waits until the three pools report one
# tip, reports setup complete and then stays up, so that Antithesis can run the test commands of
# /opt/antithesis/test/v1/main in this container.
set -uo pipefail
source /opt/at/lib.sh

declare_catalog

echo "driver: waiting until h2, h3 and s1 report the same tip at block 2 or later"
# They agree within a minute of the start time when s1 follows; a pool that forked off at the start never comes back
# at k = 5. The bound turns that into a setup failure that says so, instead of a wait for the platform's timeout.
deadline=$((SECONDS + 600))
last=""
while :; do
  now=$(tips)
  agree "$now" 2 && break
  answered=$(jq -c 'with_entries(.value = (if .value == null then "no answer" else .value.block end))' <<< "$now" \
             2>/dev/null || printf '%s' "$now")
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "driver: h2, h3 and s1 do not agree after 600 s, blocks $answered" >&2
    exit 1
  fi
  if [ "$answered" != "$last" ]; then
    echo "driver: waiting, blocks $answered"
    last=$answered
  fi
  sleep 1
done
# The canary enacts its cost model before setup complete, so that every timeline starts with it.
if [ -x /opt/at/canary/setup_cost_model ]; then
  /opt/at/canary/setup_cost_model || exit 1
  now=$(tips)
fi
# Setup completes from a healthy cluster: every pool answers, every pool's ancestry is known and on the longest chain
# (fork depth 0), and since this gate began each side has taken the other's blocks: a new block of s1 on each Haskell
# pool's own chain, and a new block of a Haskell pool on s1's. Counting from the gate's start, not over the cluster's
# life, keeps out a pool that took blocks once and then stopped, whose stale tip is an ancestor of the longest and
# so of depth 0. This topology's start race at k = 5 can keep the Haskell pools from taking s1's blocks until s1 has
# forked past k, a cardano-node in s1's seat as well (#854), and every timeline would inherit that split; such a
# setup fails and says so, instead of a run that asks nothing.
# The gate waits up to 1200 s. In config-late-start and its control, which start s1's container after the pools,
# Docker's DNS has no name for s1 at the pools' first local-root lookup, and after that NXDOMAIN the reference waits
# 900 s before it looks again (`ttlForDnsError`, ouroboros-network `RootPeersDNS/LocalRootPeers.hs`). Until then the
# pools take none of s1's blocks. On the platform that window outlasted a 600 s gate. Locally it is about a minute.
forks() {
  local out
  out=$(python3 -I /opt/at/forktree.py "$STATE_DIR/forktree.json" /logs/h2.log /logs/h3.log /logs/s1.log 2> /dev/null)
  jq -e 'type == "object"' <<< "$out" > /dev/null 2>&1 && printf '%s\n' "$out" || echo '{}'
}
base=$(forks)
deadline=$((SECONDS + 1200))
while :; do
  health=$(forks)
  now=$(tips)
  if jq -e --argjson base "$base" --argjson now "$now" '
       def on($o; $pool; $forger): $o.forgedOnChain[$pool][$forger] // 0;
       def took($o): {h2: on($o; "h2"; "s1"), h3: on($o; "h3"; "s1"), s1: (on($o; "s1"; "h2") + on($o; "s1"; "h3"))};
       took(.) as $after | took($base) as $before
       | .depth == 0 and (.pools | length == 3) and ([.pools[]] | all(. != null)) and ([$now[]] | all(. != null))
         and $after.h2 > $before.h2 and $after.h3 > $before.h3 and $after.s1 > $before.s1' <<< "$health" \
       > /dev/null 2>&1; then
    break
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "driver: the cluster is not healthy after 1200 s: $health, tips $now, at the gate's start $base" >&2
    exit 1
  fi
  sleep 2
done
echo "driver: healthy, $health"
echo "driver: setup complete at $now"
printf '%s\n' '{"antithesis_setup": {"status": "complete", "details": {"message": "h2, h3 and s1 report one tip"}}}' >> "$SDK"
exec sleep infinity
