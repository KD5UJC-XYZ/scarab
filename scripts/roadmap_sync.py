#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Merge the nightly roadmap-sync pull request, and only that one, once it is green (owner decision, 2026-10-29).

    python3 scripts/roadmap_sync.py merge --app-slug scarab-sync            merge if every condition holds
    python3 scripts/roadmap_sync.py merge --app-slug scarab-sync --dry-run  say what it would do
    python3 scripts/roadmap_sync.py environment                             whether the sync job may start

The sync App's private key is a secret of the `roadmap-sync` environment only, which only `main` may deploy from, so a
workflow started from any other branch cannot read it. Naming an environment that does not exist creates it without
that restriction, so the triage workflow first runs `environment` with its own token (reads only) and starts the sync
job only when the environment exists and deploys from `main` alone; otherwise the run says why and skips it.

The triage workflow runs `merge` with the sync App's token, on its schedule or a manual dispatch only. It merges the open
pull request from `bot/roadmap-sync` when all of these hold, and otherwise leaves it for a person:

  - its author is the sync App (`app/<slug>`, `<slug>[bot]`);
  - it changes `docs/data/issue-states.json`, `docs/roadmap.md` or both, and no other file;
  - every check on its head commit has completed and passed (or was skipped), and there is at least one;
  - GitHub reports it mergeable.

GitHub computes `mergeable` lazily: the first read after main moves is often `UNKNOWN`, so the pull request is read
again, a few seconds apart, until GitHub has an answer or the attempts run out.

The merge is a squash pinned to the head commit it checked (`--match-head-commit`), so a push in between cannot ride
along. The repository's auto-merge setting is not used: no other pull request can be merged this way. `--admin` lets
`gh` send the merge for a pull request it reports as blocked; GitHub still decides whether the merge is allowed. Exit 0
whether or not it merged (the reason is printed), 2 when `gh` fails.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass

REPOSITORY = "KD5UJC-XYZ/scarab"
BRANCH = "bot/roadmap-sync"
ENVIRONMENT = "roadmap-sync"
SELECTED_BRANCHES = {"protected_branches": False, "custom_branch_policies": True}
SYNC_FILES = frozenset({"docs/data/issue-states.json", "docs/roadmap.md"})
PASSED = frozenset({"SUCCESS", "SKIPPED", "NEUTRAL"})
MERGEABLE_ATTEMPTS = 6
MERGEABLE_WAIT_SECONDS = 10.0

Run = Callable[[Sequence[str]], str]
Sleep = Callable[[float], None]


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


def environment_verdict(environments: Sequence[dict], branch_policies: Sequence[dict]) -> Verdict:
    """Whether the sync job may name its environment: it exists and only `main` may deploy from it.

    `environments` is the repository's list (`GET /environments`), `branch_policies` the environment's deployment
    branch policies (`GET /environments/<name>/deployment-branch-policies`), empty when it does not exist."""
    found = [e for e in environments if e.get("name") == ENVIRONMENT]
    if not found:
        return Verdict(False, f"the {ENVIRONMENT} environment does not exist")
    if found[0].get("deployment_branch_policy") != SELECTED_BRANCHES:
        return Verdict(False, f"the {ENVIRONMENT} environment does not restrict deployments to selected branches")
    rules = sorted((rule.get("type", "branch"), rule.get("name", "")) for rule in branch_policies)
    if rules != [("branch", "main")]:
        return Verdict(False, f"the {ENVIRONMENT} environment deploys from {rules}, not from main alone")
    return Verdict(True, f"the {ENVIRONMENT} environment deploys from main alone")


def gh(args: Sequence[str]) -> str:
    done = subprocess.run(["gh", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:2])}: {done.stderr.strip()}")
    return done.stdout


def environment(run: Run = gh) -> Verdict:
    listed = json.loads(run(["api", f"repos/{REPOSITORY}/environments", "--jq", ".environments"]))
    if not any(e.get("name") == ENVIRONMENT for e in listed):
        return environment_verdict(listed, [])
    policies = json.loads(run(["api", f"repos/{REPOSITORY}/environments/{ENVIRONMENT}/deployment-branch-policies",
                               "--jq", ".branch_policies"]))
    return environment_verdict(listed, policies)


def view(number: str, run: Run, sleep: Sleep) -> dict:
    """The pull request, read again while GitHub has not yet computed whether it is mergeable."""
    args = ["pr", "view", number, "--repo", REPOSITORY, "--json",
            "author,files,statusCheckRollup,mergeable,headRefName,headRefOid"]
    pr = json.loads(run(args))
    for _ in range(MERGEABLE_ATTEMPTS - 1):
        if pr.get("mergeable") != "UNKNOWN":
            break
        sleep(MERGEABLE_WAIT_SECONDS)
        pr = json.loads(run(args))
    return pr


def merge(slug: str, dry_run: bool, run: Run = gh, sleep: Sleep = time.sleep) -> str:
    listed = json.loads(run(["pr", "list", "--repo", REPOSITORY, "--head", BRANCH, "--state", "open", "--json", "number"]))
    if not listed:
        return "roadmap sync: no open sync pull request"
    number = str(listed[0]["number"])
    pr = view(number, run, sleep)
    decided = verdict(pr, slug)
    if not decided.merge:
        return f"roadmap sync: #{number} left open: {decided.reason}"
    if dry_run:
        return f"roadmap sync: would merge #{number}: {decided.reason}"
    run(["pr", "merge", number, "--repo", REPOSITORY, "--squash", "--admin", "--match-head-commit", pr["headRefOid"]])
    return f"roadmap sync: merged #{number}: {decided.reason}"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    merging = commands.add_parser("merge", help="merge the sync pull request if every condition holds")
    merging.add_argument("--app-slug", required=True, help="the sync App's slug")
    merging.add_argument("--dry-run", action="store_true")
    commands.add_parser("environment", help="whether the sync job may start; writes ok=true|false to $GITHUB_OUTPUT")
    args = parser.parse_args(argv)
    try:
        if args.command == "merge":
            print(merge(args.app_slug, args.dry_run))
        else:
            decided = environment()
            print(f"roadmap sync: {decided.reason}")
            output = os.environ.get("GITHUB_OUTPUT")
            if output:
                with open(output, "a", encoding="utf-8") as stream:
                    stream.write(f"ok={'true' if decided.merge else 'false'}\n")
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
