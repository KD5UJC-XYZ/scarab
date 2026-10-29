#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fix the mechanical drift of the issue tracker, so that nobody has to remember to (owner decision, 2026-10-29).

    python3 scripts/triage_fix.py              print the plan (read-only; the default)
    python3 scripts/triage_fix.py --apply      carry it out with `gh` (the nightly triage workflow does)
    python3 scripts/triage_fix.py --from issues.json --prs prs.json    plan over saved `gh api` listings

The fixes, each one an edit a person would make without judgement:

  form labels    an open issue filed through an issue form takes its Area and Priority answers as labels, and
                 `status:backlog` when it has no status (#605)
  stale work     an open `status:in-progress` issue that no open pull request names and that has had no activity
                 for 7 days moves to `status:backlog`, with a comment that says how to undo it
  milestone      an open issue that is neither `status:idea` nor `status:parked` and has no milestone gets one: the
                 bucket a word of its title names (`mainnet` -> mainnet-evidence; `leios`, `dijkstra` ->
                 leios-dijkstra), otherwise reference-parity; a comment says so
  closed status  a closed issue loses `status:in-progress`, `status:blocked` and `status:needs-owner`, which claim
                 work or a wait that has ended
  empty PRs      an open pull request whose merge into `main` changes nothing (its changes landed another way) gets
                 one comment; nothing is closed

Everything else the triage check reports (`status.py --check`) needs a person and stays in the report. The plan is
pure: `plan` reads listings and returns actions, so a dry run shows exactly what `--apply` would do. Pure standard
library; the network use is `gh api` (GETs to plan; label, milestone and comment writes with `--apply`) and `git`
for the empty-PR test.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from enum import Enum
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from status import AREAS, PRIORITIES, WITHOUT_MILESTONE, label_names, updated

STALE_AFTER = timedelta(days=7)
ENDED_ON_CLOSE = ("status:in-progress", "status:blocked", "status:needs-owner")
DEFAULT_MILESTONE = "reference-parity"
MILESTONE_WORDS = (("mainnet-evidence", re.compile(r"\bmainnet\b", re.IGNORECASE)),
                   ("leios-dijkstra", re.compile(r"\b(?:leios|dijkstra)\b", re.IGNORECASE)))
FORM_ANSWER = re.compile(r"^###\s+(Area|Priority)\s*\n+(.+?)\s*$", re.MULTILINE)
EMPTY_PR_MARKER = "<!-- triage:empty-pr -->"
UNDO_STALE = ("Moved to `status:backlog` by the nightly triage: no open pull request names this issue and it has had "
              "no activity for 7 days. Re-add `status:in-progress` if work on it continues.")


class Fix(Enum):
    FORM_LABELS = "form labels"
    STALE_WORK = "stale work"
    MILESTONE = "milestone"
    CLOSED_STATUS = "closed status"
    EMPTY_PR = "empty PR"


@dataclass(frozen=True)
class Action:
    fix: Fix
    number: int
    add: tuple[str, ...] = ()
    remove: tuple[str, ...] = ()
    milestone: str | None = None
    comment: str | None = None

    def describe(self) -> str:
        parts = [f"+{', +'.join(self.add)}" if self.add else "", f"-{', -'.join(self.remove)}" if self.remove else "",
                 f"milestone {self.milestone}" if self.milestone else "", "comment" if self.comment else ""]
        return f"#{self.number:<5} {self.fix.value}: " + "; ".join(p for p in parts if p)


def form_answers(body: str) -> list[str]:
    """The area and priority labels an issue form's answers name, in the vocabulary only."""
    labels = []
    for field, answer in FORM_ANSWER.findall(body or ""):
        for value in (v.strip() for v in answer.split(",")):
            prefix, _, bare = value.partition(":")
            wanted = AREAS if field == "Area" else PRIORITIES
            if prefix == field.lower() and bare in wanted:
                labels.append(value)
    return labels


def names_issue(pr: dict, number: int) -> bool:
    """A pull request names an issue by `#N` or an issue link in its title or body, or by N in its branch name."""
    text = f"{pr.get('title') or ''} {pr.get('body') or ''}"
    if re.search(rf"(?:#|issues/){number}(?!\d)", text):
        return True
    return re.search(rf"(?<!\d){number}(?!\d)", (pr.get("head") or {}).get("ref", "")) is not None


def bucket(title: str) -> str:
    return next((name for name, word in MILESTONE_WORDS if word.search(title)), DEFAULT_MILESTONE)


def plan(issues: Sequence[dict], prs: Sequence[dict], now: datetime, empty: Callable[[dict], bool] = lambda pr: False
         ) -> list[Action]:
    """The fixes for these issues (open and closed) and open pull requests, in a stable order."""
    actions: list[Action] = []
    for issue in sorted(issues, key=lambda i: int(i["number"])):
        number, labels = int(issue["number"]), label_names(issue)
        statuses = [l for l in labels if l.startswith("status:")]
        if issue["state"] == "closed":
            ended = tuple(l for l in ENDED_ON_CLOSE if l in labels)
            if ended:
                actions.append(Action(Fix.CLOSED_STATUS, number, remove=ended))
            continue
        add = []
        if not any(l.startswith("area:") for l in labels) or not any(l.startswith("priority:") for l in labels):
            answered = form_answers(issue.get("body") or "")
            add += [l for l in answered if l not in labels and
                    not (l.startswith("priority:") and any(x.startswith("priority:") for x in labels))]
            if add and not statuses:
                add.append("status:backlog")
        if add:
            actions.append(Action(Fix.FORM_LABELS, number, add=tuple(add)))
            statuses = statuses or (["status:backlog"] if "status:backlog" in add else [])
        if "status:in-progress" in statuses and now - updated(issue) > STALE_AFTER \
                and not any(names_issue(pr, number) for pr in prs):
            actions.append(Action(Fix.STALE_WORK, number, add=("status:backlog",), remove=("status:in-progress",),
                                  comment=UNDO_STALE))
            statuses = [s for s in statuses if s != "status:in-progress"] + ["status:backlog"]
        if issue.get("milestone") is None and statuses and not {s.removeprefix("status:") for s in statuses} & WITHOUT_MILESTONE:
            chosen = bucket(issue["title"])
            actions.append(Action(Fix.MILESTONE, number, milestone=chosen, comment=(
                f"Milestone set to `{chosen}` by the nightly triage "
                f"({'a word of the title' if chosen != DEFAULT_MILESTONE else 'the default bucket'}). "
                "Change it if the issue belongs to another one.")))
    for pr in sorted(prs, key=lambda p: int(p["number"])):
        if empty(pr):
            actions.append(Action(Fix.EMPTY_PR, int(pr["number"]), comment=(
                f"{EMPTY_PR_MARKER}\nMerging this pull request into `main` changes nothing: its changes have landed "
                "another way (for example through a squash merge of a pull request stacked on it). Close it if nothing "
                "is left to do. The nightly triage leaves this comment once.")))
    return actions


Run = Callable[[Sequence[str]], str]


def gh(args: Sequence[str]) -> str:
    done = subprocess.run(["gh", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:2])}: {done.stderr.strip()}")
    return done.stdout


def listing(path: str, run: Run) -> list[dict]:
    pages = json.loads(run(["api", "--paginate", "--slurp", path]) or "[]")
    return [item for page in pages for item in (page if isinstance(page, list) else [page])]


def merge_is_empty(pr: dict, git: Callable[[Sequence[str]], str]) -> bool:
    """Whether merging the pull request's head into `main` leaves `main`'s tree as it is (no conflict, no change)."""
    if pr.get("draft") or any(EMPTY_PR_MARKER in (c or "") for c in pr.get("_comments", [])):
        return False
    number = int(pr["number"])
    try:
        git(["fetch", "--quiet", "origin", f"pull/{number}/head:refs/triage/pr-{number}"])
        merged = git(["merge-tree", "--write-tree", "origin/main", f"refs/triage/pr-{number}"]).split()[0]
        return merged == git(["rev-parse", "origin/main^{tree}"]).strip()
    except RuntimeError:
        return False


def git_run(args: Sequence[str]) -> str:
    done = subprocess.run(["git", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(f"git {args[0]}: {done.stderr.strip()}")
    return done.stdout


def apply(action: Action, run: Run) -> None:
    number = str(action.number)
    if action.add:
        run(["issue", "edit", number, *[x for label in action.add for x in ("--add-label", label)]])
    if action.remove:
        run(["issue", "edit", number, *[x for label in action.remove for x in ("--remove-label", label)]])
    if action.milestone:
        run(["issue", "edit", number, "--milestone", action.milestone])
    if action.comment:
        run(["issue", "comment", number, "--body", action.comment])


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--apply", action="store_true", help="carry out the plan with gh (default: print it)")
    parser.add_argument("--from", dest="source", type=Path, help="issues: a saved `gh api --slurp` listing")
    parser.add_argument("--prs", type=Path, help="open pull requests: a saved `gh api --slurp` listing")
    args = parser.parse_args(argv)
    now = datetime.now(timezone.utc)
    if args.source:
        issues = [i for p in json.loads(args.source.read_text()) for i in (p if isinstance(p, list) else [p])]
        prs = [i for p in json.loads(args.prs.read_text()) for i in (p if isinstance(p, list) else [p])] if args.prs else []
        empty: Callable[[dict], bool] = lambda pr: False
    else:
        open_issues = listing("repos/{owner}/{repo}/issues?state=open&per_page=100", gh)
        closed = [i for label in ENDED_ON_CLOSE
                  for i in listing(f"repos/{{owner}}/{{repo}}/issues?state=closed&labels={label}&per_page=100", gh)]
        issues = [i for i in open_issues + list({i["number"]: i for i in closed}.values()) if "pull_request" not in i]
        prs = listing("repos/{owner}/{repo}/pulls?state=open&per_page=100", gh)
        for pr in prs:
            pr["_comments"] = [c.get("body") for c in listing(f"repos/{{owner}}/{{repo}}/issues/{pr['number']}/comments?per_page=100", gh)]
        empty = lambda pr: merge_is_empty(pr, git_run)
    actions = plan(issues, prs, now, empty)
    for action in actions:
        print(("applied " if args.apply else "plan    ") + action.describe())
        if args.apply:
            apply(action, gh)
    print(f"triage fixes: {len(actions)} {'applied' if args.apply else 'planned'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
