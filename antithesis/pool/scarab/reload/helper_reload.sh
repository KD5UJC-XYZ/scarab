#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Shared by s1's reload commands (../../config-reload), which run inside s1's container, where the node's process is:
# each changes what a reload reads (the KES generation, the topology, or nothing), sends the node SIGHUP and waits for
# the node's answer in its log. What each did goes to /logs/reload.log as one JSON line, for the driver's checks.
#
# The node is signalled only once its current start is ready: `../scarab-node.sh` removes /tmp/node.boot when it
# starts and writes it just before it execs the JVM (the JVM keeps the shell's PID), with the log's length then; ready
# means that PID is the JVM, running the node's Main, and the log has `forgeStarted` past that length: the start read
# its credentials and installed its SIGHUP handler (a JVM ends on a SIGHUP that comes before the node's handler).
# Nothing is changed before that, so a start never reads a generation half switched. A kill of s1 kills these commands
# too, so none outlives the start it checked.
#
# One reload is outstanding at a time. Copies of the commands run at once and hold a lock for their whole step. A
# signal sent is recorded in /data/keys/.pending until the node's answer is read (`kesReloaded` or `kesReloadRefused`:
# every SIGHUP reads the credentials again); a later command resolves it first, and sends nothing while it is
# unanswered in the same start, for at most five minutes (`settle_pending`). Every wait is bounded; a command stopped
# by a signal exits 0, a broken harness (a copy or a rename that fails) exits 1.
set -uo pipefail

KEYS=/data/keys
ROTATIONS=/env/net/pools-keys/pool1/rot
TOPOLOGIES=/env/net
NODE_LOG=/logs/s1.log
RECORD=/logs/reload.log
BOOT=/tmp/node.boot
PENDING=$KEYS/.pending
UNCORRELATED=$KEYS/.uncorrelated
ANSWER='"event":"kesReload(ed|Refused)"'
NAME=$(basename "$0")

# On stderr, so that a call inside a command substitution never lands in what it captures.
say() { echo "$NAME: $*" >&2; }

trap 'exit 0' PIPE
trap 'trap "" PIPE; say "stopped by a signal"; exit 0' TERM INT HUP

broken() { say "harness: $*"; exit 1; }

# record ACTION OUTCOME [DETAILS_JSON]: one line in /logs/reload.log.
record() {
  printf '{"action":"%s","outcome":"%s","details":%s}\n' "$1" "$2" "${3:-null}" >> "$RECORD" ||
    broken "cannot append to $RECORD"
}

# A cluster without the experiment's files is not a reload cluster: nothing to do.
reload_cluster() {
  [ -L "$KEYS/current" ] && [ -d "$ROTATIONS" ] && [ -f "$TOPOLOGIES/topology-s1-hot1.json" ]
}

# lock: the step's lock, waited on for at most 60 s; another copy holding it longer means skip this one.
lock() {
  exec 9> "$KEYS/.reload.lock" || broken "cannot open the lock"
  flock -w 60 9
}

# ready: the PID and log offset of the node's current start, once that start has logged `forgeStarted`.
ready() {
  local pid from exe args n
  read -r pid from < "$BOOT" 2> /dev/null || return 1
  [[ $pid =~ ^[0-9]+$ && $from =~ ^[0-9]+$ ]] || return 1
  exe=$(readlink "/proc/$pid/exe" 2> /dev/null) || return 1
  [[ $exe == */java ]] || return 1
  args=$(tr '\0' '\n' < "/proc/$pid/cmdline" 2> /dev/null | grep -c -x -F 'xyz.kd5ujc.scarab.node.Main')
  [ "${args:-0}" -gt 0 ] || return 1
  # Read to the end, never stop the pipe early: pipefail would turn a found line into a failure.
  n=$(tail -c +"$((from + 1))" "$NODE_LOG" 2> /dev/null | grep -c -F '"event":"forgeStarted"')
  [ "${n:-0}" -gt 0 ] || return 1
  echo "$pid $from"
}

# first_after OFFSET PATTERN: the first line of the node's log past OFFSET matching the extended regex PATTERN.
first_after() {
  local lines
  lines=$(tail -c +"$(($1 + 1))" "$NODE_LOG" | grep -E -- "$2")
  [ -n "$lines" ] || return 1
  printf '%s\n' "${lines%%$'\n'*}"
}

# correlated: no unanswered reload was dropped in the current start ($START), so an answer is this command's own.
correlated() {
  local at rest
  [ -f "$UNCORRELATED" ] || return 0
  read -r at rest < "$UNCORRELATED"
  [ "$at $rest" != "$START" ] || return 1
  return 0
}

# settle_pending START: resolves an earlier signal's answer, START being "PID BOOT_OFFSET" of the current start (a
# restarted container may reuse a PID, never with the same offset). Returns 1 while one is unanswered in this start.
# A record older than PENDING_SECONDS is dropped: its signal may never have been sent (a command stopped between the
# record and the signal), and a reload that comes later anyway reads whatever generation is current, each a valid one,
# while a refused reload leaves the node's credentials as they were.
PENDING_SECONDS=300
settle_pending() {
  local ppid pboot poffset ptime paction pdetails answer age
  [ -f "$PENDING" ] || return 0
  read -r ppid pboot poffset ptime paction pdetails < "$PENDING"
  if [ "$ppid $pboot" != "$1" ]; then
    say "the $paction reload pending at a start that ended is dropped"
    record "$paction" lost-in-restart "${pdetails:-null}"
  elif answer=$(first_after "$poffset" "$ANSWER"); then
    say "the pending $paction reload was answered late: $answer"
    record "$paction" answered-late "${pdetails:-null}"
  else
    age=$(($(date +%s) - ptime))
    if [ "$age" -le "$PENDING_SECONDS" ] && [ "$age" -ge "-$PENDING_SECONDS" ]; then
      say "the $paction reload sent earlier is still unanswered"
      return 1
    fi
    say "the $paction reload recorded ${age} s ago has no answer; dropped"
    record "$paction" dropped-unanswered "${pdetails:-null}"
    # Its answer may still come, after a later signal, and the node's answer does not name the generation it read: from
    # now on in this start, an answer cannot be told for which signal it is.
    printf '%s\n' "$1" > "$UNCORRELATED" || broken "cannot write $UNCORRELATED"
  fi
  rm -f "$PENDING" || broken "cannot remove $PENDING"
}

# signal ACTION DETAILS: records the pending signal, then sends SIGHUP to the current start's JVM; sets OFFSET to the
# log length it was sent at. Returns 2 when the process is gone; a harness failure exits 1 (no command substitution
# runs it, so `broken` ends the command).
signal() {
  OFFSET=$(stat -c %s "$NODE_LOG") || broken "cannot read the node's log"
  printf '%s %s %s %s %s\n' "$START" "$OFFSET" "$(date +%s)" "$1" "$2" > "$PENDING.new" &&
    mv -f "$PENDING.new" "$PENDING" || broken "cannot write $PENDING"
  kill -HUP "${START%% *}" 2> /dev/null || return 2
}

# await OFFSET PATTERN SECONDS: the first line past OFFSET matching PATTERN, waited for at most SECONDS.
await() {
  local end=$((SECONDS + $3)) line
  while [ "$SECONDS" -lt "$end" ]; do
    if line=$(first_after "$1" "$2"); then
      printf '%s\n' "$line"
      return 0
    fi
    sleep 1
  done
  return 1
}

# begin: the common start of every command; sets START ("PID BOOT_OFFSET"), or exits 0 when there is nothing to do.
begin() {
  reload_cluster || { say "not a reload cluster"; exit 0; }
  lock || { say "another reload holds the lock"; exit 0; }
  START=$(ready) || { say "the node's current start is not ready"; exit 0; }
  settle_pending "$START" || exit 0
}

# finish: after the node's answer to this command's signal, clears the pending record.
finish() {
  rm -f "$PENDING" || broken "cannot remove $PENDING"
}
