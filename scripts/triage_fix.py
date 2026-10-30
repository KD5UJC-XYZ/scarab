#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fix the mechanical drift of the issue tracker, so that nobody has to remember to (owner decision, 2026-10-29).

    python3 scripts/triage_fix.py                                  print the plan (read-only; the default)
    python3 scripts/triage_fix.py --apply --enable closed-status   apply the enabled fixes, report the rest
    python3 scripts/triage_fix.py --from issues.json --prs prs.json   plan over saved `gh api` listings

The fixes, each one an edit a person would make without judgement:

  closed-status  a closed issue loses `status:in-progress`, `status:blocked` and `status:needs-owner`
  form-labels    an open issue filed through an issue form takes its Area and Priority answers as labels, and
                 `status:backlog` when it has no status (#605)
  empty-pr       an open pull request whose merge into `main` changes nothing gets a comment; nothing is closed
  milestone      an open issue with a status other than idea or parked and no milestone gets one: the bucket a word
                 of its title names (`mainnet` -> mainnet-evidence; `leios`, `dijkstra` -> leios-dijkstra), otherwise
                 reference-parity
  stale-work     an open `status:in-progress` issue quiet for 7 days is flagged with a comment (its labels stay):
                 not when an open pull request or a branch names it, it has a comment within the 7 days, or it is
                 also `status:needs-owner`

Guards, so that a person's decision always wins:
  - an issue labelled `triage:hold` is never changed;
  - a fix is never made again once a person undid one of its kind on that issue: the issue's timeline shows a
    person removing a label after the bot added it, adding one back after the bot removed it, or changing or
    clearing a milestone after the bot set it;
  - each issue gets at most one comment a run, listing every fix and how to undo it.

Rollout is staged: `--enable` (the workflow passes the repository variable `SCARAB_TRIAGE_FIXES`) names the fixes
that `--apply` carries out; every other fix is only reported, so a fix runs in report-only mode until it is enabled.
The plan is pure (`plan`), so a dry run shows exactly what would happen. Pure standard library; the network use is
`gh api` (GETs to plan; label, milestone and comment writes with `--apply`) and `git` for the empty-PR test.
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
from enum import Enum
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from status import AREAS, PRIORITIES, WITHOUT_MILESTONE, label_names, updated

STALE_AFTER = timedelta(days=7)
HOLD = "triage:hold"
ENDED_ON_CLOSE = ("status:in-progress", "status:blocked", "status:needs-owner")
DEFAULT_MILESTONE = "reference-parity"
MILESTONE_WORDS = (("mainnet-evidence", re.compile(r"\bmainnet\b", re.IGNORECASE)),
                   ("leios-dijkstra", re.compile(r"\b(?:leios|dijkstra)\b", re.IGNORECASE)))
FORM_ANSWER = re.compile(r"^###\s+(Area|Priority)\s*\n+(.+?)\s*$", re.MULTILINE)
EMPTY_PR_MARKER = "<!-- triage:empty-pr -->"
COMMENT_HEAD = "<!-- triage:fixes -->\nThe nightly triage (`scripts/triage_fix.py`) changed this issue:"
COMMENT_TAIL = f"Add the `{HOLD}` label and the nightly triage leaves this issue alone; an undone fix is not made again."


class Fix(Enum):
    CLOSED_STATUS = "closed-status"
    FORM_LABELS = "form-labels"
    EMPTY_PR = "empty-pr"
    MILESTONE = "milestone"
    STALE_WORK = "stale-work"


@dataclass(frozen=True)
class Action:
    fix: Fix
    number: int
    add: tuple[str, ...] = ()
    remove: tuple[str, ...] = ()
    milestone: str | None = None
    note: str = ""

    def describe(self) -> str:
        parts = [f"+{', +'.join(self.add)}" if self.add else "", f"-{', -'.join(self.remove)}" if self.remove else "",
                 f"milestone {self.milestone}" if self.milestone else "", "flag" if self.fix is Fix.STALE_WORK else ""]
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


def names_issue(text: str, number: int) -> bool:
    return re.search(rf"(?:#|issues/){number}(?!\d)", text) is not None


def branch_names_issue(branch: str, number: int) -> bool:
    return re.search(rf"(?<!\d){number}(?!\d)", branch) is not None


def bucket(title: str) -> str:
    return next((name for name, word in MILESTONE_WORDS if word.search(title)), DEFAULT_MILESTONE)


def is_person(event: dict) -> bool:
    login = (event.get("actor") or {}).get("login", "")
    return bool(login) and not login.endswith("[bot]")


def undone(action: Action, events: Sequence[dict]) -> bool:
    """Whether a person has undone a fix of this kind on this issue: after the bot added (removed) a label, a person
    removed (added) it, or after the bot set a milestone, a person changed or cleared it (from its timeline events)."""
    ordered = sorted(events, key=lambda e: e.get("created_at", ""))

    def reverted(bot_kind: str, person_kinds: tuple[str, ...], name: str | None) -> bool:
        bot_done = False
        for e in ordered:
            same = name is None or (e.get("label") or {}).get("name") == name
            if not same:
                continue
            if not is_person(e) and e.get("event") == bot_kind:
                bot_done = True
            elif bot_done and is_person(e) and e.get("event") in person_kinds:
                return True
        return False
    return any(reverted("labeled", ("unlabeled",), l) for l in action.add) \
        or any(reverted("unlabeled", ("labeled",), l) for l in action.remove) \
        or (action.milestone is not None and reverted("milestoned", ("milestoned", "demilestoned"), None))


def commented_since(events: Sequence[dict], since: datetime) -> bool:
    return any(e.get("event") == "commented" and e.get("created_at", "") >= since.strftime("%Y-%m-%dT%H:%M:%SZ")
               for e in events)


def candidates(issues: Sequence[dict], prs: Sequence[dict], branches: Sequence[str], now: datetime,
               empty: Callable[[dict], bool]) -> list[Action]:
    """Every fix the listings call for, before the timeline guards."""
    actions: list[Action] = []
    pr_text = [f"{p.get('title') or ''} {p.get('body') or ''}" for p in prs]
    refs = [(p.get("head") or {}).get("ref", "") for p in prs] + list(branches)
    for issue in sorted(issues, key=lambda i: int(i["number"])):
        number, labels = int(issue["number"]), label_names(issue)
        if HOLD in labels:
            continue
        statuses = [l for l in labels if l.startswith("status:")]
        if issue["state"] == "closed":
            ended = tuple(l for l in ENDED_ON_CLOSE if l in labels)
            if ended:
                actions.append(Action(Fix.CLOSED_STATUS, number, remove=ended,
                                      note=f"removed {', '.join(f'`{l}`' for l in ended)}, which claim work on a closed "
                                           "issue (undo: add the label back)"))
            continue
        add: list[str] = []
        if not any(l.startswith("area:") for l in labels) or not any(l.startswith("priority:") for l in labels):
            has_priority = any(l.startswith("priority:") for l in labels)
            add = [l for l in form_answers(issue.get("body") or "")
                   if l not in labels and not (l.startswith("priority:") and has_priority)]
            if add and not statuses:
                add.append("status:backlog")
        if add:
            actions.append(Action(Fix.FORM_LABELS, number, add=tuple(add),
                                  note=f"added {', '.join(f'`{l}`' for l in add)} from the issue form's answers "
                                       "(undo: remove the label)"))
            statuses = statuses or ["status:backlog"]
        named = any(names_issue(t, number) for t in pr_text) or any(branch_names_issue(r, number) for r in refs)
        if "status:in-progress" in statuses and "status:needs-owner" not in statuses and not named \
                and now - updated(issue) > STALE_AFTER:
            actions.append(Action(Fix.STALE_WORK, number, note=(
                "flagged: `status:in-progress` with no open pull request or branch naming it and no activity for 7 days. "
                "If the work has stopped, set `status:backlog`; if it goes on, nothing to do")))
        if issue.get("milestone") is None and statuses and \
                not {s.removeprefix("status:") for s in statuses} & WITHOUT_MILESTONE:
            chosen = bucket(issue["title"])
            why = "a word of its title" if chosen != DEFAULT_MILESTONE else "the default bucket"
            actions.append(Action(Fix.MILESTONE, number, milestone=chosen,
                                  note=f"set the milestone to `{chosen}` ({why}; undo: change or clear it)"))
    for pr in sorted(prs, key=lambda p: int(p["number"])):
        if empty(pr):
            actions.append(Action(Fix.EMPTY_PR, int(pr["number"]), note=(
                f"{EMPTY_PR_MARKER}merging this pull request into `main` changes nothing: its changes have landed another "
                "way (for example through a squash merge of a pull request stacked on it). Close it if nothing is left")))
    return actions


def plan(issues: Sequence[dict], prs: Sequence[dict], now: datetime, empty: Callable[[dict], bool] = lambda pr: False,
         branches: Sequence[str] = (), timeline: Callable[[int], Sequence[dict]] = lambda n: ()) -> list[Action]:
    """The fixes, after the guards: a person's undo, and for stale work a recent comment, keep a fix from being made."""
    kept = []
    for action in candidates(issues, prs, branches, now, empty):
        events = timeline(action.number) if action.fix is not Fix.EMPTY_PR else ()
        if undone(action, events):
            continue
        if action.fix is Fix.STALE_WORK and commented_since(events, now - STALE_AFTER):
            continue
        kept.append(action)
    return kept


def comment_for(actions: Sequence[Action]) -> str:
    """The one comment of a run on one issue: every fix with its undo, then how to opt out."""
    return "\n".join([COMMENT_HEAD, *[f"- {a.note}." for a in actions], "", COMMENT_TAIL])


Run = Callable[[Sequence[str]], str]


def gh(args: Sequence[str]) -> str:
    done = subprocess.run(["gh", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:2])}: {done.stderr.strip()}")
    return done.stdout


def apply(actions: Sequence[Action], run: Run) -> None:
    """Carry out the actions: the label and milestone edits, then one comment per issue."""
    by_issue: dict[int, list[Action]] = defaultdict(list)
    for action in actions:
        by_issue[action.number].append(action)
    for number, group in sorted(by_issue.items()):
        n = str(number)
        add = [l for a in group for l in a.add]
        remove = [l for a in group for l in a.remove]
        if add:
            run(["issue", "edit", n, *[x for label in add for x in ("--add-label", label)]])
        if remove:
            run(["issue", "edit", n, *[x for label in remove for x in ("--remove-label", label)]])
        for action in group:
            if action.milestone:
                run(["issue", "edit", n, "--milestone", action.milestone])
        run(["issue", "comment", n, "--body", comment_for(group)])


def listing(path: str, run: Run) -> list[dict]:
    pages = json.loads(run(["api", "--paginate", "--slurp", path]) or "[]")
    return [item for page in pages for item in (page if isinstance(page, list) else [page])]


def git_run(args: Sequence[str]) -> str:
    done = subprocess.run(["git", *args], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(f"git {args[0]}: {done.stderr.strip()}")
    return done.stdout


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


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--apply", action="store_true", help="carry out the enabled fixes (default: print the plan)")
    parser.add_argument("--enable", default="", help="comma-separated fixes --apply carries out: "
                        + ", ".join(f.value for f in Fix) + " (empty: report only)")
    parser.add_argument("--from", dest="source", type=Path, help="issues: a saved `gh api --slurp` listing")
    parser.add_argument("--prs", type=Path, help="open pull requests: a saved `gh api --slurp` listing")
    args = parser.parse_args(argv)
    enabled = {Fix(v.strip()) for v in args.enable.split(",") if v.strip()}
    now = datetime.now(timezone.utc)
    if args.source:
        flat = lambda path: [i for p in json.loads(path.read_text()) for i in (p if isinstance(p, list) else [p])]
        actions = plan(flat(args.source), flat(args.prs) if args.prs else [], now)
    else:
        issues = listing("repos/{owner}/{repo}/issues?state=open&per_page=100", gh)
        issues += list({i["number"]: i for label in ENDED_ON_CLOSE for i in listing(
            f"repos/{{owner}}/{{repo}}/issues?state=closed&labels={label}&per_page=100", gh)}.values())
        issues = [i for i in issues if "pull_request" not in i]
        prs = listing("repos/{owner}/{repo}/pulls?state=open&per_page=100", gh)
        for pr in prs:
            pr["_comments"] = [c.get("body") for c in listing(f"repos/{{owner}}/{{repo}}/issues/{pr['number']}/comments?per_page=100", gh)]
        branches = [b["name"] for b in listing("repos/{owner}/{repo}/branches?per_page=100", gh)]
        actions = plan(issues, prs, now, lambda pr: merge_is_empty(pr, git_run), branches,
                       lambda n: listing(f"repos/{{owner}}/{{repo}}/issues/{n}/timeline?per_page=100", gh))
    applying = [a for a in actions if args.apply and a.fix in enabled]
    for action in actions:
        print(("applied   " if action in applying else "report    ") + action.describe())
    if applying:
        apply(applying, gh)
    print(f"triage fixes: {len(applying)} applied, {len(actions) - len(applying)} reported only"
          f" (enabled: {', '.join(sorted(f.value for f in enabled)) or 'none'})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
