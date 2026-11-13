#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Shared by the driver's main process and its test command: the assertion catalogue in the fallback SDK's format
# (JSON lines appended to $ANTITHESIS_OUTPUT_DIR/sdk.jsonl) and the pools' tips.
#
# There are only sometimes and reachable assertions: at k = 5 a partition can split the chain for good, which is the
# protocol working, so no convergence `always` is asserted yet.

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
                          location: {class: "", function: "main", file: "antithesis/pool/driver/lib.sh",
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
