#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A per-file count of sites that only goes down, the check shared by the guards whose baseline only shrinks.

A guard finds its sites (the lines of each file where its rule is broken) and hands them to `check` with its
`Baseline`. The counts are compared with the committed baseline, a JSON file under `docs/data/`: a file may not go
up, a file not in the baseline must have none, and a count that went down fails until the change also commits
the smaller baseline, so the allowance a removed site leaves cannot be used again. `--write-baseline` records
lower counts, or the first baseline, and refuses any increase. An increase is reported with every site of
the file, since the text alone cannot say which of them is the new one.

`check_imperative.py` and `check_test_sleeps.py` keep their own copies of this logic, with a count per keyword.

Pure standard library.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import json

Sites = dict[str, list[int]]


@dataclass(frozen=True)
class Baseline:
    """`path` is the repository-relative path of the JSON file; `sites` names what is counted (plural);
    `refusal` is the first line printed when a count goes up: the rule and where it is written down;
    `comment` is the `_comment` written into the file."""
    path: str
    sites: str
    refusal: str
    comment: str


def read(root: Path, baseline: Baseline) -> dict[str, int]:
    path = root / baseline.path
    return json.loads(path.read_text())['files'] if path.is_file() else {}


def compare(now: dict[str, int], was: dict[str, int]) -> tuple[list[str], list[str]]:
    """(the files whose count went up, the files whose count went down)."""
    increases, decreases = [], []
    for path in sorted(set(now) | set(was)):
        before, after = was.get(path, 0), now.get(path, 0)
        if after > before:
            increases.append(path)
        elif after < before:
            decreases.append(path)
    return increases, decreases


def write(root: Path, baseline: Baseline, now: dict[str, int]) -> None:
    body = {'_comment': baseline.comment, 'files': dict(sorted(now.items()))}
    (root / baseline.path).write_text(json.dumps(body, indent=1) + '\n')


def check(root: Path, baseline: Baseline, sites: Sites, write_baseline: bool) -> int:
    """Prints the outcome and returns the exit status."""
    now = {path: len(lines) for path, lines in sites.items() if lines}
    was = read(root, baseline)
    increases, decreases = compare(now, was)
    total = sum(now.values())

    def change(path: str) -> str:
        return f'  {path}: {was.get(path, 0)} -> {now.get(path, 0)}'

    if write_baseline and not (root / baseline.path).is_file():
        write(root, baseline, now)
        print(f'wrote the first {baseline.path}: {total} {baseline.sites} in {len(now)} files')
        return 0
    if increases:
        print(baseline.refusal)
        for path in increases:
            print(change(path))
            print('\n'.join(f'    {path}:{line}' for line in sites[path]))
        return 1
    if write_baseline:
        write(root, baseline, now)
        print(f'wrote {baseline.path}: {total} {baseline.sites} in {len(now)} files')
        return 0
    if decreases:
        print(f'fewer {baseline.sites} than {baseline.path} allows: run with --write-baseline and commit it:')
        print('\n'.join(change(path) for path in decreases))
        return 1
    print(f'{baseline.sites}: {total} in {len(now)} files, none new')
    return 0
