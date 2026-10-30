#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""On-demand digest of the open issues, one line each, grouped by area, and the triage check. Never committed output.

    python3 scripts/status.py                      all open issues (needs an authenticated `gh`)
    python3 scripts/status.py --area networking    one area (the label without `area:`)
    python3 scripts/status.py --priority P1        one priority (`P1` or `priority:P1`)
    python3 scripts/status.py --json               the digest as JSON instead of text
    python3 scripts/status.py --from issues.json   read the output of
                                                   `gh issue list --json number,title,labels` from a file
    python3 scripts/status.py --check              the triage check: exit 1 and list each open issue that
                                                   breaks the labelling rules of docs/process.md
    python3 scripts/status.py --check --from p.json the check over the output of
                                                   `gh api --paginate --slurp 'repos/{owner}/{repo}/issues?state=open'`
    python3 scripts/status.py --issue 567          what a session needs before it works on issue 567: its state,
                                                   labels and milestone, its roadmap id (or those of the issues it
                                                   cites), the decisions of docs/decisions.md that cite it, the
                                                   playbooks for its areas, a suggested level, and its own triage
                                                   problems

A line shows the number, priority, status label, roadmap id, and the title cut to the terminal width. The
roadmap id comes from `docs/roadmap.md`: the bold id that leads a line whose leading links cite the issue, and
failing that the first line that cites the issue at all. Labels follow docs/process.md: `priority:P0..P2`,
`status:*`, `area:*`.

The check reports, grouped by violation: an open issue missing an `area:`, `priority:` or `status:` label; an
`area:` label that names neither an sbt module nor `build`, `scripts` or `docs`; one without a milestone whose
status is neither `status:idea` nor `status:parked`; one with two `status:` labels; `status:in-progress` not
updated for 7 days and `status:needs-owner` not updated for 14. It exits 0 when clean, 1 on violations and 2 when
`gh` fails. The areas are read from `docs/data/codemap.json` (one per module, as `build.sbt` defines them) and the
rest of the vocabulary is the one docs/process.md lists.

Pure standard library; the only network use is a read-only `gh issue list` or `gh api` GET, and `--from`
avoids it.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from enum import Enum
from pathlib import Path

GH_COMMAND = ["gh", "issue", "list", "--state", "open", "--limit", "1000", "--json", "number,title,labels"]
NO_AREA = "none"
ISSUE_LINK = re.compile(r"issues/(\d+)")
LEADING = re.compile(r"^\s*-\s+\*\*([^*]+)\*\*((?:\s*\[#\d+\]\([^)]*issues/\d+\)\s*,?)*)")

CODEMAP = Path(__file__).resolve().parents[1] / "docs/data/codemap.json"
# Work that lives in no sbt module: the build, CI and releases; the scripts (devnet, tooling, guards); the documents.
OUTSIDE_MODULES = ("build", "scripts", "docs")


def module_areas(codemap: Path = CODEMAP) -> tuple[str, ...]:
    """One area per sbt module of the code map (generated from build.sbt), then the areas outside the modules."""
    modules = json.loads(codemap.read_text(encoding="utf-8"))["modules"]
    return tuple(m if isinstance(m, str) else m["name"] for m in modules) + OUTSIDE_MODULES


AREAS = module_areas()
PRIORITIES = ("P0", "P1", "P2")
STATUSES = ("idea", "backlog", "in-progress", "blocked", "parked", "needs-owner")
MILESTONES = ("reference-parity", "mainnet-evidence", "leios-dijkstra")
WITHOUT_MILESTONE = frozenset({"idea", "parked"})
CHECK_COMMAND = ["gh", "api", "--paginate", "--slurp", "repos/{owner}/{repo}/issues?state=open&per_page=100"]


class GhFailure(Exception):
    """`gh` exited non-zero; the message is its standard error."""


class Kind(Enum):
    MISSING_LABEL = "missing an area, priority or status label"
    UNKNOWN_AREA = "an area label that is neither an sbt module nor build, scripts or docs"
    NO_MILESTONE = "no milestone, and the status is neither idea nor parked"
    TWO_STATUSES = "more than one status label"
    STALE_IN_PROGRESS = "status:in-progress not updated for more than 7 days"
    STALE_NEEDS_OWNER = "status:needs-owner not updated for more than 14 days"


STALE_AFTER = {"in-progress": (Kind.STALE_IN_PROGRESS, timedelta(days=7)),
               "needs-owner": (Kind.STALE_NEEDS_OWNER, timedelta(days=14))}


@dataclass(frozen=True)
class Violation:
    kind: Kind
    number: int
    title: str
    detail: str


@dataclass(frozen=True)
class Digest:
    number: int
    priority: str
    status: str
    area: str
    roadmap: str
    title: str


def roadmap_ids(text: str) -> dict[int, str]:
    """Issue number -> roadmap id, from the lines of docs/roadmap.md."""
    leading: dict[int, str] = {}
    mentioned: dict[int, str] = {}
    for line in text.splitlines():
        head = LEADING.match(line)
        if head is None:
            continue
        for number in ISSUE_LINK.findall(head.group(2)):
            leading.setdefault(int(number), head.group(1).strip())
        for number in ISSUE_LINK.findall(line):
            mentioned.setdefault(int(number), head.group(1).strip())
    return {**mentioned, **leading}


def label_value(labels: list[str], prefix: str, default: str) -> str:
    return next((label[len(prefix):] for label in labels if label.startswith(prefix)), default)


def digest(raw: list[dict], roadmap: dict[int, str]) -> list[Digest]:
    result = []
    for issue in raw:
        labels = [label["name"] if isinstance(label, dict) else label for label in issue.get("labels", [])]
        number = int(issue["number"])
        result.append(Digest(
            number, label_value(labels, "priority:", "-"), label_value(labels, "status:", "-"),
            label_value(labels, "area:", NO_AREA), roadmap.get(number, "-"), issue["title"].strip(),
        ))
    return sorted(result, key=lambda d: (d.area, d.priority, d.number))


def select(items: list[Digest], area: str | None, priority: str | None) -> list[Digest]:
    wanted = priority.removeprefix("priority:").upper() if priority else None
    wanted_area = area.removeprefix("area:") if area else None
    return [d for d in items
            if (wanted is None or d.priority.upper() == wanted) and (wanted_area is None or d.area == wanted_area)]


def render_text(items: list[Digest], width: int) -> str:
    groups: dict[str, list[Digest]] = defaultdict(list)
    for d in items:
        groups[d.area].append(d)
    ids = max((len(d.roadmap) for d in items), default=1)
    statuses = max((len(d.status) for d in items), default=1)
    lines = []
    for area, members in groups.items():
        lines.append(f"{area} ({len(members)})")
        for d in members:
            prefix = f"  #{d.number:<5} {d.priority:<3} {d.status:<{statuses}} {d.roadmap:<{ids}} "
            room = max(width - len(prefix), 10)
            title = d.title if len(d.title) <= room else d.title[:room - 3].rstrip() + "..."
            lines.append(prefix + title)
    return "\n".join(lines)


def label_names(issue: dict) -> list[str]:
    return [label["name"] if isinstance(label, dict) else label for label in issue.get("labels", [])]


def updated(issue: dict) -> datetime:
    return datetime.fromisoformat(issue["updated_at"].replace("Z", "+00:00"))


def check(raw: list[dict], now: datetime) -> list[Violation]:
    """Every violation of the labelling rules, in the order of `Kind`, then by issue number."""
    found = []
    for issue in sorted(raw, key=lambda i: int(i["number"])):
        labels = label_names(issue)
        number, title = int(issue["number"]), issue["title"].strip()
        statuses = [label.removeprefix("status:") for label in labels if label.startswith("status:")]
        missing = [prefix for prefix in ("area", "priority", "status")
                   if not any(label.startswith(prefix + ":") for label in labels)]
        if missing:
            found.append(Violation(Kind.MISSING_LABEL, number, title, "missing " + ", ".join(missing)))
        unknown = [label for label in labels if label.startswith("area:") and label.removeprefix("area:") not in AREAS]
        if unknown:
            found.append(Violation(Kind.UNKNOWN_AREA, number, title, ", ".join(unknown)))
        if issue.get("milestone") is None and not WITHOUT_MILESTONE.intersection(statuses):
            found.append(Violation(Kind.NO_MILESTONE, number, title,
                                   "status " + ("/".join(statuses) if statuses else "none")))
        if len(statuses) > 1:
            found.append(Violation(Kind.TWO_STATUSES, number, title, ", ".join("status:" + s for s in statuses)))
        for status in statuses:
            if status in STALE_AFTER:
                kind, limit = STALE_AFTER[status]
                age = now - updated(issue)
                if age > limit:
                    found.append(Violation(kind, number, title,
                                           f"updated {age.days} days ago ({issue['updated_at'][:10]})"))
    order = list(Kind)
    return sorted(found, key=lambda v: (order.index(v.kind), v.number))


def render_check(violations: list[Violation], issues: int) -> str:
    if not violations:
        return f"triage check: clean ({issues} open issues)"
    groups: dict[Kind, list[Violation]] = defaultdict(list)
    for v in violations:
        groups[v.kind].append(v)
    lines = [f"triage check: {len(violations)} violations over {issues} open issues"]
    for kind, members in groups.items():
        lines.append(f"{kind.value} ({len(members)})")
        lines.extend(f"  #{v.number:<5} {v.title} [{v.detail}]" for v in members)
    return "\n".join(lines)


def open_issues(pages: list) -> list[dict]:
    """The issues of `gh api --paginate --slurp` pages (or of one flat list), without the pull requests."""
    flat = [item for page in pages for item in (page if isinstance(page, list) else [page])]
    return [item for item in flat if "pull_request" not in item]


def fetch_for_check(source: Path | None, run=subprocess.run) -> list[dict]:
    if source is not None:
        return open_issues(json.loads(source.read_text(encoding="utf-8")))
    done = run(CHECK_COMMAND, capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise GhFailure(done.stderr.strip())
    return open_issues(json.loads(done.stdout))


def run_check(source: Path | None, as_json: bool, now: datetime, run=subprocess.run) -> int:
    try:
        issues = fetch_for_check(source, run)
    except GhFailure as failure:
        print(f"gh api failed: {failure}", file=sys.stderr)
        return 2
    violations = check(issues, now)
    if as_json:
        print(json.dumps([{**asdict(v), "kind": v.kind.name} for v in violations], indent=1))
    else:
        print(render_check(violations, len(issues)))
    return 1 if violations else 0


DECISION_ROW = re.compile(r"^\|\s*(\d{4}-\d{2}-\d{2})\s*\|([^|]*)\|([^|]*)\|")
PLAYBOOK_ROW = re.compile(r"^\|[^|]*\|\s*\[([^\]]+)\]\(([^)]+)\)\s*\|([^|]*)\|")
CITED = re.compile(r"(?<![\w/])#(\d+)\b|issues/(\d+)")
CRITICAL_AREAS = frozenset({"ledger", "consensus", "storage", "protocol", "crypto"})
LEVELS_LINK = "CONTRIBUTING.md#levels"


def decisions_citing(number: int, text: str) -> list[str]:
    """The rows of docs/decisions.md whose issue column cites `number`: date and the decision's first words."""
    rows = []
    for line in text.splitlines():
        row = DECISION_ROW.match(line)
        if row and number in {int(n) for n in ISSUE_LINK.findall(row.group(2))}:
            decision = re.sub(r"\s+", " ", row.group(3)).strip()
            struck = decision.startswith("~~")
            rows.append(f"{row.group(1)}{' (superseded)' if struck else ''}: {decision.strip('~')[:110]}")
    return rows


def playbooks_for(areas: list[str], text: str) -> list[str]:
    """The playbooks of docs/playbooks/README.md for these areas, then those for any area."""
    specific, general = [], []
    for line in text.splitlines():
        row = PLAYBOOK_ROW.match(line)
        if not row:
            continue
        wanted = {a.strip() for a in row.group(3).split(",")}
        entry = f"docs/playbooks/{row.group(2)}"
        if "any" in wanted:
            general.append(entry)
        elif wanted & set(areas):
            specific.append(entry)
    return specific + general


def suggested_level(areas: list[str]) -> str:
    """A starting point from the areas; CONTRIBUTING.md decides."""
    if not areas:
        return f"unknown (no area label); see {LEVELS_LINK}"
    if set(areas) <= {"docs"}:
        return f"0 (documentation); see {LEVELS_LINK}"
    if set(areas) <= {"docs", "scripts", "build"}:
        return f"1 (tooling); see {LEVELS_LINK}"
    if CRITICAL_AREAS & set(areas):
        return f"3 if it changes rules, chain selection, a persisted format or forging, else 1 or 2; see {LEVELS_LINK}"
    return f"1 for a refactor or test-only change, 2 for a behaviour change; see {LEVELS_LINK}"


def issue_brief(issue: dict, roadmap: dict[int, str], decisions: str, playbooks: str, now: datetime) -> str:
    """What docs/process.md asks a session to find before it starts on an issue, in one place."""
    number, labels = int(issue["number"]), label_names(issue)
    areas = [l.removeprefix("area:") for l in labels if l.startswith("area:")]
    milestone = (issue.get("milestone") or {}).get("title", "none")
    lines = [f"#{number} {issue['title'].strip()}",
             f"  state: {issue['state']}; labels: {', '.join(labels) or 'none'}; milestone: {milestone}"]
    if "status:in-progress" in labels and issue["state"] == "open":
        lines.append("  in progress: someone else is on it unless you were asked to take it over")
    if number in roadmap:
        lines.append(f"  roadmap: {roadmap[number]}")
    else:
        cited = sorted({int(a or b) for a, b in CITED.findall(issue.get("body") or "")} - {number})
        parents = [f"{roadmap[n]} (#{n})" for n in cited if n in roadmap]
        lines.append("  roadmap: none" + (f"; the issues it cites: {', '.join(parents)}" if parents else ""))
    rows = decisions_citing(number, decisions)
    lines.append("  decisions: none" if not rows else "  decisions (docs/decisions.md):")
    lines += [f"    {r}" for r in rows]
    lines.append("  playbooks: " + (", ".join(playbooks_for(areas, playbooks)) or "none"))
    lines.append(f"  suggested level: {suggested_level(areas)}")
    problems = check([issue], now) if issue["state"] == "open" else []
    lines.append("  triage: " + ("clean" if not problems else "; ".join(f"{v.kind.value} [{v.detail}]" for v in problems)))
    return "\n".join(lines)


def fetch_issue(number: int, run=subprocess.run) -> dict:
    done = run(["gh", "api", f"repos/{{owner}}/{{repo}}/issues/{number}"], capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise GhFailure(done.stderr.strip())
    return json.loads(done.stdout)


def load_issues(source: Path | None, run=subprocess.run) -> list[dict]:
    if source is not None:
        return json.loads(source.read_text(encoding="utf-8"))
    done = run(GH_COMMAND, capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise SystemExit(f"gh issue list failed: {done.stderr.strip()}")
    return json.loads(done.stdout)


def main(argv: list[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--area", help="only this area (label without the `area:` prefix)")
    parser.add_argument("--priority", help="only this priority, for example P1")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    parser.add_argument("--from", dest="source", type=Path, help="read `gh issue list --json` output from a file")
    parser.add_argument("--roadmap", type=Path, default=root / "docs/roadmap.md")
    parser.add_argument("--width", type=int, help="line width (default: the terminal's)")
    parser.add_argument("--check", action="store_true", help="the triage check of docs/process.md; exit 1 on violations")
    parser.add_argument("--issue", type=int, help="what to know before working on this issue")
    args = parser.parse_args(argv)
    if args.issue is not None:
        try:
            issue = json.loads(args.source.read_text(encoding="utf-8")) if args.source else fetch_issue(args.issue)
        except GhFailure as failure:
            print(f"gh api failed: {failure}", file=sys.stderr)
            return 2
        read = lambda p: (root / p).read_text(encoding="utf-8") if (root / p).is_file() else ""
        roadmap = roadmap_ids(args.roadmap.read_text(encoding="utf-8")) if args.roadmap.is_file() else {}
        print(issue_brief(issue, roadmap, read("docs/decisions.md"), read("docs/playbooks/README.md"),
                          datetime.now(timezone.utc)))
        return 0
    if args.check:
        return run_check(args.source, args.json, datetime.now(timezone.utc))
    roadmap = roadmap_ids(args.roadmap.read_text(encoding="utf-8")) if args.roadmap.is_file() else {}
    items = select(digest(load_issues(args.source), roadmap), args.area, args.priority)
    if args.json:
        print(json.dumps([asdict(d) for d in items], indent=1))
    else:
        print(render_text(items, args.width or shutil.get_terminal_size((100, 24)).columns))
    return 0


if __name__ == "__main__":
    sys.exit(main())
