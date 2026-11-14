#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Runs the Python guards of `scripts/guards.json`, the one list that `scripts/preflight` and CI share.

    python3 scripts/guards.py            run every guard, each to its end; exit 1 when any failed, naming them
    python3 scripts/guards.py --list     one line per guard: its id, a tab and its title
    python3 scripts/guards.py --run ID   run that guard and exit with its status

`scripts/preflight` runs each guard as a step of its own (`--list`, then `--run`); CI's scripts job runs them all
in one step, where (`GITHUB_ACTIONS=true`) each guard's output is a collapsible group. A guard runs with the
interpreter that runs this script, from the repository root. A new guard is registered in the JSON file and
nowhere else. The registry is checked when it is read: every guard has a unique id of lower-case words joined
by hyphens, a title, and a command whose first word is a script under `scripts/` that exists; a registry that
fails the check runs nothing and exits 2.

Pure standard library.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = 'scripts/guards.json'
ID = re.compile(r'[a-z0-9]+(?:-[a-z0-9]+)*')


@dataclass(frozen=True)
class Guard:
    id: str
    title: str
    argv: tuple[str, ...]


def problems(root: Path, entries: list[dict]) -> list[str]:
    found, seen = [], set()
    for entry in entries:
        name = entry.get('id')
        argv = entry.get('argv')
        if not isinstance(name, str) or not ID.fullmatch(name):
            found.append(f'{name!r}: an id is lower-case words joined by hyphens')
        elif name in seen:
            found.append(f'{name}: registered twice')
        seen.add(name)
        if not isinstance(entry.get('title'), str) or not entry['title'] or '\t' in entry['title']:
            found.append(f'{name}: a title is a line of text with no tab')
        if (not isinstance(argv, list) or not argv or not all(isinstance(a, str) for a in argv)
                or not argv[0].startswith('scripts/') or not (root / argv[0]).is_file()):
            found.append(f'{name}: the command starts with a script under scripts/ that exists')
    return found


def load(root: Path) -> tuple[list[Guard], list[str]]:
    """The guards in their order, or the problems of the registry."""
    entries = json.loads((root / REGISTRY).read_text())['guards']
    trouble = problems(root, entries)
    if trouble:
        return [], trouble
    return [Guard(e['id'], e['title'], tuple(e['argv'])) for e in entries], []


def run(root: Path, guard: Guard) -> int:
    return subprocess.run([sys.executable, *guard.argv], cwd=root, check=False).returncode


def run_all(root: Path, guards: list[Guard], grouped: bool) -> int:
    failed = []
    for guard in guards:
        # preflight's form, `== title` and `ok` or `FAILED: title`; GitHub's, a group and an error annotation
        print(f'::group::{guard.title}' if grouped else f'== {guard.title}', flush=True)
        status = run(root, guard)
        if grouped:
            print('::endgroup::', flush=True)
        if status != 0:
            failed.append(guard.title)
            print(f'::error::{guard.title} failed' if grouped else f'   FAILED: {guard.title}', flush=True)
        elif not grouped:
            print('   ok', flush=True)
    if failed:
        print(f'\nguards FAILED: {"; ".join(failed)}')
        return 1
    print(f'\n{len(guards)} guards passed')
    return 0


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    choice = parser.add_mutually_exclusive_group()
    choice.add_argument('--list', action='store_true', help='print each guard id and title, tab-separated')
    choice.add_argument('--run', metavar='ID', help='run one guard')
    args = parser.parse_args(argv)
    guards, trouble = load(root)
    if trouble:
        print(f'{REGISTRY} is not a valid guard registry:', file=sys.stderr)
        print('\n'.join('  ' + line for line in trouble), file=sys.stderr)
        return 2
    if args.list:
        print('\n'.join(f'{g.id}\t{g.title}' for g in guards))
        return 0
    if args.run is not None:
        chosen = next((g for g in guards if g.id == args.run), None)
        if chosen is None:
            print(f'no guard {args.run!r} in {REGISTRY}', file=sys.stderr)
            return 2
        return run(root, chosen)
    return run_all(root, guards, os.environ.get('GITHUB_ACTIONS') == 'true')


if __name__ == '__main__':
    sys.exit(main())
