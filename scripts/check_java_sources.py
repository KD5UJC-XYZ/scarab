#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The Java main sources are a recorded list, and the count of `throw` in each only goes down (#1418).

The Java main sources of `crypto` are an exception to the functional-style rules (docs/design-rules.md, FUN-2
(f)), and no wart reads Java. This guard holds three things about them that review alone held before:

  * The Java main sources, every tracked `.java` file under a `src/main/` directory anywhere in the repository
    (of any module, `tools` included, at the root too, whatever the directories under it are named, and
    `package-info.java` too), are exactly those that `docs/data/java-sources.json` lists. A source added or
    removed fails until the list is changed in the same change, by hand, where review sees it.
  * No Java main source holds a Unicode escape: a backslash, one or more `u` and four hexadecimal digits,
    anywhere, comments and literals included. javac translates them before it reads anything else, so an
    escape can spell `throw`, or end a comment with an escaped line break, where this guard's reading sees
    neither. It refuses every such sequence, also one whose backslash is itself escaped (`"\\u0041"`), which
    javac would not translate: the rule of the backslashes before it is not applied.
  * The `throw` statements of each are counted, with comments, string and character literals and text blocks
    removed, and compared with the committed baseline `docs/data/java-throw-baseline.json` (`shrinking_baseline`):
    a file may not go up, a file not in the baseline must have none, and a count that went down fails until the
    change also commits the smaller baseline. So a file's count cannot rise unless the change rewrites the
    baseline as well, a diff a reviewer sees.

What it does not see: a `throw` replaced by another in the same file, which leaves the count as it was; an
exception that a call throws (a JDK method, Bouncy Castle, an array index) and not a `throw` of these sources; a
`throws` clause, which declares and throws nothing; and whether a `throw` can be reached from outside input,
which `EntryPointTotalitySuite` (crypto tests) and review hold. The Scala that calls these sources is the warts'
and `check_imperative.py`'s.

    python3 scripts/check_java_sources.py                    check (the default)
    python3 scripts/check_java_sources.py --write-baseline   record lower throw counts (or the first baseline);
                                                             refuses any increase, and a source not on the list

Pure standard library.
"""
from __future__ import annotations

from pathlib import Path
import argparse
import json
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shrinking_baseline  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SOURCES = 'docs/data/java-sources.json'
# where the rule is written down, named in the refusals
RULE = 'docs/design-rules.md#fun-2'
BASELINE = shrinking_baseline.Baseline(
    path='docs/data/java-throw-baseline.json',
    sites='throw statements in Java main sources',
    refusal=f'more throw statements in Java main sources than the baseline allows: return a value the caller '
            f'types instead ({RULE}):',
    comment='throw statements per Java main source, comments and literals aside; written by '
            'scripts/check_java_sources.py --write-baseline, which refuses an increase. It only shrinks (#1418).')
# `throw` as a word of Java, which a `$` in an identifier does not end
THROW = re.compile(r'(?<![\w$])throw(?![\w$])')
# a Unicode escape (JLS 3.3), whatever the backslashes before it
UNICODE_ESCAPE = re.compile(r'\\u+[0-9a-fA-F]{4}')


def code(source: str) -> str:
    """`source` with its comments, string and character literals and text blocks blanked, line breaks kept, so
    that a line of the result is the line of the source. Java block comments do not nest."""
    out: list[str] = []
    i, n = 0, len(source)

    def blank(end: int) -> None:
        out.append(re.sub(r'[^\n]', ' ', source[i:end]))

    while i < n:
        if source.startswith('//', i):
            end = source.find('\n', i)
            end = n if end < 0 else end
        elif source.startswith('/*', i):
            end = source.find('*/', i + 2)
            end = n if end < 0 else end + 2
        elif source.startswith('"""', i):
            end = literal_end(source, i + 3, '"""')
        elif source[i] in '"\'':
            end = literal_end(source, i + 1, source[i])
        else:
            out.append(source[i])
            i += 1
            continue
        blank(end)
        i = end
    return ''.join(out)


def literal_end(source: str, i: int, close: str) -> int:
    """The index after the literal whose text starts at `i` and ends with `close`; a backslash escapes the
    character after it. A string or character literal also ends at a line break, as the compiler refuses one
    that runs on."""
    n = len(source)
    while i < n:
        if source[i] == '\\':
            i += 2
        elif source.startswith(close, i):
            return i + len(close)
        elif source[i] == '\n' and close != '"""':
            return i
        else:
            i += 1
    return n


def sites(source: str) -> list[int]:
    """The line of each `throw` statement."""
    text = code(source)
    return [text.count('\n', 0, match.start()) + 1 for match in THROW.finditer(text)]


def escapes(source: str) -> list[int]:
    """The line of each Unicode escape, in the text as it is."""
    return [source.count('\n', 0, match.start()) + 1 for match in UNICODE_ESCAPE.finditer(source)]


def under_main(path: str) -> bool:
    """Whether the repository-relative `path` lies under a `src/main/` directory, at any depth."""
    parts = path.split('/')
    return any(parts[i:i + 2] == ['src', 'main'] for i in range(len(parts) - 2))


def java_main_sources(root: Path) -> list[str]:
    listed = subprocess.run(['git', '-C', str(root), 'ls-files', '-z', '*.java'], check=True,
                            capture_output=True, text=True).stdout.split('\0')
    return sorted(p for p in listed if p and under_main(p))


def recorded(root: Path) -> list[str] | None:
    """The list of `SOURCES`, or None when the file is missing or is not an object with a list of paths."""
    path = root / SOURCES
    if not path.is_file():
        return None
    body = json.loads(path.read_text())
    sources = body.get('sources') if isinstance(body, dict) else None
    if not isinstance(sources, list) or not all(isinstance(s, str) for s in sources):
        return None
    return sources


def list_problems(present: list[str], listed: list[str] | None) -> list[str]:
    """One line for each way the Java main sources differ from the list; nothing when they are the list."""
    if listed is None:
        return [f'{SOURCES} is missing, or has no "sources" list of paths']
    found = [f'listed twice: {p}' for p in sorted({p for p in listed if listed.count(p) > 1})]
    found += [f'not listed: {p}' for p in sorted(set(present) - set(listed))]
    found += [f'listed, and no Java main source: {p}' for p in sorted(set(listed) - set(present))]
    return found


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--write-baseline', action='store_true',
                        help='record lower throw counts; refuses any increase')
    args = parser.parse_args(argv)
    present = java_main_sources(root)
    problems = list_problems(present, recorded(root))
    if problems:
        print(f'the Java main sources are not those {SOURCES} lists: a Java main source is an exception of '
              f'{RULE} (f), and the list changes with it, by hand:')
        print('\n'.join('  ' + line for line in problems))
        return 1
    print(f'Java main sources: {len(present)}, each listed in {SOURCES}')
    texts = {path: (root / path).read_text(encoding='utf-8') for path in present}
    escaped = [f'  {path}:{line}' for path, text in texts.items() for line in escapes(text)]
    if escaped:
        print('Unicode escapes in Java main sources, which javac reads before this guard can count throw '
              f'statements in them: write the character itself, or name it another way ({RULE} (f)):')
        print('\n'.join(escaped))
        return 1
    found = {path: sites(text) for path, text in texts.items()}
    return shrinking_baseline.check(root, BASELINE, {p: lines for p, lines in found.items() if lines},
                                    args.write_baseline)


if __name__ == '__main__':
    sys.exit(main())
