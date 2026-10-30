#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render `docs/roadmap.md` from the register `docs/data/roadmap.json` and a snapshot of its issues' states.

The register has one entry per roadmap item: a stable id (`G3`, `N4-X1`, `K2 follow-ups`), the section it belongs to,
an optional parent item (rendered as a nested bullet), a one-line title, an optional status detail (text shown after
the status word inside the parentheses, starting with its own separator: `: `, `; `, `, ` or a space), the priority
(`P0`, `P1`, `P2` or null), the issue numbers (the first is the primary issue), the item's prose (`notes`, markdown with
links relative to `docs/`) and, for a folded item, `folded_into`, the issue that now carries its work. Sections hold
their heading and optional prose before (`intro`) and after (`outro`) their items; a section without items holds
`text` (the order of work, the milestones). The rendered file is generated, and `--check` fails when the committed file
differs from the render, so an edit by hand cannot land.

Status is derived from the issues, so it cannot drift from them (owner decision, 2026-10-29). An item with issues takes
its status from the snapshot `docs/data/issue-states.json`:

  done          every issue is closed, and one as completed
  declined      every issue is closed as not planned
  in-progress   an issue is open and the primary one carries `status:in-progress`
  planned       an issue is open otherwise
  not synced    an issue is not in the snapshot yet (the next refresh adds it)

A status is stored in the register only where no issue can give it: `decision` (the item records a decision, whatever
its issue's state) and `folded` (its work continues in `folded_into`, and its primary issue is closed), and on an item
without issues. Validation (always run, also by `--check`) holds the enums, unique single-line ids, positive int issue
numbers, bounded one-line titles, nonempty notes, known sections and parents, `folded_into` exactly on folded items, a
status exactly where it is stored, and a well-formed snapshot.

The snapshot is the only issue state the check reads, so `--check` needs no network and judges a pull request by its
own files. It is refreshed with `--refresh-states` (adds the issues the register names that it lacks, for a change that
adds an item) or `--refresh-states --all` (every issue again, and drops the ones no item names); the nightly triage
workflow runs the second and proposes the result as a pull request. Each refresh reads one paginated, read-only
`gh api repos/KD5UJC-XYZ/scarab/issues?state=all` listing, and a number missing from it by number.

Usage:
  python3 scripts/render_roadmap.py                          write docs/roadmap.md
  python3 scripts/render_roadmap.py --check                  validate and fail on a stale render (offline)
  python3 scripts/render_roadmap.py --refresh-states [--all] refresh the snapshot from GitHub, then render

Pure standard library; the only network use is read-only `gh api` in `--refresh-states`.
"""
from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
import argparse
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REGISTER = "docs/data/roadmap.json"
OUTPUT = "docs/roadmap.md"
SNAPSHOT = "docs/data/issue-states.json"
REPOSITORY = "KD5UJC-XYZ/scarab"
ISSUE_BASE = f"https://github.com/{REPOSITORY}/issues/"
SCHEMA_VERSION = 1
TITLE_CHARS = 120
DETAIL_SEPARATORS = (": ", "; ", ", ", " ")


class Status(str, Enum):
    """The closed set of item statuses; `label` is what the rendered parentheses show."""
    PLANNED = "planned"
    IN_PROGRESS = "in-progress"
    DONE = "done"
    DECLINED = "declined"
    FOLDED = "folded"
    DECISION = "decision"

    @property
    def label(self) -> str:
        return "in progress" if self is Status.IN_PROGRESS else self.value


class Priority(str, Enum):
    P0 = "P0"
    P1 = "P1"
    P2 = "P2"


class IssueState(str, Enum):
    OPEN = "open"
    CLOSED = "closed"


class Kind(str, Enum):
    SCHEMA = "schema"
    UNKNOWN_FIELD = "unknown-field"
    DUPLICATE_ID = "duplicate-id"
    BAD_ID = "bad-id"
    BAD_STATUS = "bad-status"
    BAD_PRIORITY = "bad-priority"
    BAD_ISSUE = "bad-issue"
    BAD_TITLE = "bad-title"
    BAD_DETAIL = "bad-detail"
    EMPTY_NOTES = "empty-notes"
    UNKNOWN_SECTION = "unknown-section"
    BAD_PARENT = "bad-parent"
    BAD_FOLDED_INTO = "bad-folded-into"
    BAD_JSON = "bad-json"
    STALE_RENDER = "stale-render"
    BAD_SNAPSHOT = "bad-snapshot"
    FOLDED_ISSUE_OPEN = "folded-issue-open"
    UNKNOWN_ISSUE = "unknown-issue"




@dataclass(frozen=True)
class Problem:
    kind: Kind
    subject: str
    detail: str = ""

    def __str__(self) -> str:
        return f"{self.kind.value}: {self.subject}" + (f" ({self.detail})" if self.detail else "")


TOP_FIELDS = frozenset({"schemaVersion", "intro", "sections", "items"})
SECTION_FIELDS = frozenset({"id", "heading", "intro", "outro", "text"})
ITEM_FIELDS = frozenset({"id", "section", "parent", "title", "status", "detail", "priority", "issues", "notes",
                         "folded_into"})
REQUIRED_ITEM_FIELDS = ("id", "section", "title", "priority", "issues", "notes")
STORED_WITH_ISSUES = frozenset({"decision", "folded"})


def read_json(path: Path) -> object:
    """Parse JSON, refusing duplicate object keys."""
    def unique_fields(pairs: list[tuple[str, object]]) -> dict[str, object]:
        fields: dict[str, object] = {}
        for key, value in pairs:
            if key in fields:
                raise ValueError(f"duplicate JSON field: {key}")
            fields[key] = value
        return fields
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_fields)


def is_issue(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def one_line(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip()) and "\n" not in value


def unknown(subject: str, obj: Mapping[str, object], allowed: frozenset[str]) -> list[Problem]:
    return [Problem(Kind.UNKNOWN_FIELD, subject, key) for key in obj if key not in allowed]


def validate(register: object) -> list[Problem]:
    """Every defect of the register; empty when it is sound. Needs no network and no tree."""
    if not isinstance(register, dict):
        return [Problem(Kind.SCHEMA, REGISTER, "not an object")]
    problems = unknown(REGISTER, register, TOP_FIELDS)
    if register.get("schemaVersion") != SCHEMA_VERSION:
        problems.append(Problem(Kind.SCHEMA, REGISTER, f"schemaVersion must be {SCHEMA_VERSION}"))
    if not isinstance(register.get("intro"), str) or not register["intro"].strip():
        problems.append(Problem(Kind.SCHEMA, "intro", "must be a nonempty string"))
    sections = register.get("sections")
    items = register.get("items")
    if not isinstance(sections, list) or not isinstance(items, list):
        return problems + [Problem(Kind.SCHEMA, REGISTER, "sections and items must be lists")]
    section_ids: set[str] = set()
    for index, section in enumerate(sections):
        if not isinstance(section, dict) or not one_line(section.get("id")):
            problems.append(Problem(Kind.SCHEMA, f"sections[{index}]", "an object with a one-line id"))
            continue
        subject = f"section {section['id']}"
        problems += unknown(subject, section, SECTION_FIELDS)
        if section["id"] in section_ids:
            problems.append(Problem(Kind.DUPLICATE_ID, subject))
        section_ids.add(section["id"])
        if not one_line(section.get("heading")):
            problems.append(Problem(Kind.SCHEMA, subject, "heading must be one nonempty line"))
        for key in ("intro", "outro", "text"):
            if key in section and (not isinstance(section[key], str) or not section[key].strip()):
                problems.append(Problem(Kind.SCHEMA, subject, f"{key} must be a nonempty string"))
    item_sections: dict[str, str] = {}
    parents: dict[str, object] = {}
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            problems.append(Problem(Kind.SCHEMA, f"items[{index}]", "not an object"))
            continue
        iid = item.get("id")
        subject = iid if isinstance(iid, str) else f"items[{index}]"
        problems += unknown(subject, item, ITEM_FIELDS)
        missing = [key for key in REQUIRED_ITEM_FIELDS if key not in item]
        if missing:
            problems.append(Problem(Kind.SCHEMA, subject, "missing " + ", ".join(missing)))
        if not one_line(iid) or "*" in iid or iid != iid.strip():
            problems.append(Problem(Kind.BAD_ID, subject, "one line without '*' or surrounding blanks"))
        elif iid in item_sections:
            problems.append(Problem(Kind.DUPLICATE_ID, subject))
        else:
            item_sections[iid] = item.get("section")  # type: ignore[assignment]
        if not isinstance(item.get("section"), str) or item["section"] not in section_ids:
            problems.append(Problem(Kind.UNKNOWN_SECTION, subject, repr(item.get("section"))))
        if "parent" in item:
            parents[subject] = item["parent"] if isinstance(item["parent"], str) else repr(item["parent"])
        title = item.get("title")
        if not one_line(title) or len(title) > TITLE_CHARS or "|" in title:
            problems.append(Problem(Kind.BAD_TITLE, subject, f"one nonempty line of at most {TITLE_CHARS} characters"))
        status = item.get("status")
        has_issues = isinstance(item.get("issues"), list) and bool(item["issues"])
        if status is None:
            if isinstance(item.get("issues"), list) and not item["issues"]:
                problems.append(Problem(Kind.BAD_STATUS, subject, "an item without issues stores its status"))
        elif not isinstance(status, str) or status not in {s.value for s in Status}:
            problems.append(Problem(Kind.BAD_STATUS, subject, repr(status)))
        elif has_issues and status not in STORED_WITH_ISSUES:
            problems.append(Problem(Kind.BAD_STATUS, subject,
                                    f"{status!r} is derived from the issues; only decision or folded is stored"))
        detail = item.get("detail")
        if detail is not None and (not isinstance(detail, str) or "\n" in detail
                                   or not detail.startswith(DETAIL_SEPARATORS) or not detail.strip()):
            problems.append(Problem(Kind.BAD_DETAIL, subject, "one line starting with ': ', '; ', ', ' or a space"))
        priority = item.get("priority")
        if priority is not None and (not isinstance(priority, str) or priority not in {p.value for p in Priority}):
            problems.append(Problem(Kind.BAD_PRIORITY, subject, repr(priority)))
        issues = item.get("issues")
        if not isinstance(issues, list) or not all(is_issue(n) for n in issues):
            problems.append(Problem(Kind.BAD_ISSUE, subject, "issues must be a list of positive integers"))
        elif len(set(issues)) != len(issues):
            problems.append(Problem(Kind.BAD_ISSUE, subject, "an issue is listed twice"))
        if not isinstance(item.get("notes"), str) or not item["notes"].strip():
            problems.append(Problem(Kind.EMPTY_NOTES, subject))
        folded = status == Status.FOLDED.value
        if folded != ("folded_into" in item) or ("folded_into" in item and not is_issue(item["folded_into"])):
            problems.append(Problem(Kind.BAD_FOLDED_INTO, subject,
                                    "a positive issue number on a folded item, and only there"))
    text_sections = {s["id"] for s in sections if isinstance(s, dict) and "text" in s}
    for subject, section in item_sections.items():
        if isinstance(section, str) and section in text_sections:
            problems.append(Problem(Kind.UNKNOWN_SECTION, subject, f"section {section} holds text, not items"))
    for subject, parent in parents.items():
        if parent not in item_sections or item_sections[parent] != item_sections.get(subject) \
                or parent in parents or parent == subject:
            problems.append(Problem(Kind.BAD_PARENT, subject, f"{parent!r} must be a top-level item of the same section"))
    return problems


# ---------------------------------------------------------------------------------------------------------------
# Issue state

@dataclass(frozen=True)
class IssueRecord:
    """What the snapshot keeps of an issue: its state, why it was closed, and whether it is marked in progress."""
    state: IssueState
    reason: str
    in_progress: bool


Snapshot = Mapping[int, IssueRecord]
REASONS = frozenset({"", "completed", "not_planned", "reopened", "duplicate"})


def derive(item: Mapping[str, object], snapshot: Snapshot) -> Status | None:
    """The item's status: the stored one, or the one its issues give; None when an issue is not in the snapshot."""
    if "status" in item:
        return Status(item["status"])
    issues: list[int] = item["issues"]  # type: ignore[assignment]
    if any(n not in snapshot for n in issues):
        return None
    records = [snapshot[n] for n in issues]
    if all(r.state is IssueState.CLOSED for r in records):
        return Status.DONE if any(r.reason != "not_planned" for r in records) else Status.DECLINED
    return Status.IN_PROGRESS if records[0].in_progress and records[0].state is IssueState.OPEN else Status.PLANNED


def snapshot_problems(register: Mapping[str, object], snapshot: Snapshot) -> list[Problem]:
    """A stored status the snapshot contradicts: a folded item whose primary issue is open."""
    problems: list[Problem] = []
    for item in register["items"]:  # type: ignore[union-attr]
        if item.get("status") == Status.FOLDED.value and item["issues"]:
            record = snapshot.get(item["issues"][0])
            if record is not None and record.state is IssueState.OPEN:
                problems.append(Problem(Kind.FOLDED_ISSUE_OPEN, item["id"], f"status folded, #{item['issues'][0]} open"))
    return problems


def wanted_issues(register: Mapping[str, object]) -> set[int]:
    wanted: set[int] = set()
    for item in register["items"]:  # type: ignore[union-attr]
        wanted.update(item["issues"])
        if "folded_into" in item:
            wanted.add(item["folded_into"])
    return wanted


def read_snapshot(root: Path) -> tuple[dict[int, IssueRecord], list[Problem]]:
    """The committed snapshot; a missing file is an empty snapshot, a malformed one a defect."""
    path = root / SNAPSHOT
    if not path.is_file():
        return {}, []
    try:
        data = read_json(path)
        if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA_VERSION or not isinstance(data.get("issues"), dict):
            raise ValueError(f"schemaVersion {SCHEMA_VERSION} and an issues object")
        snapshot = {}
        for key, value in data["issues"].items():
            state, reason, in_progress = value
            if not key.isdigit() or reason not in REASONS or not isinstance(in_progress, bool):
                raise ValueError(f"entry {key!r}")
            snapshot[int(key)] = IssueRecord(IssueState(state), reason, in_progress)
    except (OSError, ValueError, TypeError) as error:
        return {}, [Problem(Kind.BAD_SNAPSHOT, SNAPSHOT, str(error))]
    return snapshot, []


def write_snapshot(root: Path, snapshot: Snapshot) -> None:
    """One issue per line, in number order, so a refresh's diff names exactly the issues that changed."""
    entries = [f'  "{n}": {json.dumps([r.state.value, r.reason, r.in_progress])}' for n, r in sorted(snapshot.items())]
    body = ",\n".join(entries)
    (root / SNAPSHOT).write_text(f'{{\n "schemaVersion": {SCHEMA_VERSION},\n "issues": {{\n{body}\n }}\n}}\n',
                                 encoding="utf-8")


class Unavailable(Exception):
    """GitHub could not be read: `gh` is missing, unauthenticated or offline."""


Gh = Callable[[Sequence[str]], str]


def gh(args: Sequence[str]) -> str:
    """Run `gh` with these arguments and return its output; any failure is `Unavailable`."""
    try:
        done = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=120, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise Unavailable(str(error)) from error
    if done.returncode != 0:
        lines = (done.stderr or done.stdout).strip().splitlines()
        raise Unavailable(lines[0] if lines else f"gh exited with {done.returncode}")
    return done.stdout


RECORD_JQ = '[.number, .state, (.state_reason // ""), (([.labels[].name] | index("status:in-progress")) != null)] | @tsv'


def parse_record(line: str) -> tuple[int, IssueRecord] | None:
    fields = line.rstrip("\n").split("\t")
    if len(fields) != 4 or not fields[0].strip().isdigit():
        return None
    return int(fields[0]), IssueRecord(IssueState(fields[1].strip().lower()), fields[2].strip(), fields[3].strip() == "true")


def github_records(wanted: set[int], run: Gh | None = None) -> dict[int, IssueRecord]:
    """The record of every wanted issue; a number GitHub does not know is left out."""
    run = run or gh
    listing = run(["api", "--paginate", f"repos/{REPOSITORY}/issues?state=all&per_page=100", "--jq", ".[] | " + RECORD_JQ])
    records = {n: r for n, r in filter(None, map(parse_record, listing.splitlines())) if n in wanted}
    for number in sorted(wanted - records.keys()):
        try:
            found = parse_record(run(["api", f"repos/{REPOSITORY}/issues/{number}", "--jq", RECORD_JQ]))
        except Unavailable:
            continue
        if found is not None:
            records[number] = found[1]
    return records


def refresh(root: Path, every: bool, run: Gh | None = None) -> list[Problem]:
    """Update the snapshot from GitHub: the missing issues, or with `every` all of them (dropping unnamed ones)."""
    register, problems = load(root)
    if problems:
        return problems
    snapshot, problems = read_snapshot(root)
    if problems and not every:
        return problems
    wanted = wanted_issues(register)  # type: ignore[arg-type]
    fetch = wanted if every else wanted - snapshot.keys()
    fresh = github_records(fetch, run) if fetch else {}
    kept = {} if every else {n: r for n, r in snapshot.items() if n in wanted}
    write_snapshot(root, {**kept, **fresh})
    return [Problem(Kind.UNKNOWN_ISSUE, REGISTER, f"#{n} is not an issue of {REPOSITORY}") for n in sorted(fetch - fresh.keys())]


# ---------------------------------------------------------------------------------------------------------------
# Rendering

def issue_link(number: int) -> str:
    return f"[#{number}]({ISSUE_BASE}{number})"


NOT_SYNCED = "state not yet synced"


def item_line(item: Mapping[str, object], snapshot: Snapshot) -> str:
    status = derive(item, snapshot)
    if status is Status.FOLDED:
        label = f"folded into {issue_link(item['folded_into'])}"  # type: ignore[arg-type]
    else:
        label = NOT_SYNCED if status is None else status.label
    paren = ", ".join(p for p in (item.get("priority"), label) if p) + str(item.get("detail") or "")
    links = ", ".join(issue_link(n) for n in item["issues"])  # type: ignore[union-attr]
    notes = str(item["notes"])
    indent = "  " if "parent" in item else ""
    return f"{indent}- **{item['id']}**" + (f" {links}" if links else "") + f" ({paren})" \
        + ("" if notes.startswith((":", ",", ";", ".")) else " ") + notes


def render(register: Mapping[str, object], snapshot: Snapshot) -> str:
    statuses = ", ".join(f"**{s.label}**" for s in Status)
    out = [
        "<!-- SPDX-License-Identifier: Apache-2.0 -->",
        f"<!-- Generated by scripts/render_roadmap.py from {REGISTER}. Do not edit by hand. -->",
        "# Roadmap",
        "",
        "> Generated from [roadmap.json](data/roadmap.json) and the issue-state snapshot "
        "[issue-states.json](data/issue-states.json) by `scripts/render_roadmap.py`; do not edit this file by hand. "
        "An item's status comes from its issues; a nightly job refreshes the snapshot.",
        "",
        str(register["intro"]),
        "",
        f"Status values: {statuses}. A done item's issues are closed; a planned or in-progress item has an open issue "
        f"(the primary issue's `status:in-progress` label tells the two apart); a folded item's work continues in the "
        f"issue it names; a decision records a decision.",
    ]
    items: list[Mapping[str, object]] = register["items"]  # type: ignore[assignment]
    for section in register["sections"]:  # type: ignore[union-attr]
        out += ["", f"## {section['heading']}", ""]
        if "text" in section:
            out.append(section["text"])
        if "intro" in section:
            out += [section["intro"], ""]
        own = [item for item in items if item["section"] == section["id"]]
        for top in (item for item in own if "parent" not in item):
            out.append(item_line(top, snapshot))
            out += [item_line(child, snapshot) for child in own if child.get("parent") == top["id"]]
        if "outro" in section:
            out += ["", section["outro"]]
    return "\n".join(out) + "\n"


def load(root: Path) -> tuple[object, list[Problem]]:
    """The register and its defects; unreadable JSON is a defect, not a traceback."""
    try:
        register = read_json(root / REGISTER)
    except (OSError, ValueError) as error:
        return None, [Problem(Kind.BAD_JSON, REGISTER, str(error))]
    return register, validate(register)


def run(root: Path, check: bool) -> tuple[list[Problem], str | None]:
    """Validate and render from the committed snapshot; with `check`, also compare the committed file. Offline."""
    register, problems = load(root)
    snapshot, broken = read_snapshot(root)
    if problems or broken:
        return problems + broken, None
    rendered = render(register, snapshot)  # type: ignore[arg-type]
    if check:
        problems += snapshot_problems(register, snapshot)  # type: ignore[arg-type]
        target = root / OUTPUT
        current = target.read_text(encoding="utf-8") if target.is_file() else None
        if current != rendered:
            problems.append(Problem(Kind.STALE_RENDER, OUTPUT, f"run python3 scripts/render_roadmap.py and commit {OUTPUT}"))
    return problems, rendered


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--check", action="store_true", help="validate and fail on a stale render (offline)")
    parser.add_argument("--refresh-states", action="store_true",
                        help="add the missing issues to the snapshot from GitHub, then render")
    parser.add_argument("--all", action="store_true", help="with --refresh-states: read every issue again")
    args = parser.parse_args(argv)
    if args.all and not args.refresh_states:
        parser.error("--all goes with --refresh-states")
    if args.refresh_states:
        try:
            unknown_issues = refresh(args.root, args.all)
        except Unavailable as error:
            print(f"roadmap register: GitHub unavailable, snapshot not refreshed ({error})", file=sys.stderr)
            return 2
        for problem in unknown_issues:
            print(f"roadmap register: {problem}", file=sys.stderr)
        if unknown_issues and any(p.kind is not Kind.UNKNOWN_ISSUE for p in unknown_issues):
            return 1
    problems, rendered = run(args.root, args.check)
    if problems:
        for problem in problems:
            print(f"roadmap register: {problem}", file=sys.stderr)
        return 1
    if args.check:
        print("roadmap register ok; docs/roadmap.md is the current render")
    else:
        assert rendered is not None
        (args.root / OUTPUT).write_text(rendered, encoding="utf-8")
        print(f"wrote {OUTPUT} ({len(rendered.encode('utf-8'))} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
