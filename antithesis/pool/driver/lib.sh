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
# Scarab's `systemClockMovedBack` trace (`ClockTrace.SystemClockMovedBack`), traced as the reference traces it when a
# reading falls in the previous reading's slot or earlier. The slot loop waits until the next slot's start, so two
# readings in one slot with the time moving forward are a wake before that start (#629). After a clock really moved
# back, the loop keeps the old slot and later readings move forward through earlier slots, which this tells apart.
CLOCK_TRACE_PATTERN='"event":"systemClockMovedBack"'
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
SCARAB_FALSE_CLOCK="Scarab never traces the clock moved back on a wake 1 µs to 1 ms before its slot, time moving on"
FORK_BELOW_K="the cluster's fork depth stays below k"
FORK_SEEN="a fork is observed"
PAYMENT_S1="s1 accepts a payment"
PAYMENT_HASKELL="a Haskell pool accepts a payment"
S1_FORGES_TXS="s1 forges a block with transactions"
LOGS_READ="the detectors read every pool's log"

# assertion HIT MUST_HIT TYPE DISPLAY MESSAGE CONDITION [DETAILS]
# Copies of the test commands run at once, so each line is appended under a lock, whole (after
# cardano-foundation/cardano-node-antithesis `components/composer-sdk/helper_sdk_common.sh`). A lock not had within
# 10 s is passed over rather than waited on: a line written without it is better than a command that hangs.
assertion() {
  local line
  line=$(jq -cn --argjson hit "$1" --argjson must "$2" --arg type "$3" --arg display "$4" --arg msg "$5" \
     --argjson cond "$6" --argjson details "${7:-null}" '
    {antithesis_assert: ({hit: $hit, must_hit: $must, assert_type: $type, display_type: $display,
                          message: $msg, id: $msg, condition: $cond,
                          location: {class: "", function: "main", file: "antithesis/pool/driver/lib.sh",
                                     begin_line: 0, begin_column: 0}}
                         + (if $details == null then {} else {details: $details} end))}') || return 0
  {
    flock -w 10 9 || true
    printf '%s\n' "$line" >> "$SDK"
  } 9>> "$STATE_DIR/sdk.lock"
}

# A test command ends with exit code 0 when a signal stops it (the platform's per-command bound, or a closed pipe):
# its non-zero exit would fail the platform's "Commands finish with zero exit code", which is about the workload, not
# the system under test. What it had not yet asserted is simply not asserted.
exit_zero_on_signal() {
  trap 'echo "$(basename "$0"): stopped by a signal" >&2; exit 0' TERM INT HUP PIPE
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

# always MESSAGE CONDITION DETAILS: an evaluation of the always-or-unreachable MESSAGE, its first true and first false.
always() {
  once "always/$1/$2" || return 0
  assertion true false always AlwaysOrUnreachable "$1" "$2" "$3"
}

# unreachable_hit MESSAGE DETAILS: the unreachable MESSAGE was reached; recorded once per timeline.
unreachable_hit() {
  once "unreachable/$1" || return 0
  assertion true false reachability Unreachable "$1" false "$2"
}

declare_catalog() {
  assertion false true reachability Reachable "$STARTS" false
  local m
  for m in "$SCARAB_EXTENDS" "$HASKELL_EXTENDS" "$ALL_EXTEND" "$ALL_AGREE" "$PAYMENT_S1" "$PAYMENT_HASKELL"; do
    assertion false true sometimes Sometimes "$m" false
  done
  # Scarab's `forged` event names a block's transactions; cardano-node's forge traces do not, so the reference
  # controls (S1_IMPLEMENTATION=cardano-node) leave this one out.
  if [ "${S1_IMPLEMENTATION:-scarab}" = scarab ]; then
    assertion false true sometimes Sometimes "$S1_FORGES_TXS" false
  fi
  # The divergence detectors run where the compose file mounts the pools' logs at /logs.
  if [ -d /logs ]; then
    for m in "$HASKELL_REFUSED" "$SCARAB_REFUSED" "$SCARAB_FATAL" "$SCARAB_FALSE_CLOCK"; do
      assertion false false reachability Unreachable "$m" false
    done
    assertion false false always AlwaysOrUnreachable "$FORK_BELOW_K" true
    assertion false true sometimes Sometimes "$FORK_SEEN" false
    assertion false true sometimes Sometimes "$LOGS_READ" false
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

# The first `systemClockMovedBack` of s1's log past its offset whose `prevTime` and `newTime` fall in one slot, with
# `newTime` the later and more than 1 µs (1.5, for the log's microseconds and float error) but less than 1 ms before
# the next slot's start: #629's millisecond floor woke at most that early. A clock skip back by less than a slot
# during the wait also wakes the loop inside the slot it read, but earlier: the platform's skips are 10 ms or more in
# all but a few, and the cardano-node pools traced `SystemClockMovedBack` hundreds of times in a run with them, none
# in a run without. The reference floors its wait to the microsecond
# (`threadDelay`), so it too can wake a fraction of a microsecond early and trace `SystemClockMovedBack`. A
# cardano-node 11.1.3 pool did, 144 ns before its slot, in a platform run of 2026-11-14. Scarab, floored as the
# reference since #955, does that far more often on the platform (why is not checked); that is not what this detects.
# Slots are counted from the genesis's `systemStart` by its `slotLength`, as `EpochGeometry` counts them; whole
# seconds are subtracted before the fraction is added, so the arithmetic stays exact enough.
scarab_false_clock() {
  local offset_file="$STATE_DIR/offset-clock-s1" log=/logs/s1.log genesis=/env/net/shelley-genesis.json size from found
  [ -f "$log" ] && [ -f "$genesis" ] || return 1
  size=$(stat -c %s "$log")
  from=$(cat "$offset_file" 2> /dev/null || echo 0)
  [ "$from" -le "$size" ] || from=0
  found=$(tail -c +"$((from + 1))" "$log" | grep -F -- "$CLOCK_TRACE_PATTERN" | jq -R -c -n \
      --argjson g "$(jq -c '{systemStart, slotLength}' "$genesis")" '
    def whole: sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601;
    def fraction: (capture("\\.(?<f>[0-9]+)Z$") | "0." + .f | tonumber) // 0;
    ($g.systemStart | whole) as $start | ($g.systemStart | fraction) as $startFraction
    | def since: (whole - $start) + (fraction - $startFraction);
    def slot: since / $g.slotLength | floor;
    def early: ((slot + 1) * $g.slotLength) - since;
    first(inputs | fromjson? | select(.newTime != null and .prevTime != null)
          | select((.newTime | slot) == (.prevTime | slot)
                   and ((.newTime | whole) - (.prevTime | whole)) + (.newTime | fraction) - (.prevTime | fraction) > 0
                   and (.newTime | early) > 0.0000015 and (.newTime | early) < 0.001))
    // empty')
  echo $(( size > 4096 ? size - 4096 : 0 )) > "$offset_file"
  [ -n "$found" ] || return 1
  printf '%s\n' "$found"
}

# One pass of the divergence detectors, where /logs is mounted. The detectors that read a log find nothing in a log
# that is missing or empty, so that they cannot pass unseen on a broken path, a sometimes property records that every
# pool's log was there to read (after dingo's removed analyzer, which asserted that it read each node's log).
check_divergence() {
  local found readable=true sizes='{}' n size
  [ -d /logs ] || return 0
  for n in h2 h3 s1; do
    size=$(stat -c %s "/logs/$n.log" 2> /dev/null || echo 0)
    [ "$size" -gt 0 ] || readable=false
    sizes=$(jq -cn --argjson s "$sizes" --arg n "$n" --argjson b "$size" '$s + {($n): $b}')
  done
  sometimes "$LOGS_READ" "$readable" "$(jq -cn --argjson s "$sizes" '{bytes: $s}')"
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
  if found=$(scarab_false_clock); then
    echo "detectors: s1 reported the clock moved back for two readings in one slot: $found"
    unreachable_hit "$SCARAB_FALSE_CLOCK" "$found"
  fi
  return 0
}

# The cluster's fork depth (forktree.py) against k from the genesis: past k a fork is permanent by protocol. At
# create-env's k = 5 the start race of this topology can pass it, the reference in s1's seat as well (#854); at a
# large k it asks whether the forks that faults cause heal.
check_forks() {
  local k result depth below seen
  [ -d /logs ] || return 0
  k=$(jq -r '.securityParam // empty' /env/net/shelley-genesis.json 2> /dev/null)
  [ -n "$k" ] || return 0
  result=$(timeout 60 python3 -I /opt/at/forktree.py "$STATE_DIR/forktree.json" /logs/h2.log /logs/h3.log \
             /logs/s1.log) || return 0
  depth=$(jq -r '.depth // empty' <<< "$result")
  [ -n "$depth" ] || return 0
  result=$(jq -c --argjson k "$k" '. + {k: $k}' <<< "$result")
  if [ "$depth" -lt "$k" ]; then below=true; else below=false; fi
  if [ "$depth" -ge 1 ]; then seen=true; else seen=false; fi
  always "$FORK_BELOW_K" "$below" "$result"
  sometimes "$FORK_SEEN" "$seen" "$result"
  [ "$below" = true ] || echo "detectors: the cluster's fork depth is $depth, k is $k: $result"
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
