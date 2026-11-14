#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The comments of a Scala source, and its code with the comments and the text of its literals blanked.

The text guards that read comments (`check_pinned_citations.py`) or the code around them
(`check_parameter_comments.py`) share this split. A comment is a `//` line comment or a `/* ... */` block
comment (Scala block comments nest). A string literal is blanked with its interpolated `${...}` expressions,
triple-quoted or not, by the scanner of `check_imperative.py`; a character literal (`'('`, `'\\''`, `'\\u0041'`)
is blanked too, so a quoted bracket is not a bracket. The quotes themselves stay, so a literal still reads
as a value. Blanking keeps the length and every line break, so an offset into the code is an offset into
the source. A comment inside an interpolation is part of the literal and is not reported.

Pure standard library.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import bisect
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_imperative import string_literal  # noqa: E402

CHARACTER = re.compile(r"'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'")
INTERESTING = re.compile(r"/[*/]|[\"']")
NESTING = re.compile(r"/\*|\*/")


@dataclass(frozen=True)
class Comment:
    """One comment: `start` and `end` are offsets into the source, `line` is the 1-based line of `start`."""
    start: int
    end: int
    line: int
    block: bool


@dataclass(frozen=True)
class Block:
    """A block comment, or a run of `//` comments each on the line after the one before with only
    whitespace between them; `text` is the source from the first one's start to the last one's end."""
    comments: tuple[Comment, ...]
    text: str

    @property
    def start(self) -> int:
        return self.comments[0].start

    @property
    def end(self) -> int:
        return self.comments[-1].end

    @property
    def line(self) -> int:
        return self.comments[0].line


@dataclass(frozen=True)
class Split:
    code: str
    comments: tuple[Comment, ...]


def comment_end(source: str, i: int) -> int:
    """The offset after the block comment that opens at `i`; Scala block comments nest (as
    `check_imperative.comment_end`, by search rather than by character)."""
    depth = 0
    for match in NESTING.finditer(source, i):
        depth += 1 if match.group() == '/*' else -1
        if depth == 0:
            return match.end()
    return len(source)


def split(source: str) -> Split:
    pieces: list[str] = []
    found: list[Comment] = []
    newlines = [match.start() for match in re.finditer('\n', source)]
    i, n = 0, len(source)

    def blank(start: int, end: int, quote: str = '') -> None:
        """`source[start:end]` blanked, line breaks kept; a literal keeps its delimiting quotes."""
        text = '\n'.join(' ' * len(line) for line in source[start:end].split('\n'))
        if quote:
            closed = end - 1 > start and source[end - 1] == quote
            text = quote + text[1:-1] + quote if closed else quote + text[1:]
        pieces.append(text)

    def comment(start: int, end: int, block: bool) -> None:
        found.append(Comment(start, end, bisect.bisect_left(newlines, start) + 1, block))
        blank(start, end)

    while i < n:
        if source.startswith('/*', i):
            end = comment_end(source, i)
            comment(i, end, True)
        elif source.startswith('//', i):
            end = source.find('\n', i)
            end = n if end < 0 else end
            comment(i, end, False)
        elif source[i] == '"':
            interpolated = i > 0 and (source[i - 1].isalnum() or source[i - 1] == '_')
            triple = source.startswith('"""', i)
            end, _ = string_literal(source, i + (3 if triple else 1), triple, interpolated)
            blank(i, end, '"')
        elif source[i] == "'" and (match := CHARACTER.match(source, i)):
            end = match.end()
            blank(i, end, "'")
        else:
            # code up to the next comment or literal
            following = INTERESTING.search(source, i + 1)
            end = n if following is None else following.start()
            pieces.append(source[i:end])
        i = end
    return Split(''.join(pieces), tuple(found))


def blocks(source: str, comments: tuple[Comment, ...]) -> list[Block]:
    """The comments grouped into blocks (`Block`)."""
    runs: list[list[Comment]] = []
    for comment in comments:
        previous = runs[-1][-1] if runs else None
        if (previous is not None and not comment.block and not previous.block
                and comment.line == previous.line + 1 and not source[previous.end:comment.start].strip()):
            runs[-1].append(comment)
        else:
            runs.append([comment])
    return [Block(tuple(run), source[run[0].start:run[-1].end]) for run in runs]


def scala_sources(listed: list[str]) -> list[str]:
    """The tracked Scala and sbt sources the comment guards read: every `.scala` and `.sbt` file but the
    archived programs under `fixtures/`, which are recorded provenance of a fixture and are kept as they were
    when it was made, and generated sources (`src_managed`)."""
    return sorted(p for p in listed
                  if p.endswith(('.scala', '.sbt')) and not p.startswith('fixtures/') and '/src_managed/' not in p)
