# SPDX-License-Identifier: Apache-2.0
"""The `restart` scenario: a graceful stop ends the JVM promptly, and the next start restores the snapshot."""
import json
import subprocess
import sys
import time
from ..cluster import up
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


def migration_problem(events, source="V4"):
    """Why the start did not migrate the snapshots written by the older build (from `source` to V5), else None."""
    migrated = [e for e in events if e["event"] == "formatMigrated" and e.get("kind") == "SnapshotFile"]
    if not any(e.get("from") == source and e.get("to") == "V5" and e.get("count", 0) > 0 for e in migrated):
        return f"the start did not migrate the {source} snapshots to V5 (formatMigrated: {migrated})"
    if any(e["event"] == "snapshotRefused" for e in events):
        return "a snapshot was refused"
    return None


def _start(name, classes=None):
    command = [sys.executable, str(ENTRY), "--name", name, "follow", "--db"]
    if classes:
        command += ["--classes", classes]
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
    """Fresh cluster; follow --db, SIGTERM, assert a prompt exit; follow --db again, assert the snapshot is restored.

    With --migrate-from, a run of the older build comes first, and the first run of this build must migrate what
    it wrote. At the end every snapshot file is of the current format.
    """
    root = data_root(args.name)
    up(args)
    failures, stops = [], []
    rounds = [(0, args.migrate_from)] if args.migrate_from else []
    rounds += [(1, None), (2, None)]
    for round_, classes in rounds:
        before = len(read_log(root / "follower.jsonl")[0])
        process = _start(args.name, classes)
        if not _wait_for(root, "extended", 120, before):
            failures.append(f"round {round_}: no block followed within 120 s")
            docker("rm", "-f", f"{args.name}-scala", check=False)
            process.wait()
            break
        time.sleep(args.seconds)
        elapsed = _stop(args.name, process)
        fresh = read_log(root / "follower.jsonl")[0][before:]
        stops.append(round(elapsed, 1))
        problem = stop_problem(elapsed, any(e["event"] == "shutdownSnapshot" for e in fresh))
        if problem:
            failures.append(f"round {round_}: {problem}")
        if round_ == 1 and args.migrate_from:
            problem = migration_problem(fresh) or restore_problem(fresh)
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
