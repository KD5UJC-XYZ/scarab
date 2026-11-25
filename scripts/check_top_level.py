#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a tracked file lies in a top-level directory that is not on the list of this guard.

The top-level directories are the sbt modules and the few homes the documents name (`docs`, `scripts`, `changes`,
...). A new one is most often what a workflow tool writes for its own use: a spec, a plan, a task list or a
validation log, for example a `specs/` directory. Those are working notes, and stay out of the repository
(docs/process.md, "One place per question", says where their content goes instead). The guard reads the tracked
files (`git ls-files`), so an ignored directory (`.workspace`, `target`, `.cache`) never counts; a file at the top
level is not its concern. A real new top-level directory is added to `DIRECTORIES` in the pull request that adds
it.

    python3 scripts/check_top_level.py    exit 0 when every tracked top-level directory is listed, 1 when one is not

Pure standard library.
"""
from __future__ import annotations

from collections.abc import Iterable
from pathlib import Path
import argparse
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
# The tracked top-level directories. `flake` and `perSystem` hold the Nix flake's modules of PR #1428, which is
# expected to land before this guard.
DIRECTORIES = frozenset({
    # the sbt modules of build.sbt, and the build's own definition
    'codec', 'consensus', 'crypto', 'fuzz', 'ledger', 'models', 'models-gen', 'network', 'node', 'plutus',
    'protocol', 'storage', 'tools', 'uplc', 'warts', 'project',
    # workflows, images, scripts, release policy and operations
    '.github', '.semgrep', 'antithesis', 'docker', 'ops', 'release', 'scripts',
    # documents, changelog fragments, fixtures and licence texts
    'changes', 'docs', 'fixtures', 'licenses',
    # the Nix flake
    'flake', 'perSystem',
})
HELP = ('A spec, plan, task list or validation log that a workflow tool writes is a working note: keep it outside\n'
        'the checkout or in an ignored directory, and put what a later reader needs where docs/process.md\n'
        '("One place per question") says: an approved plan in a design document, its tasks in issues, its\n'
        'evidence in the pull request. A real new top-level directory is added to DIRECTORIES in\n'
        'scripts/check_top_level.py, in the pull request that adds it.')


def unlisted(paths: Iterable[str], listed: frozenset[str] = DIRECTORIES) -> dict[str, list[str]]:
    """Each top-level directory that is not listed, with its tracked files in order, sorted by directory."""
    found: dict[str, list[str]] = {}
    for path in paths:
        head, slash, _ = path.partition('/')
        if slash and head not in listed:
            found.setdefault(head, []).append(path)
    return {head: sorted(files) for head, files in sorted(found.items())}


def tracked(root: Path) -> list[str]:
    names = subprocess.run(['git', '-C', str(root), 'ls-files', '-z'], check=True, capture_output=True).stdout
    return [os.fsdecode(name) for name in names.split(b'\0') if name]


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    argparse.ArgumentParser(description=__doc__.split('\n\n')[0]).parse_args(argv)
    found = unlisted(tracked(root))
    if found:
        print('top-level directories that are not on the list of scripts/check_top_level.py:')
        for head, files in found.items():
            count = f'{len(files)} tracked file' + ('' if len(files) == 1 else 's')
            print(f'  {head}/: {count}, such as {files[0]}')
        print(HELP)
        return 1
    print(f'every tracked top-level directory is on the list ({len(DIRECTORIES)} listed)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
