#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""No new try/catch/finally in main Scala sources (issue 650).

WartRemover refuses the other disallowed patterns at compile time (`build.sbt`, `disallowedWarts`), but it has
no wart for `try`, `catch` or `finally`. Effects use `Resource`, `bracket` and `attempt` instead; a performance
path keeps imperative code only with measured evidence (AGENTS.md). This guard counts the three keywords per
main Scala source, with comments and the text of string literals removed (the `${...}` expressions of an
interpolated string count), and compares the counts with the committed baseline
`docs/data/imperative-baseline.json`: a file may not go up, a file not in the baseline must have none, and a
count that went down fails until the change also commits the smaller baseline. The baseline only shrinks.

    python3 scripts/check_imperative.py                    check (the default)
    python3 scripts/check_imperative.py --write-baseline   record lower counts (or the first baseline); refuses any increase

Pure standard library.
"""
from __future__ import annotations

from pathlib import Path
import argparse
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BASELINE = 'docs/data/imperative-baseline.json'
KEYWORDS = ('try', 'catch', 'finally')
KEYWORD = re.compile(r'\b(try|catch|finally)\b')


def code(source: str) -> str:
    """`source` with comments and the text of string and character literals removed, line breaks kept.
    A comment becomes a space, so the tokens on either side stay apart; the `${...}` expressions of an
    interpolated string are code and are kept."""
    return scan(source, 0, False)[1]


def scan(source: str, i: int, expression: bool) -> tuple[int, str]:
    """Code from `i`: to the end of `source`, or, in an interpolation's `${...}` (`expression`), to just after
    its closing brace. Braces in comments and literals do not count, and literals nest."""
    out: list[str] = []
    depth, n = 0, len(source)
    while i < n:
        if source.startswith('/*', i):
            end = comment_end(source, i)
            out.append(' ' + '\n' * source.count('\n', i, end))
            i = end
        elif source.startswith('//', i):
            end = source.find('\n', i)
            out.append(' ')
            i = n if end < 0 else end
        elif source[i] == '"':
            interpolated = i > 0 and (source[i - 1].isalnum() or source[i - 1] == '_')
            triple = source.startswith('"""', i)
            i, kept = string_literal(source, i + (3 if triple else 1), triple, interpolated)
            out.append(' ' + kept + ' ')
        elif source[i] == "'" and i + 2 < n and (source[i + 2] == "'" or source[i + 1] == '\\'):
            end = source.find("'", i + 2)
            i = n if end < 0 else end + 1
        elif expression and source[i] == '}' and depth == 0:
            return i + 1, ''.join(out)
        else:
            depth += {'{': 1, '}': -1}.get(source[i], 0)
            out.append(source[i])
            i += 1
    return i, ''.join(out)


def comment_end(source: str, i: int) -> int:
    """The index after the block comment that opens at `i`; Scala block comments nest."""
    depth, n = 0, len(source)
    while i < n:
        if source.startswith('/*', i):
            depth, i = depth + 1, i + 2
        elif source.startswith('*/', i):
            depth, i = depth - 1, i + 2
            if depth == 0:
                return i
        else:
            i += 1
    return n


def string_literal(source: str, i: int, triple: bool, interpolated: bool) -> tuple[int, str]:
    """From just after the opening quote: the index after the closing quote, and the code of the
    literal's `${...}` expressions (with the line breaks of the literal)."""
    kept: list[str] = []
    n = len(source)
    while i < n:
        if triple and source.startswith('"""', i):
            i += 3
            while i < n and source[i] == '"':
                i += 1
            return i, ''.join(kept)
        if not triple and source[i] in '"\n':
            return i + 1, ''.join(kept)
        if interpolated and source.startswith('${', i):
            i, expression = scan(source, i + 2, True)
            kept.append(' ' + expression + ' ')
        elif interpolated and source.startswith('$$', i):
            i += 2
        elif source[i] == '\\' and not triple:
            i += 2
        else:
            if source[i] == '\n':
                kept.append('\n')
            i += 1
    return i, ''.join(kept)


def counts(text: str) -> dict[str, int]:
    found = {k: 0 for k in KEYWORDS}
    for match in KEYWORD.finditer(code(text)):
        found[match.group(1)] += 1
    return {k: v for k, v in found.items() if v}


def main_sources(root: Path) -> list[str]:
    listed = subprocess.run(['git', '-C', str(root), 'ls-files', '*.scala'], check=True, capture_output=True,
                            text=True).stdout.split()
    return sorted(p for p in listed if '/src/main/' in p and '/src_managed/' not in p)


def current(root: Path) -> dict[str, dict[str, int]]:
    found = {path: counts((root / path).read_text(encoding='utf-8')) for path in main_sources(root)}
    return {path: c for path, c in found.items() if c}


def read_baseline(root: Path) -> dict[str, dict[str, int]]:
    path = root / BASELINE
    return json.loads(path.read_text())['files'] if path.is_file() else {}


def compare(now: dict[str, dict[str, int]], baseline: dict[str, dict[str, int]]) -> tuple[list[str], list[str]]:
    """(increases, decreases), one line each."""
    increases, decreases = [], []
    for path in sorted(set(now) | set(baseline)):
        for keyword in KEYWORDS:
            was, is_now = baseline.get(path, {}).get(keyword, 0), now.get(path, {}).get(keyword, 0)
            if is_now > was:
                increases.append(f'{path}: {keyword} {was} -> {is_now}')
            elif is_now < was:
                decreases.append(f'{path}: {keyword} {was} -> {is_now}')
    return increases, decreases


def write_baseline(root: Path, now: dict[str, dict[str, int]]) -> None:
    body = {'_comment': 'try/catch/finally per main Scala source; written by scripts/check_imperative.py '
                        '--write-baseline, which refuses an increase. It only shrinks (issue 650).',
            'files': now}
    (root / BASELINE).write_text(json.dumps(body, indent=1, sort_keys=True) + '\n')


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--write-baseline', action='store_true', help='record lower counts; refuses any increase')
    args = parser.parse_args(argv)
    now, baseline = current(root), read_baseline(root)
    increases, decreases = compare(now, baseline)
    # the first baseline records what there is; every later one may only go down
    if args.write_baseline and not (root / BASELINE).is_file():
        write_baseline(root, now)
        print(f'wrote the first {BASELINE}: {sum(sum(c.values()) for c in now.values())} in {len(now)} files')
        return 0
    if increases:
        print('new try/catch/finally in main sources (use Resource, bracket or attempt; issue 650):')
        print('\n'.join('  ' + line for line in increases))
        return 1
    if args.write_baseline:
        write_baseline(root, now)
        print(f'wrote {BASELINE}: {sum(sum(c.values()) for c in now.values())} in {len(now)} files')
        return 0
    if decreases:
        # the baseline shrinks with the change that removes the code, so no allowance is left to reuse
        print('fewer try/catch/finally than the baseline allows: run with --write-baseline and commit it:')
        print('\n'.join('  ' + line for line in decreases))
        return 1
    total = sum(sum(c.values()) for c in now.values())
    print(f'imperative check passes: {total} try/catch/finally in {len(now)} main sources, none new')
    return 0


if __name__ == '__main__':
    sys.exit(main())
