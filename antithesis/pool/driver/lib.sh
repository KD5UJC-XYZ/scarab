#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Shared by the driver's main process and its test commands: the assertion catalogue in the fallback SDK's format
# (JSON lines appended to $ANTITHESIS_OUTPUT_DIR/sdk.jsonl), the pools' tips and the divergence detectors.
#
# The tips get sometimes and reachable assertions only: at k = 5 a partition can split the chain for good, which is
# the protocol working, so no convergence `always` is asserted yet.

MAGIC=1082026
NODES=(h2 h3 s1)
INTERVAL_SECONDS=5
# The text of the Haskell pools' `ChainSync.Client.Exception` trace when they refuse a block, from a local run of the
# canary against cardano-node 11.1.3: `InvalidBlock (Point (At (Block {blockPointSlot = ...`.
REFUSAL_PATTERN='InvalidBlock (Point'
# Scarab's `Node.Fatal` event (`EventKind.Fatal`) as its JSON log line writes it, and the text of the one fatal
# cause the reference has too: a clock moved back further than `Forger.MaxClockRewind` stops the node, as
# `checkValidClockChange` stops cardano-node (`Fatal.Internal(ClockProblem.SystemClockMovedBack)`). Antithesis's clock
# skips can cause it, so it is not a detector hit.
FATAL_PATTERN='"event":"fatal"'
CLOCK_STOP_PATTERN='the system clock moved back'
SDK="${ANTITHESIS_OUTPUT_DIR:-/tmp}/sdk.jsonl"
# What the test commands remember within a timeline (markers and log offsets): each runs as a process of its own,
# many times per timeline, and a timeline that branches takes this directory with it.
STATE_DIR=/tmp/at-state
mkdir -p "$STATE_DIR"

STARTS="the workload starts"
SCARAB_EXTENDS="Scarab pool s1 extends its chain within one interval"
HASKELL_EXTENDS="a Haskell pool extends its chain within one interval"
ALL_EXTEND="all three pools extend their chains within one interval"
ALL_AGREE="all three pools report the same tip"
CANARY_SUBMITTED="the writeBits transaction was submitted to s1 and answered"
HASKELL_REFUSED="a Haskell pool never refuses a block as invalid"
SCARAB_REFUSED="Scarab never refuses a block as invalid"
SCARAB_FATAL="Scarab never logs a fatal event"

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

# once KEY: succeeds the first time KEY is given in this timeline, in any process (mkdir is atomic).
once() {
  mkdir "$STATE_DIR/once-$(printf '%s' "$1" | md5sum | cut -c1-32)" 2> /dev/null
}

# The fallback SDK asks for the first true and the first false evaluation of an assertion only.
sometimes() {
  once "sometimes/$1/$2" || return 0
  assertion true true sometimes Sometimes "$1" "$2" "$3"
}

reached() {
  once "reachable/$1" || return 0
  assertion true true reachability Reachable "$1" true
}

# unreachable_hit MESSAGE DETAILS: the unreachable MESSAGE was reached; recorded once per timeline.
unreachable_hit() {
  once "unreachable/$1" || return 0
  assertion true false reachability Unreachable "$1" false "$2"
}

declare_catalog() {
  assertion false true reachability Reachable "$STARTS" false
  local m
  for m in "$SCARAB_EXTENDS" "$HASKELL_EXTENDS" "$ALL_EXTEND" "$ALL_AGREE"; do
    assertion false true sometimes Sometimes "$m" false
  done
  # The divergence detectors run where the compose file mounts the pools' logs at /logs.
  if [ -d /logs ]; then
    for m in "$HASKELL_REFUSED" "$SCARAB_REFUSED" "$SCARAB_FATAL"; do
      assertion false false reachability Unreachable "$m" false
    done
  fi
  if [ -d /opt/at/canary ]; then
    assertion false true sometimes Sometimes "$CANARY_SUBMITTED" false
  fi
}

# log_match NAME LOG PATTERN [EXCEPT]: the first line of LOG past NAME's offset that holds the fixed string PATTERN
# (and not the fixed string EXCEPT). The
# offset then moves to 4 KiB before the end read, so that a line cut by the end is read whole next time; a log that
# is shorter than the offset was started again, and is read from its start.
log_match() {
  local offset_file="$STATE_DIR/offset-$1" log=$2 size from lines status
  [ -f "$log" ] || return 1
  size=$(stat -c %s "$log")
  from=$(cat "$offset_file" 2> /dev/null || echo 0)
  [ "$from" -le "$size" ] || from=0
  # Every command reads to the end: an early exit would break the pipe, and pipefail would hide the match.
  if [ -n "${4:-}" ]; then
    lines=$(tail -c +"$((from + 1))" "$log" | grep -F -- "$3" | grep -v -F -- "$4")
  else
    lines=$(tail -c +"$((from + 1))" "$log" | grep -F -- "$3")
  fi
  status=$?
  # The offset moves only once the scan is done, so that a scan cut short is made again.
  echo $(( size > 4096 ? size - 4096 : 0 )) > "$offset_file"
  [ "$status" -eq 0 ] || return 1
  printf '%s\n' "${lines%%$'\n'*}"
}

# A Haskell pool refused a block as invalid: the pool and the start of its trace, which names the block and the rule
# (the trace carries the whole script and its context). Before blaming s1, check which pool forged that block.
haskell_refusal() {
  local n line
  for n in h2 h3; do
    line=$(log_match "refusal-$n" "/logs/$n.log" "$REFUSAL_PATTERN") || continue
    jq -cn --arg pool "$n" --arg line "$line" '{pool: $pool, trace: $line[0:2000]}'
    return 0
  done
  return 1
}

# s1 refused a block: its count of blocks its rules refused (`scarab_validation_errors_total`, by rule family), when
# s1 answers and the count is above 0. The count starts again at 0 when s1 restarts.
scarab_refusal() {
  local metrics
  metrics=$(curl -s --max-time 5 http://s1:12798/metrics) || return 1
  jq -Rn --arg text "$metrics" '[$text | split("\n")[] | select(startswith("scarab_validation_errors_total{"))]
    | if length == 0 then empty else . end
    | {count: (map(split(" ")[-1] | tonumber) | add), series: .} | select(.count > 0)' | grep . || return 1
}

scarab_fatal() {
  local line
  line=$(log_match fatal-s1 /logs/s1.log "$FATAL_PATTERN" "$CLOCK_STOP_PATTERN") || return 1
  jq -cn --arg line "$line" '{line: $line[0:2000]}'
}

# One pass of the divergence detectors, where /logs is mounted.
check_divergence() {
  local found
  [ -d /logs ] || return 0
  if found=$(haskell_refusal); then
    echo "detectors: a Haskell pool refused a block: $found"
    unreachable_hit "$HASKELL_REFUSED" "$found"
  fi
  if found=$(scarab_refusal); then
    echo "detectors: s1 refused a block: $found"
    unreachable_hit "$SCARAB_REFUSED" "$found"
  fi
  if found=$(scarab_fatal); then
    echo "detectors: s1 logged a fatal event: $found"
    unreachable_hit "$SCARAB_FATAL" "$found"
  fi
  return 0
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
