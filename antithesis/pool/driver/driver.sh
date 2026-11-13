#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# The driver container's main process. It declares the assertion catalogue, waits until the three pools report one
# tip, reports setup complete and then stays up, so that Antithesis can run the test command
# `singleton_driver_watch_tips` in this container.
set -uo pipefail
source /opt/at/lib.sh

declare_catalog

echo "driver: waiting until h2, h3 and s1 report the same tip at block 2 or later"
last=""
while :; do
  now=$(tips)
  agree "$now" 2 && break
  answered=$(jq -c 'with_entries(.value = (if .value == null then "no answer" else .value.block end))' <<< "$now" \
             2>/dev/null || printf '%s' "$now")
  if [ "$answered" != "$last" ]; then
    echo "driver: waiting, blocks $answered"
    last=$answered
  fi
  sleep 1
done
echo "driver: setup complete at $now"
printf '%s\n' '{"antithesis_setup": {"status": "complete", "details": {"message": "h2, h3 and s1 report one tip"}}}' >> "$SDK"
exec sleep infinity
