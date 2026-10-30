#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Dispatch the E2E devnet workflow without cancelling a run that is already waiting.

    python3 scripts/dispatch_e2e.py --ref claude/603-jump-session-state
    python3 scripts/dispatch_e2e.py --ref main --timeout 7200 --interval 60

The workflow's concurrency group (`e2e`, `cancel-in-progress: false`) keeps one run going and one waiting; a new
dispatch replaces the waiting run, which GitHub cancels. This script waits until no E2E run is queued, waiting,
pending or requested, then dispatches on `--ref` and prints the new run's URL. A running run does not hold it back:
the new one waits behind it in the group. Exit 0 on dispatch, 1 on `--timeout`, 2 when `gh` fails.

Pure standard library; the network use is `gh api` GETs and one `gh workflow run`.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from collections.abc import Callable, Sequence

REPOSITORY = "KD5UJC-XYZ/scarab"
WORKFLOW = "e2e.yml"
WAITING = ("queued", "waiting", "pending", "requested")

Run = Callable[[Sequence[str]], str]


def gh(args: Sequence[str]) -> str:
    done = subprocess.run(["gh", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:2])}: {done.stderr.strip()}")
    return done.stdout


def waiting_runs(run: Run) -> list[int]:
    """The E2E runs that a new dispatch would cancel."""
    found = []
    for status in WAITING:
        page = json.loads(run(["api", f"repos/{REPOSITORY}/actions/workflows/{WORKFLOW}/runs?status={status}&per_page=20"]))
        found += [r["id"] for r in page.get("workflow_runs", [])]
    return sorted(set(found))


def dispatch_when_free(ref: str, timeout: float, interval: float, run: Run = gh,
                       sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.monotonic) -> bool:
    """Wait until no run is waiting, then dispatch; False when `timeout` seconds pass first."""
    deadline = clock() + timeout
    while True:
        blocking = waiting_runs(run)
        if not blocking:
            run(["workflow", "run", WORKFLOW, "--repo", REPOSITORY, "--ref", ref])
            return True
        if clock() >= deadline:
            return False
        print(f"e2e: waiting for run(s) {', '.join(map(str, blocking))} to start before dispatching", file=sys.stderr)
        sleep(interval)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--ref", required=True, help="the branch or tag to run the scenarios on")
    parser.add_argument("--timeout", type=float, default=3 * 3600, help="seconds to wait at most (default 3 hours)")
    parser.add_argument("--interval", type=float, default=60, help="seconds between checks (default 60)")
    args = parser.parse_args(argv)
    try:
        if not dispatch_when_free(args.ref, args.timeout, args.interval):
            print("e2e: timed out; nothing dispatched", file=sys.stderr)
            return 1
        time.sleep(5)
        latest = json.loads(gh(["api", f"repos/{REPOSITORY}/actions/workflows/{WORKFLOW}/runs?event=workflow_dispatch&per_page=1"]))
        print(next(iter(r["html_url"] for r in latest.get("workflow_runs", [])), "e2e: dispatched"))
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
