#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when a tracked path lies in a top-level directory that is not on the list of this guard.

The top-level directories are the sbt modules and the few homes the documents name (`docs`, `scripts`, `changes`,
...). A new one is most often what a workflow tool writes for its own use: a spec, a plan, a task list or a
validation log, for example a `specs/` directory. Those are working notes, and stay out of the repository
(docs/process.md, "One place per question", says where their content goes instead). The guard reads the index
(`git ls-files --stage`), so the untracked files of an ignored directory (`.workspace`, `target`, `.cache`) never
count, while a file added to one by force does. A file at the top level is not its concern; a submodule there (a
gitlink) is a directory. A path that is not printable is shown escaped. A real new top-level directory is added to
`DIRECTORIES` in the pull request that adds it.

    python3 scripts/check_top_level.py    exit 0 when every tracked top-level directory is listed, 1 when one is not

Pure standard library.
"""
from __future__ import annotations

from collections.abc import Iterable
from pathlib import Path
import argparse
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
# the mode of a submodule's entry in the index
GITLINK = b'160000 '
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
        'evidence in the pull request and wherever the definition of done asks. A real new top-level\n'
        'directory is added to DIRECTORIES in scripts/check_top_level.py, in the pull request that adds it.')


def unlisted(paths: Iterable[str], listed: frozenset[str] = DIRECTORIES, *,
             submodules: frozenset[str] = frozenset()) -> dict[str, list[str]]:
    """Each top-level directory that is not listed, with its tracked paths, each once and sorted, sorted by directory.
    A path in `submodules` is a directory of its own."""
    found: dict[str, set[str]] = {}
    for path in paths:
        head, slash, _ = path.partition('/')
        if (slash or path in submodules) and head not in listed:
            found.setdefault(head, set()).add(path)
    return {head: sorted(found[head]) for head in sorted(found)}


def index(listing: bytes) -> tuple[list[str], frozenset[str]]:
    """The paths of `git ls-files --stage -z` output (an unmerged path once per stage), and those that are
    submodules (mode 160000)."""
    paths, submodules = [], set()
    for record in filter(None, listing.split(b'\0')):
        meta, _, name = record.partition(b'\t')
        # git writes the bytes of the name; bytes that are not UTF-8 are kept as surrogates, whatever the locale
        paths.append(name.decode('utf-8', 'surrogateescape'))
        if meta.startswith(GITLINK):
            submodules.add(paths[-1])
    return paths, frozenset(submodules)


def tracked(root: Path) -> tuple[list[str], frozenset[str]]:
    # git's own error, if any, goes to standard error
    listing = subprocess.run(['git', '-C', str(root), 'ls-files', '--stage', '-z'], check=True,
                             stdout=subprocess.PIPE).stdout
    return index(listing)


def shown(path: str) -> str:
    """The path as it is, or escaped when it holds a control character or bytes that are not UTF-8."""
    return path if path.isprintable() else ascii(path)


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    argparse.ArgumentParser(description=__doc__.split('\n\n')[0]).parse_args(argv)
    paths, submodules = tracked(root)
    found = unlisted(paths, submodules=submodules)
    if found:
        print('top-level directories that are not on the list of scripts/check_top_level.py:')
        for head, files in found.items():
            count = f'{len(files)} tracked path' + ('' if len(files) == 1 else 's')
            print(f'  {shown(head)}/: {count}, such as {shown(files[0])}')
        print(HELP)
        return 1
    print(f'every tracked top-level directory is on the list ({len(DIRECTORIES)} listed)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
