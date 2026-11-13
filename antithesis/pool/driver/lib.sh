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
# The text of the Haskell pools' `ChainSync.Client.Exception` trace when they refuse a block, from a local run of the
# canary against cardano-node 11.1.3: `InvalidBlock (Point (At (Block {blockPointSlot = ...`.
REFUSAL_PATTERN='InvalidBlock (Point'
SDK="${ANTITHESIS_OUTPUT_DIR:-/tmp}/sdk.jsonl"

STARTS="the workload starts"
SCARAB_EXTENDS="Scarab pool s1 extends its chain within one interval"
HASKELL_EXTENDS="a Haskell pool extends its chain within one interval"
ALL_EXTEND="all three pools extend their chains within one interval"
ALL_AGREE="all three pools report the same tip"
CANARY_SUBMITTED="the writeBits transaction was submitted to s1 and answered"
HASKELL_REFUSED="a Haskell pool never refuses a block as invalid"
SCARAB_REFUSED="Scarab never refuses a block as invalid"

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
  # The divergence detectors run where the compose file mounts the Haskell pools' logs at /logs.
  if [ -d /logs ]; then
    assertion false false reachability Unreachable "$HASKELL_REFUSED" false
    assertion false false reachability Unreachable "$SCARAB_REFUSED" false
  fi
  if [ -d /opt/at/canary ]; then
    assertion false true sometimes Sometimes "$CANARY_SUBMITTED" false
  fi
}

# Where the divergence detectors run: s1's count of blocks its rules refused (`scarab_validation_errors_total`, by
# rule family), when s1 answers and the count is above 0.
scarab_refusal() {
  local metrics
  [ -d /logs ] || return 1
  metrics=$(curl -s --max-time 5 http://s1:12798/metrics) || return 1
  jq -Rn --arg text "$metrics" '[$text | split("\n")[] | select(startswith("scarab_validation_errors_total{"))]
    | if length == 0 then empty else . end
    | {count: (map(split(" ")[-1] | tonumber) | add), series: .} | select(.count > 0)' | grep . || return 1
}

# Where the compose file mounts the Haskell pools' logs at /logs: the first line of either log, past its byte offset
# in REFUSAL_OFFSETS, in which the pool refused a block as invalid. In an honest cluster only s1 can have forged it.
declare -A REFUSAL_OFFSETS
refusal() {
  local n log size from lines
  [ -d /logs ] || return 1
  for n in h2 h3; do
    log=/logs/$n.log
    [ -f "$log" ] || continue
    size=$(stat -c %s "$log")
    from=${REFUSAL_OFFSETS[$n]:-0}
    # The next scan starts 4 KiB before this one's end, so that a line cut by the end is read whole next time.
    REFUSAL_OFFSETS[$n]=$(( size > 4096 ? size - 4096 : 0 ))
    # Every command reads to the end: an early exit would break the pipe, and pipefail would hide the match.
    lines=$(tail -c +"$((from + 1))" "$log" | grep -F "$REFUSAL_PATTERN") || continue
    # the trace carries the whole script and its context: its first 2,000 characters name the block and the rule
    jq -cn --arg pool "$n" --arg line "${lines%%$'\n'*}" '{pool: $pool, trace: $line[0:2000]}'
    return 0
  done
  return 1
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
