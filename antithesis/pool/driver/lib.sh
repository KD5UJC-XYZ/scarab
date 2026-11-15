#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Shared by the driver's main process and its test commands: the assertion catalogue in the fallback SDK's format
# (JSON lines appended to $ANTITHESIS_OUTPUT_DIR/sdk.jsonl), the pools' tips and the divergence detectors. What Scarab
# knows itself (a fatal stop, a forged block with transactions, the clock read before its slot) it asserts itself, in
# its own container's sdk.jsonl (`--assertions-file`, #1032); the driver reads s1 only for its refusal count and the
# fork tree.
#
# The tips get sometimes and reachable assertions only: at k = 5 a partition can split the chain for good, which is
# the protocol working, so no convergence `always` is asserted yet.

MAGIC=1082026
NODES=(h2 h3 s1)
INTERVAL_SECONDS=5
# The text of the Haskell pools' `ChainSync.Client.Exception` trace when they refuse a block, from a local run of the
# canary against cardano-node 11.1.3: `InvalidBlock (Point (At (Block {blockPointSlot = ...`.
REFUSAL_PATTERN='InvalidBlock (Point'
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
FORK_BELOW_K="the cluster's fork depth stays below k"
FORK_SEEN="a fork is observed"
PAYMENT_S1="s1 accepts a payment"
PAYMENT_HASKELL="a Haskell pool accepts a payment"
LOGS_PRESENT="every pool's log is present and not empty"

# assertion HIT MUST_HIT TYPE DISPLAY MESSAGE CONDITION [DETAILS]
# Copies of the test commands run at once, so each line is appended under a lock, whole (after
# cardano-foundation/cardano-node-antithesis `components/composer-sdk/helper_sdk_common.sh`). The lock is waited on:
# a holder keeps it for one append, and the driver is left out of every fault, so a minute is never needed. A line is
# never appended without it. A line that cannot be encoded or locked returns 1 with a note on stderr; the callers then
# fail their command (`record_once`, `must_record`), so that "Commands finish with zero exit code" shows it.
assertion() {
  local line
  line=$(jq -cn --argjson hit "$1" --argjson must "$2" --arg type "$3" --arg display "$4" --arg msg "$5" \
     --argjson cond "$6" --argjson details "${7:-null}" '
    {antithesis_assert: ({hit: $hit, must_hit: $must, assert_type: $type, display_type: $display,
                          message: $msg, id: $msg, condition: $cond,
                          location: {class: "", function: "main", file: "antithesis/pool/driver/lib.sh",
                                     begin_line: 0, begin_column: 0}}
                         + (if $details == null then {} else {details: $details} end))}') ||
    { echo "assertion: not encoded, not recorded: $5" >&2; return 1; }
  {
    flock -w 60 9 || { echo "assertion: no lock on $STATE_DIR/sdk.lock in 60 s, not recorded: $5" >&2; return 1; }
    printf '%s\n' "$line" >> "$SDK"
  } 9>> "$STATE_DIR/sdk.lock"
}

# must_record ARGS...: an assertion that the command fails without (the catalogue, the canary's answer).
must_record() {
  assertion "$@" || exit 1
}

# A test command ends with exit code 0 when a signal stops it (the platform's per-command bound, or a closed pipe):
# its non-zero exit would fail the platform's "Commands finish with zero exit code", which is about the workload, not
# the system under test. What it had not yet asserted is simply not asserted, and an exit code of 0 alone does not
# say a command finished. A closed pipe exits silently: a note on a closed stderr would raise SIGPIPE again.
exit_zero_on_signal() {
  trap 'exit 0' PIPE
  trap 'trap "" PIPE; echo "$(basename "$0"): stopped by a signal" >&2; exit 0' TERM INT HUP
}

# once KEY: succeeds the first time KEY is given in this timeline, in any process (mkdir is atomic).
once() {
  mkdir "$(marker "$1")" 2> /dev/null
}

marker() {
  printf '%s/once-%s' "$STATE_DIR" "$(printf '%s' "$1" | md5sum | cut -c1-32)"
}

# record_once KEY ARGS...: the assertion ARGS, the first time KEY is given in this timeline. A line not recorded gives
# the marker back, so that a later pass records it, and fails the command.
record_once() {
  local key=$1
  shift
  once "$key" || return 0
  assertion "$@" || { rmdir "$(marker "$key")" 2> /dev/null; exit 1; }
}

# The fallback SDK asks for the first true and the first false evaluation of an assertion only.
sometimes() {
  record_once "sometimes/$1/$2" true true sometimes Sometimes "$1" "$2" "$3"
}

reached() {
  record_once "reachable/$1" true true reachability Reachable "$1" true
}

# always MESSAGE CONDITION DETAILS: an evaluation of the always-or-unreachable MESSAGE, its first true and first false.
always() {
  record_once "always/$1/$2" true false always AlwaysOrUnreachable "$1" "$2" "$3"
}

# unreachable_hit MESSAGE DETAILS: the unreachable MESSAGE was reached; recorded once per timeline.
unreachable_hit() {
  record_once "unreachable/$1" true false reachability Unreachable "$1" false "$2"
}

declare_catalog() {
  must_record false true reachability Reachable "$STARTS" false
  local m
  for m in "$SCARAB_EXTENDS" "$HASKELL_EXTENDS" "$ALL_EXTEND" "$ALL_AGREE" "$PAYMENT_S1" "$PAYMENT_HASKELL"; do
    must_record false true sometimes Sometimes "$m" false
  done
  # The divergence detectors run where the compose file mounts the pools' logs at /logs.
  if [ -d /logs ]; then
    for m in "$HASKELL_REFUSED" "$SCARAB_REFUSED"; do
      must_record false false reachability Unreachable "$m" false
    done
    must_record false false always AlwaysOrUnreachable "$FORK_BELOW_K" false
    must_record false true sometimes Sometimes "$FORK_SEEN" false
  fi
  # Every config mounts /logs; without it this fails rather than the detectors passing unseen.
  must_record false true sometimes Sometimes "$LOGS_PRESENT" false
  if [ -d /opt/at/canary ]; then
    must_record false true sometimes Sometimes "$CANARY_SUBMITTED" false
  fi
}

# log_match NAME LOG PATTERN: the first line of LOG past NAME's offset that holds the fixed string PATTERN. The
# offset then moves to 4 KiB before the end read, so that a line cut by the end is read whole next time; a log that
# is shorter than the offset was started again, and is read from its start.
log_match() {
  local offset_file="$STATE_DIR/offset-$1" log=$2 size from lines status
  [ -f "$log" ] || return 1
  size=$(stat -c %s "$log")
  from=$(cat "$offset_file" 2> /dev/null || echo 0)
  [ "$from" -le "$size" ] || from=0
  # Every command reads to the end: an early exit would break the pipe, and pipefail would hide the match.
  lines=$(tail -c +"$((from + 1))" "$log" | grep -F -- "$3")
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

# One pass of the divergence detectors, where /logs is mounted. The detectors that read a log find nothing in a log
# that is missing or empty, so that they cannot pass unseen on a broken path, a sometimes property records that every
# pool's log was present and not empty (after dingo's removed analyzer, which asserted that it read each node's log).
# It says the logs were there, not that each detector read them through.
check_divergence() {
  local found readable=true sizes='{}' n size
  [ -d /logs ] || { sometimes "$LOGS_PRESENT" false '{"logs":"not mounted"}'; return 0; }
  for n in h2 h3 s1; do
    size=$(stat -c %s "/logs/$n.log" 2> /dev/null || echo 0)
    [ "$size" -gt 0 ] || readable=false
    sizes=$(jq -cn --argjson s "$sizes" --arg n "$n" --argjson b "$size" '$s + {($n): $b}')
  done
  sometimes "$LOGS_PRESENT" "$readable" "$(jq -cn --argjson s "$sizes" '{bytes: $s}')"
  if found=$(haskell_refusal); then
    echo "detectors: a Haskell pool refused a block: $found"
    unreachable_hit "$HASKELL_REFUSED" "$found"
  fi
  if found=$(scarab_refusal); then
    echo "detectors: s1 refused a block: $found"
    unreachable_hit "$SCARAB_REFUSED" "$found"
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
  # Every pass, for a run's search to sample: each pool's blocks on the longest chain (`forgedOnLongest`).
  echo "forktree: $result"
  if [ "$depth" -lt "$k" ]; then below=true; else below=false; fi
  if [ "$depth" -ge 1 ]; then seen=true; else seen=false; fi
  always "$FORK_BELOW_K" "$below" "$result"
  sometimes "$FORK_SEEN" "$seen" "$result"
  [ "$below" = true ] || echo "detectors: the cluster's fork depth is $depth, k is $k: $result"
  # At most once along each history: the marker branches with the timeline, so a branch taken after the line inherits
  # it and logs none of its own, and one taken before logs its own. A run's search then counts distinct crossings in
  # the run's tree of histories, to set against the distinct forge events in that tree, its exposure: runs explore
  # different lengths of chain in the same time. A signal between the marker and the line loses that line, so the
  # count is a best effort that can fall short, never over.
  if [ "$below" = false ] && once "forks/past-k"; then
    echo "forks: first past k in this timeline, depth $depth, k $k: $result"
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
