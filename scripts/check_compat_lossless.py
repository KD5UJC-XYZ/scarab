#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""One-off check that restructuring `docs/compatibility.md` into a register lost nothing.

Given the old file (for example `git show <base>:docs/compatibility.md > old.md`), it verifies that the new corpus
(`docs/compatibility.md`, `docs/data/compatibility.json` and every `docs/compatibility/*.md`) still holds

  * every issue reference (`#123`),
  * every repository path (backticked or linked; links are compared after resolving them against their own file),
  * every backticked span (symbols, commands, versions),
  * every URL,
  * every commit-like hex id and every number,
  * every table cell (each is in the corpus verbatim, whitespace-normalised, `**` ignored), and
  * every line of prose outside tables and headings.

It prints what it checked and every loss, and exits 1 when anything is lost. Not a CI gate: the old file exists only
in history.

Usage: python3 scripts/check_compat_lossless.py --old OLD.md [--root DIR]
"""
from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
import argparse
import json
import posixpath
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_docs  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OLD_BASE = "docs"
JSON_PATH = "docs/data/compatibility.json"
MAIN_PATH = "docs/compatibility.md"
PAGE_DIR = "docs/compatibility"

LINK = re.compile(r"\]\(([^)\s]+)\)")
SPAN = re.compile(r"`([^`]+)`")
ISSUE = re.compile(r"(?<![\w/&])#\d+\b")
URL = re.compile(r"https?://[^\s)>\]`|]+")
PATHLIKE = re.compile(r"(?<![\w./:-])(?:[\w.@<>{}*-]+/)+[\w.@<>{}*-]+")
COMMIT = re.compile(r"(?<![\w-])[0-9a-f]{7,40}(?![\w-])")
NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
SEPARATOR = re.compile(r"^\|[\s:|-]+\|?\s*$")


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
class Loss:
    category: Category
    text: str

    def __str__(self) -> str:
        return f"{self.category.value}: {self.text[:160]!r}"


def resolve_links(text: str, base: str) -> str:
    """`text` with every relative link target replaced by its repository-rooted path (anchors kept)."""
    def fix(match: re.Match[str]) -> str:
        target = match.group(1)
        if re.match(r"^[a-z][a-z0-9+.-]*:", target):
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


def corpus_parts(root: Path) -> list[str]:
    """Normalised text of every file of the new layout."""
    parts: list[str] = []
    main = root / MAIN_PATH
    if main.is_file():
        parts.append(resolve_links(main.read_text(encoding="utf-8"), OLD_BASE))
    register = root / JSON_PATH
    if register.is_file():
        # Issue numbers are stored as integers; render them as the references they stand for.
        data = json.loads(register.read_text(encoding="utf-8"))
        for comp in data.get("components", []):
            for key in ("evidence", "limitations"):
                for number in (comp.get(key) or {}).get("issues", []):
                    parts.append(f"#{number}")
        parts.append("\n".join(json_strings(data)))
    for page in sorted((root / PAGE_DIR).glob("*.md")):
        parts.append(resolve_links(page.read_text(encoding="utf-8"), PAGE_DIR))
    return parts


def tokens(text: str) -> dict[Category, set[str]]:
    spans = {m.group(1).strip() for m in SPAN.finditer(text)}
    paths = {m.group(0) for m in PATHLIKE.finditer(SPAN.sub(" ", text))}
    paths |= {s for s in spans if PATHLIKE.fullmatch(s)}
    paths |= {m.group(1).partition("#")[0] for m in LINK.finditer(text)
              if not re.match(r"^[a-z][a-z0-9+.-]*:", m.group(1)) and m.group(1).partition("#")[0]}
    return {
        Category.ISSUE: {m.group(0) for m in ISSUE.finditer(text)},
        Category.PATH: paths,
        Category.SPAN: spans,
        Category.URL: {m.group(0).rstrip(".,;:") for m in URL.finditer(text)},
        Category.COMMIT: {m.group(0) for m in COMMIT.finditer(text)},
        Category.NUMBER: {m.group(0).rstrip(",") for m in NUMBER.finditer(text)},
    }


def old_cells_and_lines(old: str) -> tuple[list[str], list[str]]:
    cells: list[str] = []
    lines: list[str] = []
    in_fence = False
    rows = old.splitlines()
    for number, raw in enumerate(rows):
        if raw.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence or not raw.strip() or raw.startswith("#"):
            continue
        if raw.startswith("|"):
            header = number + 1 < len(rows) and SEPARATOR.match(rows[number + 1])
            if not SEPARATOR.match(raw) and not header:
                cells += [c for c in check_docs.split_cells(raw) if c]
        else:
            lines.append(raw)
    return cells, lines


def lost(old: str, parts: Iterable[str]) -> list[Loss]:
    """What the old text holds that the new parts do not."""
    old_resolved = resolve_links(old, OLD_BASE)
    corpus = "\n".join(parts)
    squashed = squash(corpus)
    new_tokens = tokens(corpus)
    losses: list[Loss] = []
    for category, wanted in tokens(old_resolved).items():
        for token in sorted(wanted - new_tokens[category]):
            if category in (Category.SPAN, Category.PATH, Category.URL, Category.COMMIT) and squash(token) in squashed:
                continue
            losses.append(Loss(category, token))
    cells, lines = old_cells_and_lines(old_resolved)
    for cell in cells:
        if squash(cell) not in squashed:
            losses.append(Loss(Category.CELL, cell))
    for line in lines:
        if squash(line) not in squashed:
            losses.append(Loss(Category.LINE, line))
    return losses


def summarise(old: str) -> dict[str, int]:
    resolved = resolve_links(old, OLD_BASE)
    counts = {category.value: len(found) for category, found in tokens(resolved).items()}
    cells, lines = old_cells_and_lines(resolved)
    counts[Category.CELL.value] = len(cells)
    counts[Category.LINE.value] = len(lines)
    return counts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--old", type=Path, required=True, help="the previous docs/compatibility.md")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    old = args.old.read_text(encoding="utf-8")
    for name, count in summarise(old).items():
        print(f"checked {count:6d} distinct {name}s")
    losses = lost(old, corpus_parts(args.root))
    for loss in losses:
        print(f"LOST {loss}")
    print(f"{len(losses)} losses")
    return 1 if losses else 0


if __name__ == "__main__":
    sys.exit(main())
