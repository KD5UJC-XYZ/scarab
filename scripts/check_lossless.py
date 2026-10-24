#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""One-off check that restructuring a document into several pages (and JSON registers) lost nothing.

Generalises the checker written for the compatibility split. Given the old text (`--old <git rev>:<path>`, or a plain
file) and the new set of files (`--new`, each a file or a directory searched recursively for `*.md` and `*.json`), it
verifies that the new set still holds

  * every issue reference (`#123`),
  * every repository path (backticked or linked; relative links are compared after resolving them against their own
    file; the path of the old file itself is kept when it still exists),
  * every backticked span (symbols, commands, versions),
  * every URL,
  * every commit-like hex id and every number (fenced code blocks are included: the commands live there),
  * every table cell and every line of prose outside tables and headings (each verbatim, whitespace-normalised, `**`
    ignored).

Counting is by multiset. A token that occurs in the old text and nowhere in the new set is a LOSS and fails the check.
A token whose count dropped (it is still present, fewer times) is reported as a DUPLICATE: that is the only permitted
shrinkage, and the reader lists them in the review. JSON values count as the strings they decode to; numbers stored
as JSON numbers are rendered as text.

Prints per-category counts and every finding; exits 1 on a loss. Not a CI gate: the old text exists only in history.

Usage: python3 scripts/check_lossless.py --old REV:PATH --new PATH [--new PATH ...] [--root DIR] [--list-duplicates]
"""
from __future__ import annotations

from collections import Counter
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
import argparse
import json
import posixpath
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_docs  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]

LINK = re.compile(r"\]\(([^)\s]+)\)")
SPAN = re.compile(r"`([^`]+)`")
ISSUE = re.compile(r"(?<![\w/&])#\d+\b")
URL = re.compile(r"https?://[^\s)>\]`|]+")
PATHLIKE = re.compile(r"(?<![\w./:-])(?:[\w.@<>{}*-]+/)+[\w.@<>{}*-]+")
COMMIT = re.compile(r"(?<![\w-])[0-9a-f]{7,40}(?![\w-])")
NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
SEPARATOR = re.compile(r"^\|[\s:|-]+\|?\s*$")
SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*:")


class Category(str, Enum):
    ISSUE = "issue reference"
    PATH = "repository path"
    SPAN = "backticked span"
    URL = "URL"
    COMMIT = "commit-like id"
    NUMBER = "number"
    CELL = "table cell"
    LINE = "prose line"


@dataclass(frozen=True)
class Finding:
    category: Category
    text: str
    old_count: int = 1
    new_count: int = 0

    def __str__(self) -> str:
        suffix = "" if self.new_count == 0 else f" (old {self.old_count}, new {self.new_count})"
        return f"{self.category.value}: {self.text[:160]!r}{suffix}"


@dataclass(frozen=True)
class Report:
    checked: dict[Category, int]
    losses: tuple[Finding, ...]
    duplicates: tuple[Finding, ...]


def resolve_links(text: str, base: str) -> str:
    """`text` with every relative link target replaced by its repository-rooted path (anchors kept)."""
    def fix(match: re.Match[str]) -> str:
        target = match.group(1)
        if SCHEME.match(target):
            return match.group(0)
        file_part, hash_, fragment = target.partition("#")
        if not file_part:
            return match.group(0)
        resolved = posixpath.normpath(posixpath.join(base, file_part))
        return f"]({resolved}{hash_}{fragment})"
    return LINK.sub(fix, text)


def squash(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("**", "")).strip()


def json_strings(value: object) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield str(key)
            yield from json_strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from json_strings(item)
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        yield str(value)


def new_files(root: Path, selections: Sequence[Path]) -> list[Path]:
    found: list[Path] = []
    for selection in selections:
        path = selection if selection.is_absolute() else root / selection
        if path.is_dir():
            found += sorted(p for p in path.rglob("*") if p.suffix in (".md", ".json") and p.is_file())
        elif path.is_file():
            found.append(path)
        else:
            raise FileNotFoundError(str(selection))
    return found


def corpus_parts(root: Path, files: Sequence[Path]) -> list[str]:
    """Normalised text of every file of the new layout."""
    parts: list[str] = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        relative = path.resolve().relative_to(root.resolve()).as_posix()
        if path.suffix == ".json":
            parts.append("\n".join(json_strings(json.loads(text))))
        else:
            parts.append(resolve_links(text, posixpath.dirname(relative)))
    return parts


def tokens(text: str) -> dict[Category, Counter[str]]:
    spans = Counter(m.group(1).strip() for m in SPAN.finditer(text))
    paths = Counter(m.group(0) for m in PATHLIKE.finditer(SPAN.sub(" ", text)))
    paths.update(s for s in spans.elements() if PATHLIKE.fullmatch(s))
    paths.update(m.group(1).partition("#")[0] for m in LINK.finditer(text)
                 if not SCHEME.match(m.group(1)) and m.group(1).partition("#")[0])
    return {
        Category.ISSUE: Counter(m.group(0) for m in ISSUE.finditer(text)),
        Category.PATH: paths,
        Category.SPAN: spans,
        Category.URL: Counter(m.group(0).rstrip(".,;:") for m in URL.finditer(text)),
        Category.COMMIT: Counter(m.group(0) for m in COMMIT.finditer(text)),
        Category.NUMBER: Counter(m.group(0).rstrip(",") for m in NUMBER.finditer(text)),
    }


def old_cells_and_lines(old: str) -> tuple[list[str], list[str]]:
    """Table cells and prose lines of `old`, outside headings; fenced lines count as prose (commands live there)."""
    cells: list[str] = []
    lines: list[str] = []
    in_fence = False
    rows = old.splitlines()
    for number, raw in enumerate(rows):
        if raw.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            if raw.strip():
                lines.append(raw)
            continue
        if not raw.strip() or raw.startswith("#"):
            continue
        if raw.startswith("|"):
            header = number + 1 < len(rows) and SEPARATOR.match(rows[number + 1])
            if not SEPARATOR.match(raw) and not header:
                cells += [c for c in check_docs.split_cells(raw) if c]
        else:
            lines.append(raw)
    return cells, lines


def compare(old: str, old_path: str, parts: Iterable[str], kept_paths: frozenset[str] = frozenset()) -> Report:
    """What the old text holds that the new parts do not (losses), and what they hold fewer times (duplicates)."""
    old_resolved = resolve_links(old, posixpath.dirname(old_path))
    corpus = "\n".join(parts)
    squashed = squash(corpus)
    new_tokens = tokens(corpus)
    old_tokens = tokens(old_resolved)
    losses: list[Finding] = []
    duplicates: list[Finding] = []
    for category, wanted in old_tokens.items():
        for token, count in sorted(wanted.items()):
            have = new_tokens[category][token]
            if have >= count:
                continue
            if category is Category.PATH and token in kept_paths:
                continue
            if have == 0 and category in (Category.SPAN, Category.PATH, Category.URL, Category.COMMIT) \
                    and squash(token) in squashed:
                continue
            (losses if have == 0 else duplicates).append(Finding(category, token, count, have))
    cells, lines = old_cells_and_lines(old_resolved)
    for category, items in ((Category.CELL, cells), (Category.LINE, lines)):
        for item in sorted(set(items)):
            if squash(item) not in squashed:
                losses.append(Finding(category, item))
    checked = {category: len(found) for category, found in old_tokens.items()}
    checked[Category.CELL] = len(set(cells))
    checked[Category.LINE] = len(set(lines))
    return Report(checked, tuple(losses), tuple(duplicates))


def read_old(spec: str, root: Path) -> tuple[str, str]:
    """(text, repository path) of `REV:PATH`, or of a plain file when `spec` is not a revision pair."""
    _, colon, path = spec.partition(":")
    if colon and not Path(spec).exists():
        shown = subprocess.run(["git", "-C", str(root), "show", spec], check=True, capture_output=True, text=True)
        return shown.stdout, path
    return Path(spec).read_text(encoding="utf-8"), spec


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--old", required=True, help="REV:PATH of the previous text (or a plain file)")
    parser.add_argument("--new", type=Path, action="append", required=True, help="a new file or directory")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--list-duplicates", action="store_true", help="print every count that dropped")
    args = parser.parse_args(argv)
    old, old_path = read_old(args.old, args.root)
    files = new_files(args.root, args.new)
    kept = frozenset({old_path}) if (args.root / old_path).is_file() else frozenset()
    report = compare(old, old_path, corpus_parts(args.root, files), kept)
    print(f"old: {args.old}; new files: {len(files)}")
    for category, count in report.checked.items():
        print(f"checked {count:6d} distinct {category.value}s")
    for finding in report.losses:
        print(f"LOST {finding}")
    if args.list_duplicates:
        for finding in report.duplicates:
            print(f"DUPLICATE {finding}")
    print(f"{len(report.losses)} losses; {len(report.duplicates)} tokens with a smaller count (exact duplicates)")
    return 1 if report.losses else 0


if __name__ == "__main__":
    sys.exit(main())
