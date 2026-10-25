#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The third-party licence texts every binary of the node carries, and the checks that hold them complete.

`licenses/index.json` names, for every resolved runtime component (`group:artifact:version`) and every
native library packaged in the assembly, the exact upstream texts its licence asks a binary distribution to
carry, with their source: a file of a Git revision, an entry of the component's own jar, or a text kept
elsewhere in this repository. The assembly packages each text at `META-INF/scarab/licenses/<key>` and the
index itself at `META-INF/scarab/licenses/index.json` (build.sbt). Three checks use this module:

- `verify_repository` (from `check_notice.py`, in CI): every text is present with its recorded digest and
  every component and native group names texts that exist.
- `verify_artifact` (from `assembly_inventory.export`, in reproducible and candidate builds): the packaged
  index covers exactly the components and native resources resolved into the JAR, and every text is in the
  JAR with its recorded bytes. A dependency bump, a new native or a stale entry fails the build.
- `fetch` (run by hand after a dependency change): download or extract the texts named by the index,
  check each Git tag still names the recorded commit, and record the digests.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
INDEX = 'licenses/index.json'
PACKAGED = 'META-INF/scarab/licenses/'
PACKAGED_INDEX = PACKAGED + 'index.json'


class Problem(Enum):
    MISSING_TEXT = 'licence text missing'
    CHANGED_TEXT = 'licence text differs from its recorded digest'
    UNKNOWN_TEXT = 'component or native group names a text the index does not define'
    UNCOVERED_COMPONENT = 'resolved component without licence texts'
    STALE_COMPONENT = 'licence index names a component that is not resolved'
    UNCOVERED_NATIVE = 'packaged native library without licence texts'
    STALE_NATIVE = 'licence index names a native library that is not packaged'
    MISSING_INDEX = 'artifact carries no licence index'
    INDEX_DIFFERS = 'packaged licence index differs from the repository index'


@dataclass(frozen=True)
class Finding:
    problem: Problem
    subject: str

    def render(self) -> str:
        return f'{self.problem.value}: {self.subject}'


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def load(root: Path = ROOT) -> dict:
    return json.loads((root / INDEX).read_text(encoding='utf-8'))


def repository_path(key: str, text: dict) -> str:
    """Where the repository keeps a text: its own file elsewhere in the tree, else `licenses/<key>`."""
    return text['source'].get('file', 'licenses/' + key)


def references(index: dict) -> list[Finding]:
    texts = index['texts']
    named = [(c, k) for c, entry in index['components'].items() for k in entry['texts']]
    named += [(', '.join(group['paths']), k) for group in index['natives'] for k in group['texts']]
    return [Finding(Problem.UNKNOWN_TEXT, f'{subject} -> {key}') for subject, key in named if key not in texts]


def verify_repository(root: Path = ROOT, index: dict | None = None) -> list[Finding]:
    index = load(root) if index is None else index
    rows = references(index)
    for key, text in sorted(index['texts'].items()):
        path = root / repository_path(key, text)
        if not path.is_file():
            rows.append(Finding(Problem.MISSING_TEXT, f'{key} ({repository_path(key, text)})'))
        elif digest(path.read_bytes()) != text['sha256']:
            rows.append(Finding(Problem.CHANGED_TEXT, key))
    return rows


def verify_artifact(entries: dict[str, bytes], coverage: dict, expected: dict | None = None) -> list[Finding]:
    """Check an assembly's packaged texts against what was resolved into it.

    `entries` maps the JAR's entry names to their bytes; `coverage` is `assembly_inventory.reconcile`'s result;
    `expected`, when given, is the repository index the packaged one must equal.
    """
    raw = entries.get(PACKAGED_INDEX)
    if raw is None:
        return [Finding(Problem.MISSING_INDEX, PACKAGED_INDEX)]
    index = json.loads(raw)
    rows = []
    if expected is not None and index != expected:
        rows.append(Finding(Problem.INDEX_DIFFERS, PACKAGED_INDEX))
    rows += references(index)
    resolved = {c['coordinate'] for c in coverage['components']}
    listed = set(index['components'])
    rows += [Finding(Problem.UNCOVERED_COMPONENT, c) for c in sorted(resolved - listed)]
    rows += [Finding(Problem.STALE_COMPONENT, c) for c in sorted(listed - resolved)]
    packaged = {n['path'] for n in coverage['nativeResources']}
    mapped = {p for group in index['natives'] for p in group['paths']}
    rows += [Finding(Problem.UNCOVERED_NATIVE, p) for p in sorted(packaged - mapped)]
    rows += [Finding(Problem.STALE_NATIVE, p) for p in sorted(mapped - packaged)]
    for key, text in sorted(index['texts'].items()):
        body = entries.get(PACKAGED + key)
        if body is None:
            rows.append(Finding(Problem.MISSING_TEXT, PACKAGED + key))
        elif digest(body) != text['sha256']:
            rows.append(Finding(Problem.CHANGED_TEXT, PACKAGED + key))
    return rows


def resolve_tag(repository: str, ref: str) -> str | None:
    """The commit a tag names, peeled through an annotated tag object."""
    out = subprocess.run(['git', 'ls-remote', repository, f'refs/tags/{ref}', f'refs/tags/{ref}^{{}}'],
                         check=True, capture_output=True, text=True).stdout.split('\n')
    rows = dict(reversed(line.split('\t')) for line in out if line)
    return rows.get(f'refs/tags/{ref}^{{}}') or rows.get(f'refs/tags/{ref}')


def raw_url(source: dict) -> str:
    repository, commit, path = source['git'], source['commit'], source['path']
    if repository.startswith('https://github.com/'):
        return f'https://raw.githubusercontent.com/{repository.removeprefix("https://github.com/")}/{commit}/{path}'
    if repository.startswith('https://sourceware.org/git/'):
        project = repository.removeprefix('https://sourceware.org/git/')
        return f'https://sourceware.org/git/?p={project};a=blob_plain;f={path};hb={commit}'
    raise ValueError(f'no raw-file URL scheme for {repository}')


def jar_path(cache: Path, coordinate: str) -> Path:
    group, artifact, version = coordinate.split(':')
    return cache / 'https/repo.maven.apache.org/maven2' / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.jar'


def fetch(root: Path, cache: Path) -> int:
    """Write every text from its source and record its digest; refuse a tag that moved."""
    path = root / INDEX
    index = json.loads(path.read_text(encoding='utf-8'))
    for key, text in sorted(index['texts'].items()):
        source = text['source']
        if 'file' in source:
            body = (root / source['file']).read_bytes()
        else:
            if 'jar' in source:
                with zipfile.ZipFile(jar_path(cache, source['jar'])) as jar:
                    body = jar.read(source['entry'])
            else:
                commit = resolve_tag(source['git'], source['ref'])
                if commit != source['commit']:
                    print(f'{key}: tag {source["ref"]} of {source["git"]} names {commit}, the index records {source["commit"]}')
                    return 1
                with urllib.request.urlopen(raw_url(source), timeout=60) as response:
                    body = response.read()
            target = root / 'licenses' / key
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
        text['sha256'] = digest(body)
    path.write_text(json.dumps(index, indent=2, sort_keys=False) + '\n', encoding='utf-8')
    print(f'{len(index["texts"])} licence texts written and recorded in {INDEX}')
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('verify', help='check the repository copies against the index')
    fetching = sub.add_parser('fetch', help='refresh the texts from their sources (network)')
    fetching.add_argument('--cache', type=Path, default=ROOT / '.cache/scarab/coursier',
                          help='Coursier cache that holds the jars named by jar sources')
    args = parser.parse_args(argv)
    if args.command == 'fetch':
        return fetch(ROOT, args.cache)
    rows = verify_repository(ROOT)
    for row in rows:
        print(row.render())
    if not rows:
        index = load(ROOT)
        print(f'{len(index["texts"])} licence texts cover {len(index["components"])} components and '
              f'{sum(len(g["paths"]) for g in index["natives"])} native libraries')
    return 1 if rows else 0


if __name__ == '__main__':
    sys.exit(main())
