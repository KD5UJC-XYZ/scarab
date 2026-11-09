#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every wrap of the reference that a main Scala source handles names its row of the overflow register.

The owner's standing rule (#474, 2026-11-09) is that Scarab never reproduces an overflow, an underflow, a wrap or
a narrowing of the reference, and that every place where the reference has one is recorded, with the reason, in
`docs/compatibility/overflow-improvements.md`. Each row of that page has an explicit anchor (`<a id="row">`), and a
source that handles such a site says so in a comment, `overflow-improvements.md#row`. This guard holds the two in
step:

  * every `overflow-improvements.md#row` in a main source names a row of the page;
  * every row of the page is named by some main source;
  * a wrap idiom in a main source names a row nearby: in code, a modulus of 2^16, 2^32 or 2^64
    (`Word64Modulus`, `.mod(BigInt(1) << 64)`, `& UInt64.Max`) or a wrapping helper, with the reference within
    the 15 lines before it or the 3 after; in a comment, `modulo 2^16`, `2^32`, `2^64` or `2^`bits``, or a
    word of the family `wrap` in a comment that speaks of a machine word (`Word64`, `Word32`, `Word16`, a
    machine `Int`, `2^64`), with the reference in the same comment.

`ALLOWED` lists the few comments where the word means something else; each entry says why.

    python3 scripts/check_overflow_register.py

Pure standard library.
"""
from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
PAGE = 'docs/compatibility/overflow-improvements.md'
ANCHOR = re.compile(r'<a id="([a-z0-9-]+)"></a>')
REFERENCE = re.compile(r'overflow-improvements\.md#([a-z0-9-]+)')
CODE_IDIOM = re.compile(
    r'Word64Modulus|\bwrapping(?:Add|Subtract|Multiply)\b'
    r'|\.mod\(\s*(?:BigInt\(1\)\s*<<\s*(?:16|32|64)|BigInt\(2\)\.pow\((?:16|32|64)\))'
    r'|&\s*UInt64\.Max\b'
)
MODULO = re.compile(r'modulo 2\^(?:16|32|64|`)')
WRAP = re.compile(r'\bwrap(?:s|ped|ping)?\b')
MACHINE_WORD = re.compile(r'Word(?:16|32|64)|machine `?Int|`Int`|2\^(?:16|32|64)')
NOT_ARITHMETIC = re.compile(r'Bytes\.wrap|\bwrapper')
BEFORE, AFTER = 15, 3

# (path, text of the line): the comments where "wrap" does not mean a machine word's modulus.
ALLOWED: frozenset[tuple[str, str]] = frozenset({
    # "the types that wrap a Word64": the opaque types that hold one
    ('ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/state/Word64.scala',
     '/** The unsigned order, as Haskell\'s `Ord Word64`. Explicit: the types that wrap a `Word64` are'),
    # the successors are `None` at 2^64 - 1: a refusal, no wrap (#822)
    ('ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/state/Vocabulary.scala',
     '/** The following epoch; `None` at 2^64 - 1 (`Enum EpochNo` would wrap). */'),
    ('ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/state/Word64.scala',
     "/** `w + 1`, or `None` at 2^64 - 1 (Haskell's `Word64` would wrap; the mirror refuses). */"),
    # the node's own buffer sizes, summed in a `Long` so that they do not wrap; no value of the reference
    ('network/src/main/scala/xyz/kd5ujc/scarab/network/ReceiveBuffer.scala',
     '* `Int.MaxValue` after partial consumption (`end` far above `live`), where an `Int` sum wraps'),
})


@dataclass(frozen=True)
class Violation:
    path: str
    line: int
    message: str

    def __str__(self) -> str:
        return f'{self.path}:{self.line}: {self.message}'


def is_comment(line: str) -> bool:
    text = line.strip()
    return text.startswith('//') or text.startswith('*') or text.startswith('/*')


def comment_block(lines: Sequence[str], i: int) -> tuple[int, int]:
    """The first and last line of the run of comment lines that holds line `i`."""
    start, end = i, i
    while start > 0 and is_comment(lines[start - 1]):
        start -= 1
    while end + 1 < len(lines) and is_comment(lines[end + 1]):
        end += 1
    return start, end


def references(lines: Sequence[str], start: int, end: int) -> bool:
    return any(REFERENCE.search(line) for line in lines[max(0, start):end + 1])


def idioms(path: str, text: str) -> list[Violation]:
    """The wrap idioms of one source that name no row nearby."""
    lines = text.splitlines()
    out: list[Violation] = []
    for i, line in enumerate(lines):
        if (path, line.strip()) in ALLOWED:
            continue
        if is_comment(line):
            start, end = comment_block(lines, i)
            arithmetic = MODULO.search(line) or (
                WRAP.search(line) and not NOT_ARITHMETIC.search(line)
                and any(MACHINE_WORD.search(other) for other in lines[start:end + 1]))
            if arithmetic and not references(lines, start, end):
                out.append(Violation(path, i + 1, 'a comment on a wrap names no row of ' + PAGE))
        elif CODE_IDIOM.search(line.split('//')[0]) and not references(lines, i - BEFORE, i + AFTER):
            out.append(Violation(path, i + 1, 'a modular idiom names no row of ' + PAGE))
    return out


def sources(root: Path) -> list[Path]:
    return sorted(p for p in root.glob('*/src/main/**/*.scala') if p.is_file())


def check(root: Path) -> list[Violation]:
    page = root / PAGE
    if not page.is_file():
        return [Violation(PAGE, 0, 'the overflow register is missing')]
    rows = set(ANCHOR.findall(page.read_text(encoding='utf-8')))
    named: set[str] = set()
    out: list[Violation] = []
    for file in sources(root):
        path = file.relative_to(root).as_posix()
        text = file.read_text(encoding='utf-8')
        for number, line in enumerate(text.splitlines(), 1):
            for row in REFERENCE.findall(line):
                named.add(row)
                if row not in rows:
                    out.append(Violation(path, number, f'names "{row}", which is no row of {PAGE}'))
        out.extend(idioms(path, text))
    out.extend(Violation(PAGE, 0, f'row "{row}" is named by no main source') for row in sorted(rows - named))
    return out


def main(argv: Sequence[str] = (), root: Path = ROOT) -> int:
    violations = check(root)
    for violation in violations:
        print(violation)
    if violations:
        print(f'{len(violations)} overflow register problem(s); see {PAGE}')
        return 1
    print('overflow register: every wrap site names its row, and every row is named')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
