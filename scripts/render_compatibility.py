#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render `docs/compatibility.md` from the register `docs/data/compatibility.json`.

The register has one entry per component: an id, the component name, a status from the closed `Status` enum, a
one-line summary, evidence pointers (suite names, repository paths, documentation anchors, issue numbers),
limitation pointers (issue numbers, roadmap ids) and an optional long-form evidence page,
`docs/compatibility/<id>.md`, which holds the unabridged evidence prose. The rendered file is a short status table; it
is generated, and `--check` fails when the committed file differs from the render, so an edit by hand cannot land.

Validation (always run, also by `--check`): the status enum holds, ids are unique and file-name safe, an `implemented`
component names at least one evidence pointer (a `partial` or `not implemented` one may name none; its limitations say
why), every suite name is a `class`/`object`/`trait` of some Scala file under `*/src/test`, every referenced path and
documentation anchor exists, every page exists and is referenced exactly once, and no rendered table cell exceeds the per-cell budget of
`check_docs` (an oversized cell is an error, never truncated silently: evidence lists are cut at their own, smaller
budget with an explicit count of the remainder).

Usage:
  python3 scripts/render_compatibility.py            write docs/compatibility.md
  python3 scripts/render_compatibility.py --check    validate; fail when the committed markdown differs

Pure standard library; no sbt, no network.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
import argparse
import json
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_docs  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
REGISTER = "docs/data/compatibility.json"
OUTPUT = "docs/compatibility.md"
PAGE_DIR = "docs/compatibility"
ISSUE_BASE = "https://github.com/KD5UJC-XYZ/scarab/issues/"
SCHEMA_VERSION = 1
EVIDENCE_CELL_CHARS = 320
LIMITATION_CELL_CHARS = 200
ID_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


class Status(str, Enum):
    """The closed set of component statuses; the value is what the table shows."""
    IMPLEMENTED = "implemented"
    PARTIAL = "partial"
    NOT_IMPLEMENTED = "not implemented"


class Kind(str, Enum):
    SCHEMA = "schema"
    UNKNOWN_FIELD = "unknown-field"
    DUPLICATE_ID = "duplicate-id"
    BAD_ID = "bad-id"
    BAD_STATUS = "bad-status"
    BAD_SUMMARY = "bad-summary"
    MISSING_EVIDENCE = "missing-evidence"
    MISSING_SUITE = "missing-suite"
    MISSING_PATH = "missing-path"
    BAD_ANCHOR = "bad-anchor"
    BAD_ISSUE = "bad-issue"
    MISSING_PAGE = "missing-page"
    ORPHAN_PAGE = "orphan-page"
    CELL_TOO_LONG = "cell-too-long"
    STALE_RENDER = "stale-render"


@dataclass(frozen=True)
class Problem:
    kind: Kind
    subject: str
    detail: str = ""

    def __str__(self) -> str:
        return f"{self.kind.value}: {self.subject}" + (f" ({self.detail})" if self.detail else "")


COMPONENT_FIELDS = frozenset({"id", "component", "status", "summary", "evidence", "limitations", "page"})
EVIDENCE_FIELDS = frozenset({"suites", "paths", "docs", "issues"})
LIMITATION_FIELDS = frozenset({"issues", "roadmap"})
SECTION_FIELDS = frozenset({"id", "heading", "summary", "pages", "matrix"})
TOP_FIELDS = frozenset({"schemaVersion", "intro", "sections", "components"})


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


def suite_names(root: Path) -> frozenset[str]:
    """Names of every class, object and trait of the Scala sources under `*/src/test`."""
    names: set[str] = set()
    for source in root.glob("*/src/test/**/*.scala"):
        names.update(re.findall(r"\b(?:class|object|trait)\s+(\w+)", source.read_text(encoding="utf-8", errors="replace")))
    return frozenset(names)


def page_title(root: Path, name: str) -> str:
    """The first heading of `docs/compatibility/<name>.md`, or the name when the page has none."""
    path = root / PAGE_DIR / f"{name}.md"
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.startswith("# "):
                return line[2:].strip()
    return name


def string_list(value: object) -> list[str] | None:
    return list(value) if isinstance(value, list) and all(isinstance(v, str) and v for v in value) else None


def issue_list(value: object) -> list[int] | None:
    ok = isinstance(value, list) and all(isinstance(v, int) and not isinstance(v, bool) and v > 0 for v in value)
    return list(value) if ok else None  # type: ignore[arg-type]


def unknown(subject: str, obj: Mapping[str, object], allowed: frozenset[str]) -> list[Problem]:
    return [Problem(Kind.UNKNOWN_FIELD, subject, key) for key in obj if key not in allowed]


def validate(root: Path, register: object) -> list[Problem]:
    """Every defect of the register against the tree; empty when the register is sound."""
    if not isinstance(register, dict):
        return [Problem(Kind.SCHEMA, REGISTER, "not an object")]
    problems = unknown(REGISTER, register, TOP_FIELDS)
    if register.get("schemaVersion") != SCHEMA_VERSION:
        problems.append(Problem(Kind.SCHEMA, REGISTER, f"schemaVersion must be {SCHEMA_VERSION}"))
    if not isinstance(register.get("intro"), str):
        problems.append(Problem(Kind.SCHEMA, "intro", "must be a string"))
    suites = suite_names(root)
    anchors: dict[str, frozenset[str]] = {}
    referenced_pages: list[str] = []
    seen_ids: set[str] = set()
    components = register.get("components")
    if not isinstance(components, list):
        return problems + [Problem(Kind.SCHEMA, "components", "must be a list")]
    for index, entry in enumerate(components):
        if not isinstance(entry, dict):
            problems.append(Problem(Kind.SCHEMA, f"components[{index}]", "not an object"))
            continue
        cid = entry.get("id")
        subject = cid if isinstance(cid, str) else f"components[{index}]"
        problems += unknown(subject, entry, COMPONENT_FIELDS)
        if not isinstance(cid, str) or not ID_PATTERN.match(cid):
            problems.append(Problem(Kind.BAD_ID, subject, "lower-case words joined by hyphens"))
        elif cid in seen_ids:
            problems.append(Problem(Kind.DUPLICATE_ID, subject))
        else:
            seen_ids.add(cid)
        if not isinstance(entry.get("component"), str) or not entry["component"].strip() or "\n" in entry["component"]:
            problems.append(Problem(Kind.SCHEMA, subject, "component must be one nonempty line"))
        if entry.get("status") not in {s.value for s in Status}:
            problems.append(Problem(Kind.BAD_STATUS, subject, repr(entry.get("status"))))
        summary = entry.get("summary")
        if not isinstance(summary, str) or not summary.strip() or "\n" in summary or "|" in summary:
            problems.append(Problem(Kind.BAD_SUMMARY, subject, "one nonempty line without '|'"))
        evidence = entry.get("evidence", {})
        if not isinstance(evidence, dict):
            problems.append(Problem(Kind.SCHEMA, subject, "evidence must be an object"))
            evidence = {}
        problems += unknown(subject, evidence, EVIDENCE_FIELDS)
        if entry.get("status") == Status.IMPLEMENTED.value and not any(evidence.get(key) for key in EVIDENCE_FIELDS):
            problems.append(Problem(Kind.MISSING_EVIDENCE, subject, "an implemented component needs an evidence pointer"))
        for key in ("suites", "paths", "docs"):
            if key in evidence and string_list(evidence[key]) is None:
                problems.append(Problem(Kind.SCHEMA, subject, f"evidence.{key} must be a list of strings"))
        if "issues" in evidence and issue_list(evidence["issues"]) is None:
            problems.append(Problem(Kind.BAD_ISSUE, subject, "evidence.issues must be positive integers"))
        for suite in string_list(evidence.get("suites", [])) or []:
            if suite not in suites:
                problems.append(Problem(Kind.MISSING_SUITE, subject, suite))
        for path in string_list(evidence.get("paths", [])) or []:
            if not safe_relative(path) or not (root / path).exists():
                problems.append(Problem(Kind.MISSING_PATH, subject, path))
        for doc in string_list(evidence.get("docs", [])) or []:
            file_part, _, fragment = doc.partition("#")
            if not safe_relative(file_part) or not file_part.startswith("docs/") or not file_part.endswith(".md") \
                    or not (root / file_part).is_file():
                problems.append(Problem(Kind.MISSING_PATH, subject, doc))
                continue
            if fragment:
                if file_part not in anchors:
                    anchors[file_part] = check_docs.anchors_of((root / file_part).read_text(encoding="utf-8"))
                if fragment not in anchors[file_part]:
                    problems.append(Problem(Kind.BAD_ANCHOR, subject, doc))
        limitations = entry.get("limitations", {})
        if not isinstance(limitations, dict):
            problems.append(Problem(Kind.SCHEMA, subject, "limitations must be an object"))
            limitations = {}
        problems += unknown(subject, limitations, LIMITATION_FIELDS)
        if "issues" in limitations and issue_list(limitations["issues"]) is None:
            problems.append(Problem(Kind.BAD_ISSUE, subject, "limitations.issues must be positive integers"))
        if "roadmap" in limitations and string_list(limitations["roadmap"]) is None:
            problems.append(Problem(Kind.SCHEMA, subject, "limitations.roadmap must be a list of strings"))
        page = entry.get("page")
        if page is not None:
            expected = f"{PAGE_DIR}/{cid}.md"
            if page != expected or not (root / expected).is_file():
                problems.append(Problem(Kind.MISSING_PAGE, subject, f"expected the file {expected}"))
            else:
                referenced_pages.append(page)
    sections = register.get("sections")
    if not isinstance(sections, list):
        return problems + [Problem(Kind.SCHEMA, "sections", "must be a list")]
    matrix_sections = 0
    for index, section in enumerate(sections):
        if not isinstance(section, dict) or not isinstance(section.get("id"), str):
            problems.append(Problem(Kind.SCHEMA, f"sections[{index}]", "an object with an id"))
            continue
        subject = f"section {section['id']}"
        problems += unknown(subject, section, SECTION_FIELDS)
        if not isinstance(section.get("heading"), str) or not section["heading"].strip():
            problems.append(Problem(Kind.SCHEMA, subject, "heading must be a nonempty string"))
        if section.get("matrix") is True:
            matrix_sections += 1
            continue
        if not isinstance(section.get("summary"), str):
            problems.append(Problem(Kind.SCHEMA, subject, "summary must be a string"))
        for name in string_list(section.get("pages", [])) or []:
            page = f"{PAGE_DIR}/{name}.md"
            if not ID_PATTERN.match(name) or not (root / page).is_file():
                problems.append(Problem(Kind.MISSING_PAGE, subject, page))
            else:
                referenced_pages.append(page)
    if matrix_sections != 1:
        problems.append(Problem(Kind.SCHEMA, "sections", "exactly one section must have matrix: true"))
    for page in sorted(set(referenced_pages)):
        if referenced_pages.count(page) > 1:
            problems.append(Problem(Kind.DUPLICATE_ID, page, "page referenced more than once"))
    page_root = root / PAGE_DIR
    if page_root.is_dir():
        present = {p.relative_to(root).as_posix() for p in page_root.glob("*.md")}
        for orphan in sorted(present - set(referenced_pages)):
            problems.append(Problem(Kind.ORPHAN_PAGE, orphan, "not referenced by the register"))
    return problems


def safe_relative(path: str) -> bool:
    parts = Path(path).parts
    return bool(path) and not Path(path).is_absolute() and ".." not in parts


# ---------------------------------------------------------------------------------------------------------------
# Rendering

def issue_link(number: int) -> str:
    return f"[#{number}]({ISSUE_BASE}{number})"


def bounded(items: Sequence[str], budget: int, page: str | None) -> str:
    """The items joined by commas up to `budget` characters, then a count of the rest and a pointer to the page."""
    kept: list[str] = []
    length = 0
    for item in items:
        tail = 0 if len(kept) + 1 == len(items) else 60
        if length + len(item) + 2 + tail > budget and kept:
            break
        kept.append(item)
        length += len(item) + 2
    rest = len(items) - len(kept)
    text = ", ".join(kept)
    if rest:
        text += f" and {rest} more" + (" on the page" if page else "")
    return text


def evidence_cell(entry: Mapping[str, object]) -> str:
    evidence = entry.get("evidence") or {}
    items: list[str] = [f"`{s}`" for s in evidence.get("suites", [])]  # type: ignore[union-attr]
    for path in evidence.get("paths", []):  # type: ignore[union-attr]
        items.append(f"[{'/'.join(Path(path).parts[-2:])}](../{path})")
    for doc in evidence.get("docs", []):  # type: ignore[union-attr]
        file_part, _, fragment = doc.partition("#")
        label = Path(file_part).name + ("#" + fragment if fragment else "")
        items.append(f"[{label}]({doc.removeprefix('docs/')})")
    items += [issue_link(n) for n in evidence.get("issues", [])]  # type: ignore[union-attr]
    return bounded(items, EVIDENCE_CELL_CHARS, entry.get("page")) if items else "none recorded"  # type: ignore[arg-type]


def limitation_cell(entry: Mapping[str, object]) -> str:
    limitations = entry.get("limitations") or {}
    items = [issue_link(n) for n in limitations.get("issues", [])]  # type: ignore[union-attr]
    items += [f"roadmap {r}" for r in limitations.get("roadmap", [])]  # type: ignore[union-attr]
    if items:
        return bounded(items, LIMITATION_CELL_CHARS, entry.get("page"))  # type: ignore[arg-type]
    return "see the page" if entry.get("page") else "none recorded"


def cell(text: str) -> str:
    return text.replace("|", "\\|")


def matrix_rows(register: Mapping[str, object]) -> list[str]:
    rows = ["| Component | Status | Summary | Evidence | Limitations |", "|---|---|---|---|---|"]
    for entry in register["components"]:  # type: ignore[union-attr]
        page = entry.get("page")
        summary = entry["summary"] + (f" ([details](compatibility/{entry['id']}.md))" if page else "")
        cells = [f"**{entry['component']}**", entry["status"], summary, evidence_cell(entry), limitation_cell(entry)]
        rows.append("| " + " | ".join(cell(c) for c in cells) + " |")
    return rows


def render(root: Path, register: Mapping[str, object]) -> str:
    statuses = ", ".join(f"**{s.value}**" for s in Status)
    out = [
        "<!-- SPDX-License-Identifier: Apache-2.0 -->",
        f"<!-- Generated by scripts/render_compatibility.py from {REGISTER}. Do not edit by hand. -->",
        "# Compatibility matrix",
        "",
        f"> Generated from [compatibility.json](data/compatibility.json) by `scripts/render_compatibility.py`; do not "
        f"edit this file by hand. Change the register or a page in `docs/compatibility/`, "
        f"then run the script; CI runs it with `--check`.",
        "",
        str(register["intro"]),
        "",
        f"Status values: {statuses}. The one-line summaries are orientation only: the evidence, and what is not "
        f"checked, is on each component's page.",
    ]
    for section in register["sections"]:  # type: ignore[union-attr]
        out += ["", f"## {section['heading']}", ""]
        if section.get("matrix"):
            out += matrix_rows(register)
            continue
        out.append(section["summary"])
        pages = section.get("pages", [])
        if pages:
            out.append("")
            out += [f"- [{page_title(root, name)}](compatibility/{name}.md)" for name in pages]
    return "\n".join(out) + "\n"


def oversized_cells(markdown: str, limit: int) -> list[Problem]:
    problems: list[Problem] = []
    for line in markdown.splitlines():
        if line.startswith("|"):
            for text in check_docs.split_cells(line):
                if len(text) > limit:
                    problems.append(Problem(Kind.CELL_TOO_LONG, text[:60], f"{len(text)} > {limit} characters"))
    return problems


def run(root: Path, check: bool) -> tuple[list[Problem], str | None]:
    register = read_json(root / REGISTER)
    problems = validate(root, register)
    if problems:
        return problems, None
    rendered = render(root, register)  # type: ignore[arg-type]
    problems = oversized_cells(rendered, check_docs.DEFAULT_MAX_CELL_CHARS)
    if problems:
        return problems, None
    if check:
        target = root / OUTPUT
        current = target.read_text(encoding="utf-8") if target.is_file() else None
        if current != rendered:
            return [Problem(Kind.STALE_RENDER, OUTPUT, f"run python3 scripts/render_compatibility.py and commit {OUTPUT}")], rendered
    return [], rendered


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--check", action="store_true", help="validate and fail when the committed markdown differs")
    args = parser.parse_args(argv)
    problems, rendered = run(args.root, args.check)
    if problems:
        for problem in problems:
            print(f"compatibility register: {problem}", file=sys.stderr)
        return 1
    if args.check:
        print("compatibility register ok; docs/compatibility.md is the current render")
    else:
        assert rendered is not None
        (args.root / OUTPUT).write_text(rendered, encoding="utf-8")
        print(f"wrote {OUTPUT} ({len(rendered.encode('utf-8'))} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
