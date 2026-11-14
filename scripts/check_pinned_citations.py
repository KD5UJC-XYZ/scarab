#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""No new unpinned upstream citation in a Scala comment.

AGENTS.md (How code is written here): mirror the reference and "cite the upstream file and line at the pinned
version"; refer to pinned upstream sources "rather than ... unpinned line numbers". A line number means nothing
without the revision it was read at, and a reader cannot tell which revision an earlier comment in the file
meant once the file cites more than one upstream repository. This guard reads the comments of every tracked
`.scala` and `.sbt` file (`scala_comments.scala_sources`: the archived programs under `fixtures/` are
recorded provenance and are left as they are) and counts the comment blocks that cite a Haskell file and line
(`Foo.hs:123`, `Foo/Bar.hs:12-34`) and carry no pin in the same block. A block is a `/* ... */` or
`/** ... */` comment, or a run of `//` comments on consecutive lines (`scala_comments.blocks`). A pin is one
of the forms the tree already writes:

  * a revision of 7 to 40 hexadecimal digits, at least one of them a decimal digit and one a letter, as
    `check_docs.py` reads a commit id (`cardano-ledger f649f975`, `c45735a5:`, `@f649f975`, `@ 8e2bba94...`);
  * a released package, its name joined to its version by a hyphen (`plutus-1.70.0.0`, `ghc-9.6`), or by a
    space when the version has three parts or more (`cardano-node 11.1.3`, `GHC 9.6.7`, `aeson 2.2.5.0`);
  * `@` and a version of three parts or more (`@ 11.1.3`).

The SPDX header's `Apache-2.0` is not a pin. Strings and string interpolations are not comments, so a test name
that cites a file is not read. The check is lexical, so it can misfire:

  * Any hexadecimal word of 7 to 40 digits counts as a pin, including a shortened transaction id or block hash
    (`transaction 49be5965c31a60c3`), and so does any word followed by a three-part version, including prose
    (`version 1.1.0`, `than 1.1.0`), a section number or an address. These under-report; they never flag.
  * One pin covers every citation in its block, even when the block cites files of two repositories.
  * A pin elsewhere in the file, such as a file-level comment that names the revision once for all its
    citations, does not count: the rule is the block (`unpinned`).
  * A citation of a `.lhs`, `.agda` or `.cabal` file, or one written without a line (`Foo.hs`), is not read.

The counts are compared with the committed baseline `docs/data/pinned-citation-baseline.json`, which only
shrinks (`shrinking_baseline`).

    python3 scripts/check_pinned_citations.py                    check (the default)
    python3 scripts/check_pinned_citations.py --write-baseline   record lower counts; refuses any increase

Pure standard library.
"""
from __future__ import annotations

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
    path='docs/data/pinned-citation-baseline.json',
    sites='comment blocks that cite a Haskell file and line with no pin',
    refusal='new unpinned upstream citations: a comment that cites a Haskell file and line names the pinned '
            'revision or release in the same comment, for example `(Foo.hs:12-34, cardano-ledger f649f975)` '
            f'({RULE}):',
    comment='comment blocks per Scala or sbt source that cite a Haskell file and line with no pin in the same '
            'block; written by scripts/check_pinned_citations.py --write-baseline, which refuses an increase. '
            'It only shrinks.')
CITATION = re.compile(r"(?<![\w/.'-])[\w/.'-]*\w\.hs:\d+")
SPDX = re.compile(r'SPDX-License-Identifier:[^\n]*')
PIN = re.compile(
    r"(?<![\w-])(?=[a-f]*[0-9])(?=[0-9]*[a-f])[0-9a-f]{7,40}(?![\w-])"
    r"|(?<![\w.-])[A-Za-z][\w-]*-\d+(?:\.\d+)+(?![\w.])"
    r"|(?<![\w.-])[A-Za-z][\w-]* v?\d+\.\d+\.\d+(?:\.\d+)*(?![\w.])"
    r"|@\s*v?\d+\.\d+\.\d+(?:\.\d+)*(?![\w.])")


def pinned(text: str) -> bool:
    return PIN.search(SPDX.sub('', text)) is not None


def unpinned(source: str) -> list[int]:
    """The line of the first citation of each comment block that cites a Haskell file and line with no pin."""
    found = []
    for block in scala_comments.blocks(source, scala_comments.split(source).comments):
        citation = CITATION.search(block.text)
        if citation and not pinned(block.text):
            found.append(block.line + block.text.count('\n', 0, citation.start()))
    return found


def current(root: Path) -> shrinking_baseline.Sites:
    listed = subprocess.run(['git', '-C', str(root), 'ls-files'], check=True, capture_output=True,
                            text=True).stdout.split('\n')
    found = {path: unpinned((root / path).read_text(encoding='utf-8'))
             for path in scala_comments.scala_sources(listed)}
    return {path: lines for path, lines in found.items() if lines}


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--write-baseline', action='store_true', help='record lower counts; refuses any increase')
    args = parser.parse_args(argv)
    return shrinking_baseline.check(root, BASELINE, current(root), args.write_baseline)


if __name__ == '__main__':
    sys.exit(main())
