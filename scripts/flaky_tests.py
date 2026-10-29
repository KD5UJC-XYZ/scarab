#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Find tests that failed and then passed on the same commit, and track each one in an issue (owner decision, 2026-10-29).

    python3 scripts/flaky_tests.py                 print what it found (read-only; the default)
    python3 scripts/flaky_tests.py --apply         open or update the issues (the nightly flaky-test workflow does)
    python3 scripts/flaky_tests.py --hours 72      look further back than the default 26 hours

A commit is flaky when a CI run on it failed and another run, or another attempt of the same run, passed. For each
failed run of such a commit, the failing tests are the `==> X <suite>.<test>` lines of MUnit in its failed jobs' logs.
Each test gets one open issue titled `Flaky: <Suite> "<test>"`, labelled `bug`, `area:` the module whose tests hold
the suite, `priority:P2` and `status:backlog`, in `reference-parity`; an issue that already exists for the suite (an
open issue whose title names the suite and says "flak") gets a comment with the run instead, once per run. A failure
is skipped when an issue whose title names the suite was closed after the failed run: the fix has landed since.

Pure standard library; the network use is `gh api` (GETs of runs, jobs, logs and issues; with `--apply`, issue
creation and comments).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "KD5UJC-XYZ/scarab"
WORKFLOW = "ci.yml"
FAILURE = re.compile(r"==> X (xyz\.kd5ujc\.scarab\.[\w.]*?(\w+Suite))\.(.+?)\s{2,}\d+(?:\.\d+)?s")
ANSI = re.compile(r"\x1b\[[0-9;]*m")

Run = Callable[[Sequence[str]], str]


@dataclass(frozen=True)
class Flake:
    suite: str
    test: str
    run_url: str
    sha: str
    run_at: str = ""


def gh(args: Sequence[str]) -> str:
    done = subprocess.run(["gh", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:2])}: {done.stderr.strip()}")
    return done.stdout


def failures(log: str) -> list[tuple[str, str]]:
    """The (suite, test) pairs MUnit reports as failed in a job log."""
    found = []
    for line in ANSI.sub("", log).splitlines():
        match = FAILURE.search(line)
        if match and (match.group(2), match.group(3).strip()) not in found:
            found.append((match.group(2), match.group(3).strip()))
    return found


def flaky_runs(runs: Sequence[dict]) -> list[dict]:
    """The failed runs and attempts of commits on which a run or an attempt also passed."""
    by_sha: dict[str, list[dict]] = defaultdict(list)
    for run in runs:
        by_sha[run["head_sha"]].append(run)
    flaky = []
    for sha, group in by_sha.items():
        passed = any(r.get("conclusion") == "success" for r in group)
        failed = [r for r in group if r.get("conclusion") == "failure" or r.get("first_attempt") == "failure"]
        if passed and failed:
            flaky += failed
    return sorted(flaky, key=lambda r: r["id"])


def suite_modules(test_files: Sequence[str]) -> dict[str, str]:
    """Suite name -> module, from the tracked test sources (`<module>/src/test/.../<Suite>.scala`)."""
    return {Path(f).stem: f.split("/")[0] for f in test_files if "/src/test/" in f and f.endswith("Suite.scala")}


def fixed_since(flake: Flake, run_at: str, closed_issues: Sequence[dict]) -> bool:
    return any(flake.suite in i["title"] and (i.get("closed_at") or "") > run_at for i in closed_issues)


def title_of(suite: str, test: str) -> str:
    return f'Flaky: {suite} "{test[:90]}"'


def existing_issue(suite: str, open_issues: Sequence[dict]) -> dict | None:
    return next((i for i in open_issues if suite in i["title"] and "flak" in i["title"].lower()), None)


def body_of(flake: Flake) -> str:
    return (f"**What.** `{flake.suite}` \"{flake.test}\" failed and then passed on the same commit `{flake.sha[:9]}` "
            f"([failed run]({flake.run_url})), so its outcome does not depend on the change under test alone.\n\n"
            "**Acceptance.** The test's outcome depends only on the code under test: an observable condition or a "
            "controlled schedule instead of a timing assumption (AGENTS.md, concurrency tests), and a regression run "
            "of the suite under load. Filed by the nightly flaky-test workflow (`scripts/flaky_tests.py`).")


def recurrence(flake: Flake) -> str:
    return f"Again on `{flake.sha[:9]}`: \"{flake.test}\" failed, then passed on the same commit ([failed run]({flake.run_url}))."


@dataclass(frozen=True)
class Step:
    """Open an issue (`number` None) or comment on one."""
    flake: Flake
    number: int | None
    title: str
    labels: tuple[str, ...]


def plan(flakes: Sequence[Flake], open_issues: Sequence[dict], commented: Callable[[int, str], bool],
         modules: dict[str, str], closed_issues: Sequence[dict] = ()) -> list[Step]:
    steps: list[Step] = []
    opened: dict[str, Step] = {}
    for flake in flakes:
        if fixed_since(flake, flake.run_at, closed_issues):
            continue
        found = existing_issue(flake.suite, open_issues)
        if found is not None:
            if not commented(found["number"], flake.run_url) and \
                    not any(s.number == found["number"] and s.flake.run_url == flake.run_url for s in steps):
                steps.append(Step(flake, found["number"], found["title"], ()))
        elif flake.suite not in opened:
            area = modules.get(flake.suite)
            labels = ("bug", f"area:{area}" if area else "area:scripts", "priority:P2", "status:backlog")
            opened[flake.suite] = Step(flake, None, title_of(flake.suite, flake.test), labels)
            steps.append(opened[flake.suite])
    return steps


def collect(hours: int, run: Run) -> list[Flake]:
    since = (datetime.now(timezone.utc) - timedelta(hours=hours)).strftime("%Y-%m-%dT%H:%M:%SZ")
    pages = json.loads(run(["api", "--paginate", "--slurp",
                            f"repos/{REPOSITORY}/actions/workflows/{WORKFLOW}/runs?created=>{since}&per_page=100"]))
    runs = [r for page in pages for r in page["workflow_runs"] if r.get("status") == "completed"]
    for r in runs:
        if r.get("run_attempt", 1) > 1:
            first = json.loads(run(["api", f"repos/{REPOSITORY}/actions/runs/{r['id']}/attempts/1"]))
            r["first_attempt"] = first.get("conclusion")
    flakes = []
    for r in flaky_runs(runs):
        attempt = 1 if r.get("first_attempt") == "failure" and r.get("conclusion") == "success" else r.get("run_attempt", 1)
        jobs = json.loads(run(["api", f"repos/{REPOSITORY}/actions/runs/{r['id']}/attempts/{attempt}/jobs"]))["jobs"]
        for job in (j for j in jobs if j.get("conclusion") == "failure"):
            for suite, test in failures(run(["api", f"repos/{REPOSITORY}/actions/jobs/{job['id']}/logs"])):
                flakes.append(Flake(suite, test, r["html_url"], r["head_sha"], r.get("updated_at", "")))
    return flakes


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--apply", action="store_true", help="open or update the issues (default: print the plan)")
    parser.add_argument("--hours", type=int, default=26, help="how far back to read CI runs (default 26)")
    args = parser.parse_args(argv)
    flakes = collect(args.hours, gh)
    open_issues = json.loads(gh(["api", "--paginate", "--slurp", f"repos/{REPOSITORY}/issues?state=open&per_page=100"]))
    open_issues = [i for page in open_issues for i in page if "pull_request" not in i]
    since = (datetime.now(timezone.utc) - timedelta(hours=args.hours)).strftime("%Y-%m-%dT%H:%M:%SZ")
    closed = json.loads(gh(["api", "--paginate", "--slurp",
                            f"repos/{REPOSITORY}/issues?state=closed&since={since}&per_page=100"]))
    closed = [i for page in closed for i in page if "pull_request" not in i]

    def commented(number: int, url: str) -> bool:
        return url in gh(["api", "--paginate", f"repos/{REPOSITORY}/issues/{number}/comments", "--jq", ".[].body"])
    tracked = subprocess.run(["git", "-C", str(ROOT), "ls-files"], capture_output=True, text=True, check=True).stdout
    steps = plan(flakes, open_issues, commented, suite_modules(tracked.split()), closed)
    for step in steps:
        what = f"comment on #{step.number}" if step.number else f"open {step.title} [{', '.join(step.labels)}]"
        print(("applied " if args.apply else "plan    ") + what + f" ({step.flake.run_url})")
        if not args.apply:
            continue
        if step.number:
            gh(["issue", "comment", str(step.number), "--repo", REPOSITORY, "--body", recurrence(step.flake)])
        else:
            gh(["issue", "create", "--repo", REPOSITORY, "--title", step.title, "--body", body_of(step.flake),
                *[x for label in step.labels for x in ("--label", label)], "--milestone", "reference-parity"])
    print(f"flaky tests: {len(flakes)} failures on {len({f.sha for f in flakes})} commits, {len(steps)} steps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
