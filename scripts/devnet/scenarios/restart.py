# SPDX-License-Identifier: Apache-2.0
"""The `restart` scenario: a graceful stop ends the JVM promptly, and the next start restores the snapshot."""
import json
import subprocess
import sys
import time
from ..cluster import up
from ..consensus import arguments
from ..util import ENTRY, data_root, docker, read_log

# `docker stop -t STOP_LIMIT` sends SIGTERM and kills after the limit; a node that ends by itself needs far less
STOP_LIMIT = 30
# issue 89: a stop that ran into the limit was killed (exit 137), not ended
STOP_BUDGET = STOP_LIMIT - 5


def stop_problem(elapsed, wrote_snapshot):
    """Why a graceful stop failed (taking `elapsed` seconds), else None."""
    if elapsed >= STOP_BUDGET:
        return f"the node needed {elapsed:.1f} s to stop (budget {STOP_BUDGET} s): the JVM did not exit on SIGTERM"
    if not wrote_snapshot:
        return "the node stopped without a shutdown snapshot"
    return None


def restore_problem(events):
    """Why the second start did not restore the first one's snapshot, else None."""
    if not any(e["event"] == "snapshotRestored" for e in events):
        return "the restart did not restore a snapshot"
    return None


# the magic of the snapshot files this build writes (FileKind.SnapshotFile current, SnapshotV5)
SNAPSHOT_MAGIC = b"SCRBSNP5"


def format_problem(magics):
    """Why the snapshot files are not all of the current format, else None (`magics`: the first 8 bytes of each)."""
    if not magics:
        return "no snapshot file in the database"
    stale = sorted({m.decode("latin-1") for m in magics if m != SNAPSHOT_MAGIC})
    if stale:
        return f"snapshot files not in the current format: {', '.join(stale)}"
    return None


def _start(args):
    command = [sys.executable, str(ENTRY), "--name", args.name, "follow", "--db", *arguments(args)]
    return subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def _wait_for(root, event, seconds, after=0):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        events, _ = read_log(root / "follower.jsonl")
        if any(e["event"] == event for e in events[after:]):
            return True
        time.sleep(1)
    return False


def _stop(name, process):
    """Seconds `docker stop` took, once the follower process is gone too."""
    began = time.monotonic()
    docker("stop", "-t", str(STOP_LIMIT), f"{name}-scala", check=False)
    process.wait()
    return time.monotonic() - began


def restart(args):
    """Follow with current V5 snapshots, SIGTERM, then restore the snapshot on restart."""
    root = data_root(args.name)
    up(args)
    failures, stops = [], []
    for round_ in (1, 2):
        prior_events, prior_other = read_log(root / "follower.jsonl")
        before, other_before = len(prior_events), len(prior_other)
        process = _start(args)
        if not _wait_for(root, "extended", 120, before):
            failures.append(f"round {round_}: no block followed within 120 s")
            docker("rm", "-f", f"{args.name}-scala", check=False)
            process.wait()
            break
        time.sleep(args.seconds)
        elapsed = _stop(args.name, process)
        events, other = read_log(root / "follower.jsonl")
        fresh = events[before:]
        # Match follower_problem's diagnostic policy: read_log already excludes expected SLF4J
        # startup output and CPU-starvation warnings. Keep the original transcript untouched.
        failures.extend(
            f"round {round_}: follower error: {event.get('message', event)}"
            for event in fresh if event["event"] == "error"
        )
        failures.extend(
            f"round {round_}: unexpected follower output: {line[:200]}"
            for line in other[other_before:]
        )
        stops.append(round(elapsed, 1))
        problem = stop_problem(elapsed, any(e["event"] == "shutdownSnapshot" for e in fresh))
        if problem:
            failures.append(f"round {round_}: {problem}")
        if round_ == 2:
            problem = restore_problem(fresh)
            if problem:
                failures.append(problem)
    problem = format_problem([f.read_bytes()[:8] for f in sorted((root / "db" / "snapshots").glob("snapshot-*.bin"))])
    if problem:
        failures.append(problem)
    print(json.dumps({"stopSeconds": stops, "failed": failures}), flush=True)
    sys.exit(1 if failures else 0)
