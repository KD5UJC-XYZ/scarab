#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Merge the nightly roadmap-sync pull request, and only that one, once it is green (owner decision, 2026-10-29).

    python3 scripts/roadmap_sync.py merge --app-slug scarab-sync            merge if every condition holds
    python3 scripts/roadmap_sync.py merge --app-slug scarab-sync --dry-run  say what it would do

The triage workflow runs it with the sync App's token, on its schedule or a manual dispatch only. It merges the open
pull request from `bot/roadmap-sync` when all of these hold, and otherwise leaves it for a person:

  - its author is the sync App (`app/<slug>`, `<slug>[bot]`);
  - it changes `docs/data/issue-states.json`, `docs/roadmap.md` or both, and no other file;
  - every check on its head commit has completed and passed (or was skipped), and there is at least one;
  - GitHub reports it mergeable.

The merge is a squash pinned to the head commit it checked (`--match-head-commit`), so a push in between cannot ride
along. The repository's auto-merge setting is not used: no other pull request can be merged this way. Exit 0 whether
or not it merged (the reason is printed), 2 when `gh` fails.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections.abc import Callable, Sequence
from dataclasses import dataclass

REPOSITORY = "KD5UJC-XYZ/scarab"
BRANCH = "bot/roadmap-sync"
SYNC_FILES = frozenset({"docs/data/issue-states.json", "docs/roadmap.md"})
PASSED = frozenset({"SUCCESS", "SKIPPED", "NEUTRAL"})

Run = Callable[[Sequence[str]], str]


@dataclass(frozen=True)
class Verdict:
    merge: bool
    reason: str


def verdict(pr: dict, slug: str) -> Verdict:
    """Whether this pull request (`gh pr view --json author,files,statusCheckRollup,mergeable,headRefName`) may merge."""
    login = (pr.get("author") or {}).get("login", "")
    if pr.get("headRefName") != BRANCH:
        return Verdict(False, f"not the {BRANCH} branch")
    if login not in (f"app/{slug}", f"{slug}[bot]"):
        return Verdict(False, f"author {login!r} is not the sync App")
    files = {f["path"] for f in pr.get("files") or []}
    if not files or not files <= SYNC_FILES:
        return Verdict(False, "changes files other than the snapshot and the render: " + ", ".join(sorted(files - SYNC_FILES)))
    checks = pr.get("statusCheckRollup") or []
    if not checks:
        return Verdict(False, "no checks have run")
    for check in checks:
        name = check.get("name") or check.get("context")
        if (check.get("status") or "COMPLETED") != "COMPLETED":
            return Verdict(False, f"check {name!r} is pending")
        state = (check.get("conclusion") or check.get("state") or "").upper()
        if state not in PASSED:
            return Verdict(False, f"check {name!r} is {state or 'without a conclusion'}")
    if pr.get("mergeable") != "MERGEABLE":
        return Verdict(False, f"GitHub reports it {pr.get('mergeable')}")
    return Verdict(True, "the sync App's snapshot and render only, every check passed")


def gh(args: Sequence[str]) -> str:
    done = subprocess.run(["gh", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:2])}: {done.stderr.strip()}")
    return done.stdout


def merge(slug: str, dry_run: bool, run: Run = gh) -> str:
    listed = json.loads(run(["pr", "list", "--repo", REPOSITORY, "--head", BRANCH, "--state", "open", "--json", "number"]))
    if not listed:
        return "roadmap sync: no open sync pull request"
    number = str(listed[0]["number"])
    pr = json.loads(run(["pr", "view", number, "--repo", REPOSITORY, "--json",
                         "author,files,statusCheckRollup,mergeable,headRefName,headRefOid"]))
    decided = verdict(pr, slug)
    if not decided.merge:
        return f"roadmap sync: #{number} left open: {decided.reason}"
    if dry_run:
        return f"roadmap sync: would merge #{number}: {decided.reason}"
    run(["pr", "merge", number, "--repo", REPOSITORY, "--squash", "--match-head-commit", pr["headRefOid"]])
    return f"roadmap sync: merged #{number}: {decided.reason}"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("command", choices=["merge"])
    parser.add_argument("--app-slug", required=True, help="the sync App's slug")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        print(merge(args.app_slug, args.dry_run))
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
