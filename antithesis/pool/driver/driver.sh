#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# The driver of the first trial run. It declares its assertions, waits until the three pools report one tip, reports
# setup complete, waits for fuzzing to start, then reads every pool's tip at a fixed interval.
#
# Assertions use the fallback SDK: JSON lines appended to $ANTITHESIS_OUTPUT_DIR/sdk.jsonl. There are only sometimes
# and reachable assertions: at k = 5 a partition can split the chain for good, which is the protocol working, so this
# run asserts no convergence `always`.
set -uo pipefail

MAGIC=1082026
NODES=(h2 h3 s1)
INTERVAL_SECONDS=5
SDK="${ANTITHESIS_OUTPUT_DIR:-/tmp}/sdk.jsonl"

STARTS="the workload starts"
SCARAB_EXTENDS="Scarab pool s1 extends its chain within one interval"
HASKELL_EXTENDS="a Haskell pool extends its chain within one interval"
ALL_EXTEND="all three pools extend their chains within one interval"
ALL_AGREE="all three pools report the same tip"

# assertion HIT MUST_HIT TYPE DISPLAY MESSAGE CONDITION [DETAILS]
assertion() {
  jq -cn --argjson hit "$1" --argjson must "$2" --arg type "$3" --arg display "$4" --arg msg "$5" \
     --argjson cond "$6" --argjson details "${7:-null}" '
    {antithesis_assert: ({hit: $hit, must_hit: $must, assert_type: $type, display_type: $display,
                          message: $msg, id: $msg, condition: $cond,
                          location: {class: "", function: "main", file: "antithesis/pool/driver/driver.sh",
                                     begin_line: 0, begin_column: 0}}
                         + (if $details == null then {} else {details: $details} end))}' >> "$SDK"
}

# The fallback SDK asks for the first true and the first false evaluation of an assertion only.
declare -A recorded
sometimes() {
  local key="$1/$2"
  [ -n "${recorded[$key]:-}" ] && return 0
  recorded[$key]=1
  assertion true true sometimes Sometimes "$1" "$2" "$3"
}

declare_catalog() {
  assertion false true reachability Reachable "$STARTS" false
  local m
  for m in "$SCARAB_EXTENDS" "$HASKELL_EXTENDS" "$ALL_EXTEND" "$ALL_AGREE"; do
    assertion false true sometimes Sometimes "$m" false
  done
}

# Every node's tip as one JSON object, {"h2": {"block": .., "hash": .., "slot": ..} or null, ...}.
tips() {
  local n out all='{}'
  for n in "${NODES[@]}"; do
    out=$(timeout 10 cardano-cli conway query tip --testnet-magic "$MAGIC" --socket-path "/ipc/$n/node.socket" \
            2>/dev/null | jq -c '{block, hash, slot}' 2>/dev/null) || out=null
    [ -n "$out" ] || out=null
    all=$(jq -cn --argjson all "$all" --arg n "$n" --argjson t "$out" '$all + {($n): $t}')
  done
  printf '%s\n' "$all"
}

# agree TIPS MIN_BLOCK: every node answered, at block MIN_BLOCK or later, and all report one hash.
agree() {
  jq -e --argjson min "$2" '[.[]] | all(. != null and .block != null and .block >= $min)
                                    and (map(.hash) | unique | length == 1)' <<< "$1" > /dev/null
}

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

until [ -e /workload-active ]; do sleep 0.1; done
assertion true true reachability Reachable "$STARTS" true

prev=$(tips)
while :; do
  sleep "$INTERVAL_SECONDS"
  now=$(tips)
  echo "driver: tips $now"
  extended=$(jq -cn --argjson p "$prev" --argjson c "$now" \
    '$c | with_entries(.value = ($p[.key] != null and .value != null and .value.block > $p[.key].block))')
  details=$(jq -cn --argjson p "$prev" --argjson c "$now" '{previous: $p, current: $c}')
  sometimes "$SCARAB_EXTENDS" "$(jq -r '.s1' <<< "$extended")" "$details"
  sometimes "$HASKELL_EXTENDS" "$(jq -r '.h2 or .h3' <<< "$extended")" "$details"
  sometimes "$ALL_EXTEND" "$(jq -r '[.[]] | all' <<< "$extended")" "$details"
  if agree "$now" 0; then together=true; else together=false; fi
  sometimes "$ALL_AGREE" "$together" "$details"
  prev=$now
done
