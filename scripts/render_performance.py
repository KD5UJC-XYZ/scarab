#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Renders the measurement register into `docs/performance.md` and `docs/performance/*.md`.

`docs/data/performance-log.json` is the register of measurements. Each entry is one measurement section: its id, its
section number and heading, date, subject, issue references, commit ids, links, the page that holds its narrative,
and its data, namely `results` (tables as header, separator and rows of verbatim strings) and `listings` (fenced
commands and outputs, verbatim). The narrative stays in the markdown pages; the data lives in the register and is
copied between marker lines:

    <!-- register:table perf-03.t1 -->   ...rendered table...   <!-- /register -->
    <!-- register:listing perf-07.c2 --> ...rendered fence...    <!-- /register -->
    <!-- register:log -->                ...one row per entry... <!-- /register -->

`python3 scripts/render_performance.py` checks that every region is what the register renders (and that the register
and the pages agree on ids and headings); `--write` rewrites the regions. To record a new measurement: add an entry
(or extend one) in the register, put its markers in the page, run `--write`, commit both.

Pure standard library; no network.
"""
from __future__ import annotations

from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
import argparse
import json
import posixpath
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_docs  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
REGISTER = "docs/data/performance-log.json"
INDEX = "docs/performance.md"
OPEN = re.compile(r"^<!-- register:(table|listing) (\S+) -->$")
OPEN_LOG = "<!-- register:log -->"
CLOSE = "<!-- /register -->"


@dataclass(frozen=True)
class Problem:
    path: str
    message: str

    def __str__(self) -> str:
        return f"{self.path}: {self.message}"


def table_lines(table: Mapping[str, object]) -> list[str]:
    raw = table.get("raw")
    if raw is not None:
        return list(raw)  # type: ignore[arg-type]
    def row(cells: Sequence[str]) -> str:
        return "| " + " | ".join(cells) + " |"
    header = table["header"]
    return [row(header), str(table["separator"])] + [row(r) for r in table["rows"]]  # type: ignore[arg-type,union-attr]


def listing_lines(listing: Mapping[str, object]) -> list[str]:
    return [str(listing["fence"]), *listing["lines"], str(listing["close"])]  # type: ignore[misc]


def page_slugs(text: str) -> dict[str, str]:
    """Heading text to its anchor on one page (GitHub's `-1`, `-2` suffix for repeats)."""
    seen: Counter[str] = Counter()
    slugs: dict[str, str] = {}
    for line in check_docs.prose_lines(text):
        heading = check_docs.HEADING.match(line.text)
        if heading:
            slug = check_docs.github_slug(heading.group(2))
            slugs.setdefault(heading.group(2), slug if seen[slug] == 0 else f"{slug}-{seen[slug]}")
            seen[slug] += 1
    return slugs


def log_lines(register: Mapping[str, object], page_texts: Mapping[str, str]) -> list[str]:
    pages = {p["prefix"]: p for p in register["pages"]}  # type: ignore[index,union-attr]
    slugs = {file: page_slugs(text) for file, text in page_texts.items()}
    rows = ["| Section | Date | Subject | Issues | Page |", "|---|---|---|---|---|"]
    for entry in register["entries"]:  # type: ignore[union-attr]
        page = pages[entry["page"]]
        file = page["file"]
        anchor = slugs.get(file, {}).get(entry["heading"], "")
        target = posixpath.relpath(file, posixpath.dirname(INDEX)) + (f"#{anchor}" if anchor else "")
        rows.append(f"| {entry['section'] or ''} | {entry['date'] or ''} | {entry['subject']} | "
                    f"{', '.join(entry['issues'])} | [{page['title']}]({target}) |")
    return rows


def rewrite(path: str, text: str, register: Mapping[str, object], page_texts: Mapping[str, str]) -> tuple[str, list[Problem]]:
    """`text` with every register region replaced by its rendering, and what is wrong with the regions."""
    tables = {t["id"]: t for e in register["entries"] for t in e["results"]}  # type: ignore[index,union-attr]
    listings = {c["id"]: c for e in register["entries"] for c in e["listings"]}  # type: ignore[index,union-attr]
    problems: list[Problem] = []
    out: list[str] = []
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        match = OPEN.match(line)
        if match or line == OPEN_LOG:
            end = i + 1
            while end < len(lines) and lines[end] != CLOSE:
                end += 1
            if end == len(lines):
                problems.append(Problem(path, f"line {i + 1}: region is not closed"))
                out += lines[i:]
                break
            if line == OPEN_LOG:
                body = log_lines(register, page_texts)
            else:
                kind, ident = match.group(1), match.group(2)  # type: ignore[union-attr]
                source = tables if kind == "table" else listings
                if ident not in source:
                    problems.append(Problem(path, f"line {i + 1}: no {kind} {ident} in the register"))
                    body = lines[i + 1:end]
                else:
                    body = table_lines(source[ident]) if kind == "table" else listing_lines(source[ident])
            out += [line, *body, CLOSE]
            i = end + 1
        else:
            out.append(line)
            i += 1
    return "\n".join(out), problems


def read(root: Path, rel: str) -> str:
    return (root / rel).read_text(encoding="utf-8")


def audit(root: Path, register: Mapping[str, object]) -> tuple[dict[str, str], list[Problem]]:
    """The current text of every rendered file, and the structural problems of register against pages."""
    files = {INDEX: read(root, INDEX)}
    files.update({p["file"]: read(root, p["file"]) for p in register["pages"]})  # type: ignore[index,union-attr]
    problems: list[Problem] = []
    prefixes = {p["prefix"]: p["file"] for p in register["pages"]}  # type: ignore[index,union-attr]
    ids: Counter[str] = Counter()
    for entry in register["entries"]:  # type: ignore[union-attr]
        ids[entry["id"]] += 1
        file = prefixes.get(entry["page"])
        if file is None:
            problems.append(Problem(REGISTER, f"{entry['id']}: unknown page {entry['page']}"))
            continue
        if f"## {entry['heading']}" not in files[file].split("\n"):
            problems.append(Problem(file, f"heading of {entry['id']} is missing: {entry['heading']}"))
        for kind, data in (("table", entry["results"]), ("listing", entry["listings"])):
            for item in data:
                marker = f"<!-- register:{kind} {item['id']} -->"
                if files[file].split("\n").count(marker) != 1:
                    problems.append(Problem(file, f"marker for {item['id']} must occur exactly once"))
    problems += [Problem(REGISTER, f"duplicate id {i}") for i, n in ids.items() if n > 1]
    return files, problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--write", action="store_true", help="rewrite the regions instead of checking them")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    register = json.loads(read(args.root, REGISTER))
    files, problems = audit(args.root, register)
    stale: list[str] = []
    for path, text in files.items():
        new, found = rewrite(path, text, register, files)
        problems += found
        if new != text:
            stale.append(path)
            if args.write:
                (args.root / path).write_text(new, encoding="utf-8")
                files[path] = new
    for problem in problems:
        print(problem)
    if args.write:
        print(f"rewrote {len(stale)} file(s)")
        return 1 if problems else 0
    for path in stale:
        print(f"{path}: out of date with {REGISTER}; run scripts/render_performance.py --write")
    if not problems and not stale:
        print(f"performance pages match the register ({len(register['entries'])} entries)")
    return 1 if problems or stale else 0


if __name__ == "__main__":
    sys.exit(main())
