# SPDX-License-Identifier: Apache-2.0
"""The `restart` scenario: a graceful stop ends the JVM promptly and writes no snapshot, and the next start
replays from the newest periodic snapshot, as the reference does (ouroboros-consensus 82ecba32 `closeDB`,
Storage/ChainDB/Impl.hs:408-436; `initialize`, Storage/LedgerDB/API.hs:599-645)."""
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
# seconds the first run may take to write its first periodic snapshot (the immutable tip must pass the
# first epoch boundary: about a minute after the start of a fresh cluster)
SNAPSHOT_WAIT = 300


def stop_problem(elapsed, closed):
    """Why a graceful stop failed (taking `elapsed` seconds; `closed`: the database logged `closedDB`), else None."""
    if elapsed >= STOP_BUDGET:
        return f"the node needed {elapsed:.1f} s to stop (budget {STOP_BUDGET} s): the JVM did not exit on SIGTERM"
    if not closed:
        return "the node stopped without closing its database (no closedDB)"
    return None


def written_problem(before, after):
    """Why the stop wrote a snapshot, else None: `before` and `after` are the snapshot files before and after it."""
    added = sorted(set(after) - set(before))
    if added:
        return f"the stop wrote {', '.join(added)}: only the snapshot policy writes snapshots"
    return None


def first(events, name):
    return next((e for e in events if e["event"] == name), None)


def restore_problem(events, newest, closed):
    """Why the second start did not replay from the first one's newest snapshot to its immutable tip, else None.

    `newest` is the first run's last `snapshotWritten` and `closed` its `closedDB`.
    """
    if newest is None:
        return "the first run wrote no periodic snapshot"
    restored, started, finished = (first(events, n) for n in ("snapshotRestored", "replayStarted", "replayFinished"))
    if restored is None:
        return "the restart did not restore a snapshot"
    if restored["file"] != newest["file"]:
        return f"the restart restored {restored['file']}, not the newest snapshot {newest['file']}"
    if started is None or started["from"] != restored["anchor"]:
        return f"the replay did not start from the restored snapshot: {started}"
    if closed is None or finished is None or finished["tip"] != closed["immutableTip"]:
        return f"the replay did not end at the immutable tip the stop logged: {finished}"
    return None


# the magic of the snapshot files this build writes (FileKind.SnapshotFile current, SnapshotV9)
SNAPSHOT_MAGIC = b"SCRBSNP9"


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


def _snapshot_files(root):
    return sorted((root / "db" / "snapshots").glob("snapshot-*.bin"))


def restart(args):
    """Follow with current V9 snapshots until the snapshot policy writes one, SIGTERM, then replay from it."""
    root = data_root(args.name)
    up(args)
    failures, stops = [], []
    newest = closed = replayed = None
    for round_ in (1, 2):
        prior_events, prior_other = read_log(root / "follower.jsonl")
        before, other_before = len(prior_events), len(prior_other)
        process = _start(args)
        if not _wait_for(root, "extended", 120, before):
            failures.append(f"round {round_}: no block followed within 120 s")
            docker("rm", "-f", f"{args.name}-scala", check=False)
            process.wait()
            break
        # the first run stops after a periodic snapshot (`--snapshot-every 1epochs` in the harness), with
        # blocks after it, so that the second start has blocks to replay from it
        if round_ == 1 and not _wait_for(root, "snapshotWritten", SNAPSHOT_WAIT, before):
            failures.append(f"round 1: no periodic snapshot within {SNAPSHOT_WAIT} s")
            docker("rm", "-f", f"{args.name}-scala", check=False)
            process.wait()
            break
        time.sleep(args.seconds)
        files_before = [f.name for f in _snapshot_files(root)]
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
        closing = [e for e in fresh if e["event"] == "closedDB"]
        for problem in (stop_problem(elapsed, bool(closing)),
                        written_problem(files_before, [f.name for f in _snapshot_files(root)])):
            if problem:
                failures.append(f"round {round_}: {problem}")
        if round_ == 1:
            newest = next((e for e in reversed(fresh) if e["event"] == "snapshotWritten"), None)
            closed = closing[-1] if closing else None
        else:
            problem = restore_problem(fresh, newest, closed)
            if problem:
                failures.append(problem)
            replayed = (first(fresh, "replayFinished") or {}).get("replayed")
    problem = format_problem([f.read_bytes()[:8] for f in _snapshot_files(root)])
    if problem:
        failures.append(problem)
    print(json.dumps({"stopSeconds": stops, "restored": newest and newest["file"], "replayed": replayed,
                      "failed": failures}), flush=True)
    sys.exit(1 if failures else 0)
