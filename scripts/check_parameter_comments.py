#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""No new comment inside a parameter or argument list.

AGENTS.md (How code is written here): "Keep parameter lists free of comments, including constructor fields and
call arguments. Put parameter contracts in the declaration's Scaladoc (`@param` where useful), and explain a
call before the expression." This guard reads every tracked `.scala` and `.sbt` file
(`scala_comments.scala_sources`; the sbt build is Scala and its `Seq(...)` arguments are call arguments; the
archived programs under `fixtures/` are left as they are) and counts the comments that stand between the
arguments of a parenthesized list: a `//` or `/* ... */` comment, or a run of `//` comments on consecutive
lines (`scala_comments.blocks`), counted once, whose innermost enclosing bracket is a `(` that opens the
parameters of a definition or a class, or the arguments of a call, and that sits at an argument boundary:

  * the code before it, comments aside, ends with that `(` or with a `,` (`f(a, // why`, or a comment on the line
    after `class C(`); or
  * the code after it begins with a `)` or a `,` (`f(a // why` with the `)` on the next line), unless the
    argument it ends opens a body at that level: a lambda (`=>`) or a block opened by a `:` at the end of a line
    (`Some(new Iterator[A]:`). A comment on the last line of a body is about the body, not about the list.

A `(` opens such a list when it follows, on its own line, an identifier, a backquoted name, a `]` or a `)`.
After a keyword (`if (`, `while (`, `case (`, `val (`), an operator or nothing it is a condition, a grouping, a
tuple or a pattern, and comments in it are not counted. Nor is a comment in a `{ ... }` or `[ ... ]` nested in
the list, such as a lambda's braced body, or a comment between two chained calls of one argument (a line of its
own before `.map(g)`), which explains the call that follows it. Brackets, quotes and comment markers inside
string literals (interpolated and triple-quoted ones too) and character literals are not code
(`scala_comments.split`).

The check is lexical, so it can misfire:

  * A `(` after an identifier is taken for a call even where it opens an extractor pattern, so a comment on the
    line after `case Some(` counts; a pattern's arguments are a list of a kind.
  * A body that a `(` holds with no `=>` or line-final `:` before it, such as an `if ... then ... else` split over
    lines inside the parentheses, reads as an argument, so a comment that ends it just before the `)` counts.
  * A `(` on the line after its callee is not taken for a call; scalafmt never writes one there.

The counts are compared with the committed baseline `docs/data/parameter-comment-baseline.json`, which only
shrinks (`shrinking_baseline`).

    python3 scripts/check_parameter_comments.py                    check (the default)
    python3 scripts/check_parameter_comments.py --write-baseline   record lower counts; refuses any increase

Pure standard library.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import argparse
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scala_comments  # noqa: E402
import shrinking_baseline  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
# where the rule is written down, named in the refusal
RULE = 'AGENTS.md#how-code-is-written-here'
BASELINE = shrinking_baseline.Baseline(
    path='docs/data/parameter-comment-baseline.json',
    sites='comments in parameter and argument lists',
    refusal="new comments in parameter or argument lists: put a parameter's contract in the declaration's "
            f'Scaladoc (@param) and explain a call before the expression ({RULE}):',
    comment='comments inside the parentheses of a definition, a class or a call, per Scala or sbt source; '
            'written by scripts/check_parameter_comments.py --write-baseline, which refuses an increase. It only '
            'shrinks.')
# the words after which a `(` opens a condition, a grouping, a tuple or a pattern, not a parameter list
KEYWORDS = frozenset(('if', 'while', 'for', 'match', 'case', 'yield', 'return', 'then', 'else', 'do', 'throw',
                      'try', 'finally', 'val', 'var', 'lazy'))
BEFORE_CALL = re.compile(r'(`[^`\n]+`|[\w$]+|[\])])[ \t]*$')
BRACKET = re.compile(r'[()\[\]{},]')
NESTED = re.compile(r'\([^()\[\]{}]*\)|\[[^()\[\]{}]*\]|\{[^()\[\]{}]*\}')
BODY = re.compile(r'=>|:[ \t]*$', re.MULTILINE)


@dataclass
class Frame:
    """An open bracket at `at`, and where its current argument begins."""
    bracket: str
    at: int
    argument: int


def opens_list(code: str, at: int) -> bool:
    """Whether the `(` at `at` opens the parameters of a definition or a class, or a call's arguments."""
    line_start = code.rfind('\n', 0, at) + 1
    before = BEFORE_CALL.search(code, line_start, at)
    return before is not None and before.group(1) not in KEYWORDS


def previous_code(code: str, at: int) -> str:
    k = at - 1
    while k >= 0 and code[k].isspace():
        k -= 1
    return code[k] if k >= 0 else ''


def next_code(code: str, at: int) -> str:
    k = at
    while k < len(code) and code[k].isspace():
        k += 1
    return code[k] if k < len(code) else ''


def opens_body(argument: str) -> bool:
    """Whether the text of an argument, its nested brackets removed, opens a lambda or a `:` block."""
    flat, previous = argument, None
    while flat != previous:
        previous, flat = flat, NESTED.sub(' ', flat)
    return BODY.search(flat) is not None


def in_list(code: str, frame: Frame | None, block: scala_comments.Block) -> bool:
    if frame is None or frame.bracket != '(' or not opens_list(code, frame.at):
        return False
    if previous_code(code, block.start) in ('(', ','):
        return True
    return next_code(code, block.end) in (')', ',') and not opens_body(code[frame.argument:block.start])


def sites(source: str) -> list[int]:
    """The line of each comment block inside a parameter or argument list."""
    split = scala_comments.split(source)
    code = split.code
    pending = scala_comments.blocks(source, split.comments)
    found: list[int] = []
    stack: list[Frame] = []
    following = 0

    def judge(upto: int) -> None:
        nonlocal following
        while following < len(pending) and pending[following].start < upto:
            if in_list(code, stack[-1] if stack else None, pending[following]):
                found.append(pending[following].line)
            following += 1

    for match in BRACKET.finditer(code):
        if following == len(pending):
            break
        judge(match.start())
        bracket, at = match.group(), match.start()
        if bracket in '([{':
            stack.append(Frame(bracket, at, at + 1))
        elif bracket == ',':
            if stack:
                stack[-1].argument = at + 1
        elif stack:
            stack.pop()
    judge(len(code) + 1)
    return found


def current(root: Path) -> shrinking_baseline.Sites:
    listed = subprocess.run(['git', '-C', str(root), 'ls-files'], check=True, capture_output=True,
                            text=True).stdout.split('\n')
    found = {path: sites((root / path).read_text(encoding='utf-8')) for path in scala_comments.scala_sources(listed)}
    return {path: lines for path, lines in found.items() if lines}


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--write-baseline', action='store_true', help='record lower counts; refuses any increase')
    args = parser.parse_args(argv)
    return shrinking_baseline.check(root, BASELINE, current(root), args.write_baseline)


if __name__ == '__main__':
    sys.exit(main())
