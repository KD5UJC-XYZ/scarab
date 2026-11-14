#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""No new sleeps in tests (issue 870).

A test that sleeps bets on scheduling: a sleep that orders two fibers, waits for a background state or paces a
poll loop fails on a loaded runner and hides a real race behind the same observable (#632 was one). A test
synchronizes on an observable condition instead: a `Deferred`, a latch, a `Ref`'s change signal, a report or
callback of the code under test, or `TestControl` for anything that is about time. This guard counts, per Scala
source outside `src/main` (suites, their helpers and test-tree tools), the calls that wait for time or yield in
place of a condition: `sleep`, `delayBy`, `andWait`, the fs2 timers (`awakeEvery`, `awakeDelay`, `fixedDelay`,
`fixedRate`, `metered`, `spaced`), `parkNanos`, `onSpinWait`, `cede` and `Thread.yield`, and the two sleep-poll
helpers `Wait.until` and `WorkerTesting.deliveredBody`, whose callers contain no sleep of their own. A method
counts where it is called on something (`IO.sleep`, `stream.metered`), and `sleep` and `parkNanos` also where
they are called bare after an import, so that a value, a definition or an import with the same name does not.
Comments and the text of string literals are removed first (`check_imperative.code`). A poll without any of
these (`ref.get.iterateUntil(p)`) is not seen.

The text of a source cannot tell a sleep on `TestControl`'s virtual clock from one on the wall clock, so every
site counts. The counts are compared with the committed baseline `docs/data/test-sleep-baseline.json`: a file
may not go up, a file not in the baseline must have none, and a count that went down fails until the change
also commits the smaller baseline. The baseline only shrinks.

    python3 scripts/check_test_sleeps.py                    check (the default)
    python3 scripts/check_test_sleeps.py --write-baseline   record lower counts (or the first baseline); refuses any increase

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
from check_imperative import code  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
BASELINE = 'docs/data/test-sleep-baseline.json'
# methods, counted where they are called on something (`IO.sleep`, `stream.metered`), and `sleep` and `parkNanos`
# also where they are called bare after an import (`sleep(d)` after `import IO.*`); a value with the same name
# (`val spaced`), a definition (a simulated scheduler's `def sleep`) and the import itself are not calls
METHODS = ('sleep', 'delayBy', 'andWait', 'awakeEvery', 'awakeDelay', 'fixedDelay', 'fixedRate',
           'fixedRateStartImmediately', 'metered', 'meteredStartImmediately', 'spaced', 'parkNanos', 'onSpinWait',
           'cede', 'yield')
# the sleep-poll helpers of the test tree, however they are called
HELPERS = ('Wait.until', 'deliveredBody')
NAMES = '|'.join(METHODS)
# the ones a test imports and calls bare; the others share their names with values (`val metered`)
BARE = ('sleep', 'parkNanos')
CALL = re.compile(r'\.\s*`?\b(' + NAMES + r')\b`?'
                  r'|(?<![.\w`])(' + '|'.join(BARE) + r')\s*[\[(]'
                  r'|\b(' + '|'.join(re.escape(h).replace(r'\.', r'\s*\.\s*') for h in HELPERS) + r')\b')
DEFINITION = re.compile(r'\bdef\s+$')
IMPORT = re.compile(r'^[ \t]*import\b.*$', re.MULTILINE)


def counts(text: str) -> dict[str, int]:
    found: dict[str, int] = {}
    source = IMPORT.sub('', code(text))
    for match in CALL.finditer(source):
        name = match.group(1) or match.group(2) or match.group(3)
        if match.group(2) and DEFINITION.search(source, max(0, match.start() - 16), match.start()):
            continue
        name = re.sub(r'\s', '', name)
        found[name] = found.get(name, 0) + 1
    return found


def test_sources(root: Path) -> list[str]:
    listed = subprocess.run(['git', '-C', str(root), 'ls-files', '*.scala'], check=True, capture_output=True,
                            text=True).stdout.split()
    return sorted(p for p in listed if '/src/' in p and '/src/main/' not in p and '/src_managed/' not in p)


def current(root: Path) -> dict[str, dict[str, int]]:
    found = {path: counts((root / path).read_text(encoding='utf-8')) for path in test_sources(root)}
    return {path: c for path, c in found.items() if c}


def read_baseline(root: Path) -> dict[str, dict[str, int]]:
    path = root / BASELINE
    return json.loads(path.read_text())['files'] if path.is_file() else {}


def compare(now: dict[str, dict[str, int]], baseline: dict[str, dict[str, int]]) -> tuple[list[str], list[str]]:
    """(increases, decreases), one line each."""
    increases, decreases = [], []
    for path in sorted(set(now) | set(baseline)):
        for call in sorted(set(now.get(path, {})) | set(baseline.get(path, {}))):
            was, is_now = baseline.get(path, {}).get(call, 0), now.get(path, {}).get(call, 0)
            if is_now > was:
                increases.append(f'{path}: {call} {was} -> {is_now}')
            elif is_now < was:
                decreases.append(f'{path}: {call} {was} -> {is_now}')
    return increases, decreases


def total(found: dict[str, dict[str, int]]) -> int:
    return sum(sum(c.values()) for c in found.values())


def write_baseline(root: Path, now: dict[str, dict[str, int]]) -> None:
    body = {'_comment': 'sleeps, timers and yields per Scala source outside src/main; written by '
                        'scripts/check_test_sleeps.py --write-baseline, which refuses an increase. It only shrinks '
                        '(issue 870).',
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
        print(f'wrote the first {BASELINE}: {total(now)} in {len(now)} files')
        return 0
    if increases:
        print('new sleeps in tests: synchronize on an observable condition (a Deferred, a latch, a change signal, '
              'a report of the code under test) or use TestControl for time (issue 870):')
        print('\n'.join('  ' + line for line in increases))
        return 1
    if args.write_baseline:
        write_baseline(root, now)
        print(f'wrote {BASELINE}: {total(now)} in {len(now)} files')
        return 0
    if decreases:
        # the baseline shrinks with the change that removes the sleep, so no allowance is left to reuse
        print('fewer sleeps in tests than the baseline allows: run with --write-baseline and commit it:')
        print('\n'.join('  ' + line for line in decreases))
        return 1
    print(f'test sleep check passes: {total(now)} sleeps, timers and yields in {len(now)} test sources, none new')
    return 0


if __name__ == '__main__':
    sys.exit(main())
