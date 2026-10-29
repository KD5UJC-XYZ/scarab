#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render `docs/roadmap.md` from the register `docs/data/roadmap.json`, and check it against the issues.

The register has one entry per roadmap item: a stable id (`G3`, `N4-X1`, `K2 follow-ups`), the section it belongs to,
an optional parent item (rendered as a nested bullet), a one-line title, a status from the closed `Status` enum, an
optional status detail (text shown after the status word inside the parentheses, starting with its own separator:
`: `, `; `, `, ` or a space), the priority (`P0`, `P1`, `P2` or null), the issue numbers (the first is the primary
issue), the item's prose (`notes`, markdown with links relative to `docs/`) and, for a folded item, `folded_into`, the
issue that now carries its work. Sections hold their heading and optional prose before (`intro`) and after (`outro`)
their items; a section without items holds `text` (the order of work, the milestones). The rendered file is
generated, and `--check` fails when the committed file differs from the render, so an edit by hand cannot land.

Validation (always run, also by `--check`): the status and priority enums hold, ids are unique single lines, every
issue number is a positive int, titles are one bounded line, notes are nonempty, sections and parents exist, and
`folded_into` is present exactly for folded items.

Issue state (`--check` only): every issue of the register is read from GitHub in one paginated, read-only
`gh api repos/KD5UJC-XYZ/scarab/issues?state=all` listing (any issue missing from it is read by number). Rules:

  done          every listed issue is closed
  planned       the primary issue is open
  in-progress   the primary issue is open
  folded        the primary issue is closed; `folded_into` exists (open or closed)
  declined      the primary issue is closed
  decision      no state rule: the item records a decision, whatever its issue's state (the issue must exist)

An item without issues is not checked. Planned and in-progress are told apart by the issue's `status:` label, which
this check does not read. With `--pr N` (CI passes the pull request under test; `--pr auto` reads the pull request of
the current branch) the issues GitHub will close when that pull request merges (`closingIssuesReferences`, which holds
only issues and only for a pull request into the default branch) count as closed, so the check judges the state after
the merge: a pull request that closes an item's issue must also mark the item.
When `gh` is missing, unauthenticated or offline the state part (and the pull request read) is skipped with a notice
and the exit status depends on the rest (`--require-issue-state`, which CI passes, makes that a failure);
`--no-issue-state` skips it on purpose. Closing or reopening an issue by hand therefore needs the matching register
edit, or the check fails on every branch.

Usage:
  python3 scripts/render_roadmap.py                      write docs/roadmap.md
  python3 scripts/render_roadmap.py --check              validate, compare issue states, fail on a stale render
  python3 scripts/render_roadmap.py --check --pr 123     the same, as if pull request 123 were merged

Pure standard library; the only network use is read-only `gh` in `--check` (`gh api` GETs and `gh pr view`).
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
    DONE_ISSUE_OPEN = "done-issue-open"
    ACTIVE_ISSUE_CLOSED = "active-issue-closed"
    FOLDED_ISSUE_OPEN = "folded-issue-open"
    DECLINED_ISSUE_OPEN = "declined-issue-open"
    UNKNOWN_ISSUE = "unknown-issue"


STATE_KINDS = frozenset({Kind.DONE_ISSUE_OPEN, Kind.ACTIVE_ISSUE_CLOSED, Kind.FOLDED_ISSUE_OPEN,
                         Kind.DECLINED_ISSUE_OPEN, Kind.UNKNOWN_ISSUE})


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
REQUIRED_ITEM_FIELDS = ("id", "section", "title", "status", "priority", "issues", "notes")


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
        if not isinstance(status, str) or status not in {s.value for s in Status}:
            problems.append(Problem(Kind.BAD_STATUS, subject, repr(status)))
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

States = Mapping[int, IssueState]


def state_problems(register: Mapping[str, object], states: States) -> list[Problem]:
    """Every disagreement between an item's status and the state of its issues."""
    problems: list[Problem] = []

    def state(item_id: str, number: int) -> IssueState | None:
        if number not in states:
            problems.append(Problem(Kind.UNKNOWN_ISSUE, item_id, f"#{number} is not an issue of {REPOSITORY}"))
            return None
        return states[number]

    for item in register["items"]:  # type: ignore[union-attr]
        issues: list[int] = item["issues"]
        if not issues:
            continue
        iid, status = item["id"], Status(item["status"])
        primary = state(iid, issues[0])
        if status is Status.DONE:
            for number in issues:
                current = primary if number == issues[0] else state(iid, number)
                if current is IssueState.OPEN:
                    problems.append(Problem(Kind.DONE_ISSUE_OPEN, iid, f"status done, #{number} open"))
        elif status in (Status.PLANNED, Status.IN_PROGRESS) and primary is IssueState.CLOSED:
            problems.append(Problem(Kind.ACTIVE_ISSUE_CLOSED, iid, f"status {status.value}, #{issues[0]} closed"))
        elif status is Status.FOLDED:
            if primary is IssueState.OPEN:
                problems.append(Problem(Kind.FOLDED_ISSUE_OPEN, iid, f"status folded, #{issues[0]} open"))
            state(iid, item["folded_into"])
        elif status is Status.DECLINED and primary is IssueState.OPEN:
            problems.append(Problem(Kind.DECLINED_ISSUE_OPEN, iid, f"status declined, #{issues[0]} open"))
    return problems


def wanted_issues(register: Mapping[str, object]) -> set[int]:
    wanted: set[int] = set()
    for item in register["items"]:  # type: ignore[union-attr]
        wanted.update(item["issues"])
        if "folded_into" in item:
            wanted.add(item["folded_into"])
    return wanted


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


def parse_state(text: str) -> IssueState:
    return IssueState(text.strip().lower())


def github_states(wanted: set[int], run: Gh = gh) -> dict[int, IssueState]:
    """The state of every wanted issue (or pull request); a number GitHub does not know is left out."""
    listing = run(["api", "--paginate", f"repos/{REPOSITORY}/issues?state=all&per_page=100",
                   "--jq", ".[] | [.number, .state] | @tsv"])
    states: dict[int, IssueState] = {}
    for line in listing.splitlines():
        number, _, state = line.partition("\t")
        if number.strip().isdigit():
            states[int(number)] = parse_state(state)
    for number in sorted(wanted - states.keys()):
        try:
            states[number] = parse_state(run(["api", f"repos/{REPOSITORY}/issues/{number}", "--jq", ".state"]))
        except Unavailable:
            continue
    return states


def closed_by(pr: str, run: Gh = gh) -> set[int]:
    """The issues GitHub closes when pull request `pr` merges; `auto` is the pull request of the current branch."""
    if pr == "auto":
        pr = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], capture_output=True, text=True,
                            check=False).stdout.strip()
    numbers = run(["pr", "view", pr, "--repo", REPOSITORY, "--json", "closingIssuesReferences",
                   "--jq", ".closingIssuesReferences[].number"])
    return {int(n) for n in numbers.split() if n.isdigit()}


def after_merge(states: States, closing: set[int]) -> dict[int, IssueState]:
    return {n: (IssueState.CLOSED if n in closing else s) for n, s in states.items()}


# ---------------------------------------------------------------------------------------------------------------
# Rendering

def issue_link(number: int) -> str:
    return f"[#{number}]({ISSUE_BASE}{number})"


def item_line(item: Mapping[str, object]) -> str:
    status = Status(item["status"])
    label = f"folded into {issue_link(item['folded_into'])}" if status is Status.FOLDED else status.label  # type: ignore[arg-type]
    paren = ", ".join(p for p in (item.get("priority"), label) if p) + str(item.get("detail") or "")
    links = ", ".join(issue_link(n) for n in item["issues"])  # type: ignore[union-attr]
    notes = str(item["notes"])
    indent = "  " if "parent" in item else ""
    return f"{indent}- **{item['id']}**" + (f" {links}" if links else "") + f" ({paren})" \
        + ("" if notes.startswith((":", ",", ";", ".")) else " ") + notes


def render(register: Mapping[str, object]) -> str:
    statuses = ", ".join(f"**{s.label}**" for s in Status)
    out = [
        "<!-- SPDX-License-Identifier: Apache-2.0 -->",
        f"<!-- Generated by scripts/render_roadmap.py from {REGISTER}. Do not edit by hand. -->",
        "# Roadmap",
        "",
        "> Generated from [roadmap.json](data/roadmap.json) by `scripts/render_roadmap.py`; do not edit this file "
        "by hand. Change the register, then run the script; CI runs it with `--check`, which also compares each "
        "item's status with the state of its issues.",
        "",
        str(register["intro"]),
        "",
        f"Status values: {statuses}. A done item's issues are closed; a planned or in-progress item's first issue "
        f"is open (its `status:` label tells the two apart); a folded item's work continues in the issue it names.",
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
            out.append(item_line(top))
            out += [item_line(child) for child in own if child.get("parent") == top["id"]]
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


def run(root: Path, check: bool, states: States | None = None) -> tuple[list[Problem], str | None]:
    """Validate and render; with `check`, also compare the committed file and, when `states` is given, the issues."""
    register, problems = load(root)
    if problems:
        return problems, None
    rendered = render(register)  # type: ignore[arg-type]
    if check:
        if states is not None:
            problems += state_problems(register, states)  # type: ignore[arg-type]
        target = root / OUTPUT
        current = target.read_text(encoding="utf-8") if target.is_file() else None
        if current != rendered:
            problems.append(Problem(Kind.STALE_RENDER, OUTPUT, f"run python3 scripts/render_roadmap.py and commit {OUTPUT}"))
    return problems, rendered


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--check", action="store_true", help="validate, compare issue states, fail on a stale render")
    parser.add_argument("--pr", help="count the issues this pull request closes as closed (a number, or auto)")
    parser.add_argument("--no-issue-state", action="store_true", help="skip the comparison with GitHub issue states")
    parser.add_argument("--require-issue-state", action="store_true",
                        help="fail instead of skipping when GitHub cannot be read (CI, where a token is always set)")
    args = parser.parse_args(argv)
    states: dict[int, IssueState] | None = None
    wanted: set[int] = set()
    unavailable = False
    register, invalid = load(args.root)
    if args.check and not args.no_issue_state and not invalid:
        wanted = wanted_issues(register)  # type: ignore[arg-type]
        try:
            states = github_states(wanted)
            if args.pr:
                states = after_merge(states, closed_by(args.pr))
        except Unavailable as error:
            print(f"roadmap register: notice: issue states NOT checked, GitHub unavailable ({error})", file=sys.stderr)
            states, unavailable = None, True
    problems, rendered = run(args.root, args.check, states)
    if problems:
        for problem in problems:
            print(f"roadmap register: {problem}", file=sys.stderr)
        return 1
    if unavailable and args.require_issue_state:
        print("roadmap register: --require-issue-state: failing because issue states were not read", file=sys.stderr)
        return 1
    if args.check:
        checked = f"issue states checked for {len(wanted)} issues" if states is not None else "issue states skipped"
        print(f"roadmap register ok; docs/roadmap.md is the current render; {checked}")
    else:
        assert rendered is not None
        (args.root / OUTPUT).write_text(rendered, encoding="utf-8")
        print(f"wrote {OUTPUT} ({len(rendered.encode('utf-8'))} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
