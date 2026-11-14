#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The correspondence map: every definition of a mirrored area is mapped to the reference, listed as glue, or in
the baseline, and every entry is current (REF-6, docs/design/correspondence-map.md).

An area is a file `docs/data/correspondence/<area>.json` with its `roots` (directories under a module's
`src/main`), its `entries` (a definition key and its upstream counterpart) and its `glue` (groups of keys of
Scarab-only definitions, each group with its reason). `scala_definitions.py` says what a definition and its key
are. The baseline `docs/data/correspondence-baseline.json` lists, per area, the definitions not yet mapped, each
with null or with the counterpart its own citation names (a hint, checked like an entry's upstream). It only
shrinks. The guard refuses:

  * a definition under an area's roots that is in none of its entries, glue and baseline, or in two of them, or
    in another area; a line of a source under the roots that no definition holds (`scala_definitions.Read.unread`);
  * an entry, glue key or baseline key that names no current definition of its area, and a baseline key that is
    mapped or glue now (the baseline must shrink: `--write-baseline`);
  * a mapped definition whose code changed while its entry did not: the entry's `digest` is not the definition's;
  * an entry whose `class` is not Mirror, Improvement, Deviation or Gap, or whose `structure` is not `same` or
    `differs`; an Improvement, Deviation or Gap without a `record` link (a Gap's is its issue); a `differs` entry
    without `evidence`, the test suites that compare that behaviour with the reference, unless it is a Gap (a
    structure that differs with no such evidence is one); an evidence suite that no test source defines;
  * an upstream counterpart (an entry's, or a baseline hint) whose repository and revision are not a pin of
    `docs/data/reference-pins.json`, or whose file, symbol and lines are malformed; and, where a clone of its
    repository is given (`--sources DIR` or SCARAB_REFERENCE_SOURCES: a directory holding one clone per
    repository, named as the repository is), whose file does not exist at that revision, whose lines are past
    its end, or whose symbol does not occur in those lines. Given clones must hold every repository and revision
    the map names, or the check fails; with none given it prints that it did not run, and passes;
  * a baseline key or hint that the baseline of the change's base did not have: growing the baseline is never the
    way to admit a new definition. The base is the target branch on a pull request in CI (the merge commit's
    first parent, which the scripts job fetches; without it the check fails), and otherwise the merge base with
    origin/main where that history is at hand (without it the check prints that it did not run). A base with no
    baseline at all is the first one, which this check admits.

What an entry asserts beyond its counterpart (its class, and whether its structure is the reference's) is the
reviewer's to check: the guard checks only that the claim is complete.

    python3 scripts/check_correspondence.py                      check (the default)
    python3 scripts/check_correspondence.py --sources DIR        check, and the upstream symbols in DIR's clones
    python3 scripts/check_correspondence.py --write-baseline     record a smaller baseline; refuses any new key
    python3 scripts/check_correspondence.py --show KEY ...       a definition's file, lines, digest and listing
    python3 scripts/check_correspondence.py --unmapped AREA      the baselined definitions of an area, by file

Pure standard library.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scala_definitions  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
MAP = 'docs/data/correspondence'
BASELINE = 'docs/data/correspondence-baseline.json'
PINS = 'docs/data/reference-pins.json'
RULE = 'docs/design-rules.md#ref-6'
DESIGN = 'docs/design/correspondence-map.md'
SOURCES_ENV = 'SCARAB_REFERENCE_SOURCES'
CLASSES = ('Mirror', 'Improvement', 'Deviation', 'Gap')
NEEDS_RECORD = frozenset(('Improvement', 'Deviation', 'Gap'))
STRUCTURES = ('same', 'differs')
ENTRY_FIELDS = frozenset(('upstream', 'class', 'structure', 'digest', 'record', 'evidence', 'note'))
UPSTREAM_FIELDS = frozenset(('repository', 'revision', 'file', 'symbol', 'lines'))
REVISION = re.compile(r'[0-9a-f]{40}')
ISSUE = re.compile(r'https://github\.com/KD5UJC-XYZ/scarab/issues/\d+(?:#[\w-]+)?')
SUITE = re.compile(r'^\s*(?:(?:final|abstract|sealed|private|protected)\s+)*(?:class|object|trait)\s+(\w+Suite)\b',
                   re.MULTILINE)
BASELINE_COMMENT = (
    'The definitions of each mirrored area not yet mapped or listed as glue, each with null or with the upstream '
    'counterpart its own citation names, checked at the pin but not yet reviewed (docs/design/correspondence-map.md). '
    'Written by scripts/check_correspondence.py --write-baseline, which refuses a new key. It only shrinks.')


@dataclass(frozen=True)
class Found:
    """A definition of an area: its file (repository-relative) and what the reader gives."""
    area: str
    file: str
    definition: scala_definitions.Definition


@dataclass
class Area:
    name: str
    roots: list[str]
    entries: dict
    glue: dict[str, str]
    problems: list[str] = field(default_factory=list)


# ------------------------------------------------------------------ reading

def load_json(path: Path):
    return json.loads(path.read_text(encoding='utf-8'))


def areas(root: Path) -> list[Area]:
    out = []
    for path in sorted((root / MAP).glob('*.json')):
        problems: list[str] = []
        try:
            data = load_json(path)
        except ValueError as error:
            out.append(Area(path.stem, [], {}, {}, [f'{path.name}: not JSON ({error})']))
            continue
        name = data.get('area') if isinstance(data, dict) else None
        if name != path.stem:
            problems.append(f'{path.name}: its "area" is not {path.stem!r}')
        roots = data.get('roots') if isinstance(data, dict) else None
        if not isinstance(roots, list) or not roots or not all(isinstance(r, str) for r in roots):
            problems.append(f'{path.name}: "roots" is a nonempty list of directories')
            roots = []
        entries = data.get('entries', {}) if isinstance(data, dict) else {}
        if not isinstance(entries, dict):
            problems.append(f'{path.name}: "entries" is an object of definition keys')
            entries = {}
        glue: dict[str, str] = {}
        groups = data.get('glue', []) if isinstance(data, dict) else []
        if not isinstance(groups, list):
            problems.append(f'{path.name}: "glue" is a list of groups')
            groups = []
        for group in groups:
            reason = group.get('reason') if isinstance(group, dict) else None
            keys = group.get('definitions') if isinstance(group, dict) else None
            if not isinstance(reason, str) or not reason.strip() or not isinstance(keys, list):
                problems.append(f'{path.name}: a glue group has a nonempty "reason" and a list of "definitions"')
                continue
            for key in keys:
                if key in glue:
                    problems.append(f'{key}: listed as glue twice')
                glue[key] = reason
        out.append(Area(path.stem, roots, entries, glue, problems))
    return out


def definitions(root: Path, area: Area) -> tuple[list[Found], list[str]]:
    """The definitions under the area's roots, and the unread lines (`file:line`)."""
    found, unread = [], []
    for directory in area.roots:
        base = root / directory
        for path in sorted(base.rglob('*.scala')):
            if '/src_managed/' in path.as_posix():
                continue
            file = path.relative_to(root).as_posix()
            read = scala_definitions.read(path.read_text(encoding='utf-8'))
            found.extend(Found(area.name, file, d) for d in read.definitions)
            unread.extend(f'{file}:{line}' for line in read.unread)
    return found, unread


def root_problems(root: Path, all_areas: list[Area]) -> list[str]:
    out = []
    seen: list[tuple[str, str]] = []
    for area in all_areas:
        for directory in area.roots:
            if '/src/main/' not in directory + '/' or not (root / directory).is_dir():
                out.append(f'{area.name}: root {directory} is not a directory under a module\'s src/main')
            for other, taken in seen:
                if directory == taken or directory.startswith(taken + '/') or taken.startswith(directory + '/'):
                    out.append(f'{area.name}: root {directory} overlaps {other}\'s {taken}')
            seen.append((area.name, directory))
    return out


def test_suites(root: Path) -> set[str]:
    names: set[str] = set()
    for path in root.glob('*/src/test/**/*.scala'):
        names.update(SUITE.findall(path.read_text(encoding='utf-8')))
    return names


def pins(root: Path) -> set[tuple[str, str]]:
    data = load_json(root / PINS)
    return {(p['repository'], p['revision']) for p in data.get('pins', []) if p.get('revision')}


# ------------------------------------------------------------------ upstream counterparts

class Clones:
    """The upstream clones of `directory` (one per repository, named as the repository is), read with `git show`
    at a revision, never from a working tree."""

    def __init__(self, directory: Path | None):
        self.directory = directory
        self.files: dict[tuple[str, str, str], list[str] | None] = {}

    def clone(self, repository: str) -> Path | None:
        if self.directory is None:
            return None
        path = self.directory / repository.rsplit('/', 1)[-1]
        return path if (path / '.git').exists() or (path / 'HEAD').is_file() else None

    def lines(self, repository: str, revision: str, file: str) -> list[str] | None | bool:
        """The file's lines at the revision; None when it does not exist there; False when it cannot be read here
        (no clone, or the revision is not in it)."""
        clone = self.clone(repository)
        if clone is None:
            return False
        key = (repository, revision, file)
        if key not in self.files:
            known = subprocess.run(['git', '-C', str(clone), 'cat-file', '-e', f'{revision}^{{commit}}'],
                                   capture_output=True)
            if known.returncode != 0:
                return False
            shown = subprocess.run(['git', '-C', str(clone), 'show', f'{revision}:{file}'], capture_output=True)
            self.files[key] = (shown.stdout.decode('utf-8', 'replace').split('\n') if shown.returncode == 0
                               else None)
        return self.files[key]


def upstream_problems(what: str, refs, registered: set[tuple[str, str]], clones: Clones) -> list[str]:
    if not isinstance(refs, list) or not refs:
        return [f'{what}: "upstream" is a nonempty list of counterparts']
    out = []
    for ref in refs:
        if not isinstance(ref, dict) or set(ref) != UPSTREAM_FIELDS:
            out.append(f'{what}: an upstream counterpart has exactly {", ".join(sorted(UPSTREAM_FIELDS))}')
            continue
        repository, revision, file, symbol, lines = (ref[k] for k in ('repository', 'revision', 'file', 'symbol',
                                                                      'lines'))
        if (not isinstance(repository, str) or not isinstance(revision, str) or not REVISION.fullmatch(revision)
                or (repository, revision) not in registered):
            out.append(f'{what}: {repository} {revision} is not a revision pinned in {PINS}')
            continue
        if not isinstance(file, str) or not file or file.startswith('/') or '..' in file.split('/'):
            out.append(f'{what}: the upstream file is a path relative to the repository')
            continue
        if not isinstance(symbol, str) or not symbol.strip():
            out.append(f'{what}: the upstream symbol is nonempty')
            continue
        if (not isinstance(lines, list) or len(lines) != 2 or not all(isinstance(n, int) for n in lines)
                or not 1 <= lines[0] <= lines[1]):
            out.append(f'{what}: the upstream lines are [first, last], 1 <= first <= last')
            continue
        text = clones.lines(repository, revision, file)
        if text is False:
            if clones.directory is not None:
                out.append(f'{what}: cannot check {repository} {revision[:8]}: no clone of it, or the revision is not '
                           f'in it, under {clones.directory}')
            continue
        if text is None:
            out.append(f'{what}: {file} does not exist at {repository} {revision[:8]}')
            continue
        if lines[1] > len(text):
            out.append(f'{what}: {file} at {revision[:8]} has {len(text)} lines, fewer than {lines[1]}')
            continue
        word = re.compile(r"(?<![\w'])" + re.escape(symbol.strip()) + r"(?![\w'])")
        if not any(word.search(line) for line in text[lines[0] - 1:lines[1]]):
            out.append(f'{what}: {symbol} does not occur in {file}:{lines[0]}-{lines[1]} at {revision[:8]}')
    return out


def record_problems(root: Path, what: str, kind: str, record) -> list[str]:
    if not isinstance(record, str) or not record.strip():
        return [f'{what}: a {kind} links its record ("record")']
    if kind == 'Gap' and not ISSUE.fullmatch(record):
        return [f'{what}: a Gap\'s record is its issue (https://github.com/KD5UJC-XYZ/scarab/issues/N)']
    if record.startswith('https://'):
        return []
    path = record.split('#', 1)[0]
    if not path or not (root / path).is_file():
        return [f'{what}: its record {record} is neither an https link nor a file of the repository']
    return []


def entry_problems(root: Path, key: str, entry, digest: str, registered, clones: Clones,
                   suites: set[str]) -> list[str]:
    if not isinstance(entry, dict):
        return [f'{key}: an entry is an object']
    out = []
    unknown = set(entry) - ENTRY_FIELDS
    if unknown:
        out.append(f'{key}: unknown fields {", ".join(sorted(unknown))}')
    out += upstream_problems(key, entry.get('upstream'), registered, clones)
    kind, structure = entry.get('class'), entry.get('structure')
    if kind not in CLASSES:
        out.append(f'{key}: "class" is one of {", ".join(CLASSES)} (REF-4)')
    if structure not in STRUCTURES:
        out.append(f'{key}: "structure" is "same" or "differs"')
    if kind in NEEDS_RECORD:
        out += record_problems(root, key, kind, entry.get('record'))
    evidence = entry.get('evidence', [])
    if not isinstance(evidence, list) or not all(isinstance(s, str) for s in evidence):
        out.append(f'{key}: "evidence" is a list of test suite names')
        evidence = []
    if structure == 'differs' and not evidence and kind != 'Gap':
        out.append(f'{key}: its structure differs from the reference\'s, so it names the suites that compare that '
                   'behaviour with the reference ("evidence")')
    out += [f'{key}: evidence suite {s} is not defined by any test source' for s in evidence if s not in suites]
    if entry.get('digest') != digest:
        out.append(f'{key}: its code changed and its entry did not (digest {entry.get("digest")} -> {digest}): '
                   'compare it with its upstream counterpart at the pin again, correct the entry, then record '
                   f'"digest": "{digest}"')
    return out


# ------------------------------------------------------------------ the baseline

def read_baseline(root: Path) -> dict[str, dict] | None:
    path = root / BASELINE
    if not path.is_file():
        return None
    return load_json(path).get('areas', {})


def write_baseline(root: Path, baseline: dict[str, dict]) -> None:
    body = {'_comment': BASELINE_COMMENT,
            'areas': {name: dict(sorted(keys.items())) for name, keys in sorted(baseline.items())}}
    (root / BASELINE).write_text(json.dumps(body, indent=1, ensure_ascii=False) + '\n', encoding='utf-8')


class NoBase(Exception):
    """The base of the change cannot be read where it must be."""


def base_baseline(root: Path, environment: dict[str, str]) -> dict[str, dict] | str:
    """The baseline of the change's base, or why there is none to compare with. On a pull request in CI the base is
    the merge commit's first parent, which must be there (`NoBase` otherwise); elsewhere it is the merge base with
    origin/main, where that history is at hand."""
    def git(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True)
    if environment.get('GITHUB_ACTIONS') == 'true' and environment.get('GITHUB_EVENT_NAME') == 'pull_request':
        base = git('rev-parse', '--verify', '--quiet', 'HEAD^1')
        if base.returncode != 0:
            raise NoBase('the pull request\'s target is not in this checkout: check it out two commits deep')
    else:
        base = git('merge-base', 'HEAD', 'origin/main')
        if base.returncode != 0:
            return 'no history of origin/main here'
    shown = git('show', f'{base.stdout.strip()}:{BASELINE}')
    if shown.returncode != 0:
        return 'the base has no baseline yet (this is the first)'
    return json.loads(shown.stdout).get('areas', {})


# ------------------------------------------------------------------ the check

@dataclass
class Outcome:
    problems: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    unmapped: dict[str, dict[str, Found]] = field(default_factory=dict)
    counts: dict[str, dict[str, int]] = field(default_factory=dict)


def check(root: Path, sources: Path | None, write: bool, environment: dict[str, str] | None = None) -> Outcome:
    environment = dict(os.environ) if environment is None else environment
    outcome = Outcome()
    all_areas = areas(root)
    registered = pins(root)
    clones = Clones(sources)
    suites = test_suites(root)
    baseline = read_baseline(root)
    outcome.problems += root_problems(root, all_areas)
    by_key: dict[str, Found] = {}
    for area in all_areas:
        outcome.problems += area.problems
        found, unread = definitions(root, area)
        outcome.problems += [f'{line}: no definition holds this line; the reader does not know this construct '
                             f'({DESIGN})' for line in unread]
        for item in found:
            if item.definition.key in by_key:
                other = by_key[item.definition.key]
                outcome.problems.append(f'{item.definition.key}: defined in {item.file} and in {other.file}')
            by_key[item.definition.key] = item
        mine = {f.definition.key: f for f in found}
        for key in sorted(set(area.entries) | set(area.glue)):
            if key in area.entries and key in area.glue:
                outcome.problems.append(f'{key}: both an entry and glue; a definition is listed once')
            if key not in mine:
                outcome.problems.append(f'{area.name}: {key} is listed but is not a definition of the area: '
                                        'remove it, or correct its key (--show)')
        was = (baseline or {}).get(area.name, {})
        for key, entry in sorted(area.entries.items()):
            if key in mine:
                outcome.problems += entry_problems(root, key, entry, mine[key].definition.digest, registered, clones,
                                                   suites)
        for key, hint in sorted(was.items()):
            if hint is not None:
                outcome.problems += upstream_problems(f'{key} (baseline hint)', hint, registered, clones)
        unmapped = {k: f for k, f in mine.items() if k not in area.entries and k not in area.glue}
        outcome.unmapped[area.name] = unmapped
        outcome.counts[area.name] = {
            'definitions': len(mine),
            'mapped': sum(1 for k in area.entries if k in mine),
            'glue': sum(1 for k in area.glue if k in mine),
            'baselined': len(unmapped),
            'hinted': sum(1 for k in unmapped if was.get(k) is not None),
        }
    if sources is None:
        outcome.notes.append(f'upstream symbols not checked: no clones given (--sources or {SOURCES_ENV})')
    baseline_check(root, baseline, outcome, write, {a.name for a in all_areas}, environment)
    return outcome


def baseline_check(root: Path, baseline: dict[str, dict] | None, outcome: Outcome, write: bool,
                   names: set[str], environment: dict[str, str]) -> None:
    now = {area: set(keys) for area, keys in outcome.unmapped.items()}
    if baseline is None:
        if write:
            write_baseline(root, {area: {k: None for k in keys} for area, keys in now.items()})
            outcome.notes.append(f'wrote the first {BASELINE}: {sum(map(len, now.values()))} definitions')
        else:
            outcome.problems.append(f'{BASELINE} is missing: run with --write-baseline to record the first one')
        return
    for area in sorted(set(baseline) - names):
        outcome.problems.append(f'{BASELINE}: area {area} has no map file')
    try:
        base = base_baseline(root, environment)
    except NoBase as error:
        outcome.problems.append(f'baseline growth cannot be checked: {error}')
        return
    if isinstance(base, str):
        outcome.notes.append(f'baseline growth not checked: {base}')
    else:
        grown = sorted((area, k) for area, keys in baseline.items() for k in keys if k not in base.get(area, {}))
        if grown:
            outcome.problems.append(f'keys added to {BASELINE} since its base: a new definition is mapped or glue, '
                                    'never baselined:')
            outcome.problems += [f'  {area}: {key}' for area, key in grown]
        hinted = sorted((area, k) for area, keys in baseline.items() for k, v in keys.items()
                        if v is not None and base.get(area, {}).get(k, v) != v)
        if hinted:
            outcome.problems.append('baseline hints added or changed since its base: a counterpart goes into an '
                                    'entry, not the baseline:')
            outcome.problems += [f'  {area}: {key}' for area, key in hinted]
    new = sorted((area, k) for area, keys in now.items() for k in keys if k not in baseline.get(area, {}))
    gone = sorted((area, k) for area, keys in baseline.items() for k in keys if k not in now.get(area, set()))
    if new:
        outcome.problems.append(f'new definitions that are neither mapped nor glue ({RULE}, {DESIGN}): add an entry '
                                'that names the upstream counterpart at its pin, or a glue line with its reason:')
        for area, key in new:
            found = outcome.unmapped[area][key]
            outcome.problems.append(f'  {key} ({found.file}:{found.definition.line})')
    if write and not outcome.problems:
        write_baseline(root, {area: {k: v for k, v in baseline.get(area, {}).items() if k in now.get(area, set())}
                              for area in sorted(names)})
        outcome.notes.append(f'wrote {BASELINE}: {sum(map(len, now.values()))} definitions')
    elif gone and not write:
        outcome.problems.append(f'fewer unmapped definitions than {BASELINE} lists: run with --write-baseline and '
                                'commit it:')
        outcome.problems += [f'  {area}: {key}' for area, key in gone]


def show(root: Path, keys: list[str]) -> int:
    all_areas = areas(root)
    baseline = read_baseline(root) or {}
    found = {}
    for area in all_areas:
        for item in definitions(root, area)[0]:
            found[item.definition.key] = (area, item)
    status = 0
    for key in keys:
        if key not in found:
            print(f'{key}: not a definition of any area')
            status = 1
            continue
        area, item = found[key]
        listing = ('entry' if key in area.entries else 'glue' if key in area.glue
                   else 'baseline' if key in baseline.get(area.name, {}) else 'unlisted')
        spans = ', '.join(f'{a}-{b}' for a, b in item.definition.lines)
        print(f'{key}\n  area {area.name}, {listing}\n  {item.file}:{spans}\n  digest {item.definition.digest}')
        hint = baseline.get(area.name, {}).get(key)
        if hint:
            print(f'  cited counterpart: {json.dumps(hint)}')
    return status


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--sources', type=Path, default=None,
                        help=f'a directory of upstream clones, one per repository (default: ${SOURCES_ENV})')
    parser.add_argument('--write-baseline', action='store_true', help='record a smaller baseline; refuses new keys')
    parser.add_argument('--show', nargs='+', metavar='KEY', help='print where each definition is and its digest')
    parser.add_argument('--unmapped', metavar='AREA', help='list the baselined definitions of an area, by file')
    args = parser.parse_args(argv)
    if args.show:
        return show(root, args.show)
    sources = args.sources or (Path(os.environ[SOURCES_ENV]) if os.environ.get(SOURCES_ENV) else None)
    outcome = check(root, sources, args.write_baseline)
    if args.unmapped:
        for key, item in sorted(outcome.unmapped.get(args.unmapped, {}).items(),
                                key=lambda kv: (kv[1].file, kv[1].definition.line)):
            print(f'{item.file}:{item.definition.line}\t{key}')
        return 0
    for note in outcome.notes:
        print(note)
    for name, c in sorted(outcome.counts.items()):
        print(f'{name}: {c["definitions"]} definitions: {c["mapped"]} mapped, {c["glue"]} glue, {c["baselined"]} '
              f'baselined ({c["hinted"]} with a cited counterpart)')
    if outcome.problems:
        print(f'correspondence map: {len(outcome.problems)} problems ({RULE}, {DESIGN}):')
        print('\n'.join(outcome.problems))
        return 1
    print('correspondence map: every definition is mapped, glue or baselined, and every entry is current')
    return 0


if __name__ == '__main__':
    sys.exit(main())
