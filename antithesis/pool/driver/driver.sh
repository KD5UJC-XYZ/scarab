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
# Setup completes from a healthy cluster: every pool on the longest chain (fork depth 0), with blocks of s1 and of a
# Haskell pool on it, so each side has taken the other's blocks. This topology's start race at k = 5 can keep the
# Haskell pools from taking s1's blocks until s1 has forked past k, a cardano-node in s1's seat as well (#854), and
# every timeline would inherit that split; such a setup fails and says so, instead of a run that asks nothing.
deadline=$((SECONDS + 600))
while :; do
  health=$(python3 -I /opt/at/forktree.py "$STATE_DIR/forktree.json" /logs/h2.log /logs/h3.log /logs/s1.log \
             2> /dev/null || echo '{}')
  if jq -e '.depth == 0 and (.pools | length == 3) and ((.forgedOnLongest.s1 // 0) >= 1)
            and (((.forgedOnLongest.h2 // 0) + (.forgedOnLongest.h3 // 0)) >= 1)' <<< "$health" > /dev/null; then
    break
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "driver: the cluster is not healthy after 600 s: $health" >&2
    exit 1
  fi
  sleep 2
done
now=$(tips)
echo "driver: healthy, $health"
echo "driver: setup complete at $now"
printf '%s\n' '{"antithesis_setup": {"status": "complete", "details": {"message": "h2, h3 and s1 report one tip"}}}' >> "$SDK"
exec sleep infinity
