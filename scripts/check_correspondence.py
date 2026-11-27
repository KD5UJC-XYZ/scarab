#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The correspondence map: every definition of a mirrored area is mapped to the reference, listed as glue, or in
the baseline, and every entry is current (REF-6, docs/design/correspondence-map.md).

An area is a directory `docs/data/correspondence/<area>/`. Its `_area.json` holds its `roots` (directories under a
module's `src/main`). Each Scala source under the roots that holds a definition has one map file, named by `home`:
the source's path under its root, without the package prefix `xyz/kd5ujc/scarab/`, with `.json` for `.scala`. A map
file names its `source` and holds that source's `entries` (a definition key and its upstream counterpart), its
`glue` (groups of keys of Scarab-only definitions, each group with its reason) and its `baseline` (the definitions
not yet mapped, each with null or with the counterpart its own citation names: a hint, checked like an entry's
upstream). `scala_definitions.py` says what a definition and its key are. `docs/data/correspondence/_baseline.json`
records that the baseline exists, and the areas, so that a missing area is seen. The baseline only shrinks. Every file is in the one form `render` writes. The
guard refuses:

  * a definition under an area's roots that is in none of its entries, glue and baseline, or in two of them, or
    in another area; a line of a source under the roots that no definition holds (`scala_definitions.Read.unread`);
  * an entry, glue key or baseline key that names no current definition of its area, and a baseline key that is
    mapped or glue now (the baseline must shrink: `--write-baseline`);
  * a key listed in a map file other than its source's, a map file whose path or `source` is not its keys'
    source's, a map file that lists nothing, an area recorded in `_baseline.json` without its directory, a file not in the form `render` writes (`--tidy` corrects all four), and a file of the
    earlier layout (`<area>.json` and `docs/data/correspondence-baseline.json`; `migrate_correspondence.py --port`);
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
    origin/main where that history is at hand (without it the check prints that it did not run). The base's
    baseline is read in either layout (`read_flat` reads the earlier one). A base with no baseline at all is the
    first one, which this check admits.

What an entry asserts beyond its counterpart (its class, and whether its structure is the reference's) is the
reviewer's to check: the guard checks only that the claim is complete.

    python3 scripts/check_correspondence.py                      check (the default)
    python3 scripts/check_correspondence.py --sources DIR        check, and the upstream symbols in DIR's clones
    python3 scripts/check_correspondence.py --write-baseline     record a smaller baseline; refuses any new key
    python3 scripts/check_correspondence.py --tidy               move each key to its source's map file and write
                                                                 every map file in its one form; changes no data
    python3 scripts/check_correspondence.py --show KEY ...       a definition's file, lines, digest, listing and
                                                                 map file
    python3 scripts/check_correspondence.py --unmapped AREA      the baselined definitions of an area, by file

Pure standard library.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
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
AREA_FILE = '_area.json'
BASELINE = f'{MAP}/_baseline.json'
# The earlier layout (one file per area, `MAP/<area>.json`, and this baseline), read only from a base that has it.
FLAT_BASELINE = 'docs/data/correspondence-baseline.json'
PACKAGE = 'xyz/kd5ujc/scarab/'
PINS = 'docs/data/reference-pins.json'
RULE = 'docs/design-rules.md#ref-6'
DESIGN = 'docs/design/correspondence-map.md'
SOURCES_ENV = 'SCARAB_REFERENCE_SOURCES'
CLASSES = ('Mirror', 'Improvement', 'Deviation', 'Gap')
NEEDS_RECORD = frozenset(('Improvement', 'Deviation', 'Gap'))
STRUCTURES = ('same', 'differs')
ENTRY_FIELDS = frozenset(('upstream', 'class', 'structure', 'digest', 'record', 'evidence', 'note'))
UPSTREAM_FIELDS = frozenset(('repository', 'revision', 'file', 'symbol', 'lines'))
AREA_FIELDS = frozenset(('_comment', 'area', 'roots'))
SOURCE_FIELDS = frozenset(('source', 'entries', 'glue', 'baseline'))
GROUP_FIELDS = frozenset(('reason', 'definitions'))
REVISION = re.compile(r'[0-9a-f]{40}')
ISSUE = re.compile(r'https://github\.com/KD5UJC-XYZ/scarab/issues/\d+(?:#[\w-]+)?')
SUITE = re.compile(r'^\s*(?:(?:final|abstract|sealed|private|protected)\s+)*(?:class|object|trait)\s+(\w+Suite)\b',
                   re.MULTILINE)
BASELINE_COMMENT = (
    'The baseline is recorded: the definitions of each mirrored area not yet mapped or listed as glue are the '
    '"baseline" of their source\'s map file, each with null or with the upstream counterpart its own citation names, '
    'checked at the pin but not yet reviewed (docs/design/correspondence-map.md). Written by '
    'scripts/check_correspondence.py --write-baseline, which refuses a new key. It only shrinks.')


@dataclass(frozen=True)
class Found:
    """A definition of an area: its file (repository-relative) and what the reader gives."""
    area: str
    file: str
    definition: scala_definitions.Definition


@dataclass
class Area:
    """An area as read, in either layout: the data (`model`) and where each key was listed."""
    name: str
    roots: list[str]
    entries: dict
    glue: dict[str, str]
    comment: str | None = None
    baseline: dict[str, object] = field(default_factory=dict)
    listed: dict[str, str] = field(default_factory=dict)
    sources: dict[str, str] = field(default_factory=dict)
    problems: list[str] = field(default_factory=list)

    def model(self) -> dict:
        return {'comment': self.comment, 'roots': self.roots, 'entries': self.entries, 'glue': self.glue,
                'baseline': self.baseline}


@dataclass
class Map:
    """The map as read: its areas, whether its baseline is recorded and the areas its record names, the problems of
    reading it, the map files that were read (the only ones a write may remove), those that could not be, and the
    keys listed in one section of two files (a write would keep one of them)."""
    areas: list[Area]
    recorded: bool
    problems: list[str] = field(default_factory=list)
    read: set[str] = field(default_factory=set)
    unread: list[str] = field(default_factory=list)
    twice: list[str] = field(default_factory=list)
    recorded_areas: list[str] = field(default_factory=list)

    def model(self) -> dict:
        """The data alone, the same whatever the layout or the files it is stored in."""
        return {area.name: area.model() for area in self.areas}

    def baseline(self) -> dict[str, dict] | None:
        return {area.name: area.baseline for area in self.areas} if self.recorded else None


class Layout(Enum):
    TREE = 'one map file per source'
    FLAT = 'one map file per area and one baseline'
    NONE = 'no map'


# ------------------------------------------------------------------ the files

def load_json(path: Path):
    return json.loads(path.read_text(encoding='utf-8'))


def unique_pairs(pairs: list[tuple[str, object]]) -> dict:
    out: dict = {}
    for key, value in pairs:
        if key in out:
            raise ValueError(f'{key!r} is given twice')
        out[key] = value
    return out


def parse(text: str):
    """JSON, refusing a key given twice in one object (which `json.loads` would keep silently, the last one)."""
    return json.loads(text, object_pairs_hook=unique_pairs)


def render(data) -> str:
    """The one form of every file of the map: keys sorted, one space of indentation, and a final line break."""
    return json.dumps(data, indent=1, sort_keys=True, ensure_ascii=False) + '\n'


def disk_files(root: Path) -> dict[str, str]:
    """The text of every file of the map in the working tree, in either layout."""
    out = {}
    base = root / MAP
    if base.is_dir():
        for path in sorted(base.rglob('*')):
            if path.is_file():
                out[path.relative_to(root).as_posix()] = path.read_text(encoding='utf-8')
    if (root / FLAT_BASELINE).is_file():
        out[FLAT_BASELINE] = (root / FLAT_BASELINE).read_text(encoding='utf-8')
    return out


def revision_files(root: Path, revision: str) -> dict[str, str]:
    """The text of every file of the map at a revision, in either layout, read in one `git cat-file --batch`."""
    listed = subprocess.run(['git', '-C', str(root), 'ls-tree', '-r', '-z', '--name-only', revision, '--', MAP,
                             FLAT_BASELINE], capture_output=True, check=True)
    names = [n for n in listed.stdout.decode('utf-8').split('\0') if n]
    if not names:
        return {}
    shown = subprocess.run(['git', '-C', str(root), 'cat-file', '--batch'], capture_output=True, check=True,
                           input=''.join(f'{revision}:{name}\n' for name in names).encode('utf-8'))
    out, data, at = {}, shown.stdout, 0
    for name in names:
        end = data.index(b'\n', at)
        header = data[at:end].split()
        if len(header) != 3 or header[1] != b'blob':
            raise ValueError(f'{revision}:{name}: not a file ({data[at:end].decode("utf-8", "replace")})')
        size = int(header[2])
        out[name] = data[end + 1:end + 1 + size].decode('utf-8')
        at = end + 1 + size + 1
    return out


def layout(files: dict[str, str]) -> Layout:
    if BASELINE in files or any(p.startswith(MAP + '/') and p.endswith('/' + AREA_FILE) for p in files):
        return Layout.TREE
    if FLAT_BASELINE in files or any(p.startswith(MAP + '/') and p.endswith('.json') for p in files):
        return Layout.FLAT
    return Layout.NONE


def home(area: Area, source: str) -> str | None:
    """The map file of a source of the area, or None for a file that is not a Scala source under its roots."""
    for directory in area.roots:
        if source.startswith(directory + '/') and source.endswith('.scala'):
            path = source[len(directory) + 1:].removeprefix(PACKAGE).removesuffix('.scala')
            return f'{MAP}/{area.name}/{path}.json'
    return None


def glue_groups(glue: dict[str, str]) -> list[dict]:
    reasons: dict[str, list[str]] = {}
    for key, reason in glue.items():
        reasons.setdefault(reason, []).append(key)
    return [{'definitions': sorted(keys), 'reason': reason} for reason, keys in sorted(reasons.items())]


def source_document(source: str, entries: dict, glue: dict[str, str], baseline: dict) -> dict:
    """A map file's content in its one form: sections sorted, glue grouped by reason, empty sections left out."""
    out: dict = {'source': source}
    if entries:
        out['entries'] = dict(sorted(entries.items()))
    if glue:
        out['glue'] = glue_groups(glue)
    if baseline:
        out['baseline'] = dict(sorted(baseline.items()))
    return out


def area_document(area: Area) -> dict:
    out: dict = {'area': area.name, 'roots': area.roots}
    if area.comment is not None:
        out['_comment'] = area.comment
    return out


def baseline_document(areas) -> dict:
    return {'_comment': BASELINE_COMMENT, 'areas': sorted(areas)}


# ------------------------------------------------------------------ reading the map

def area_meta(name: str, path: str, data, problems: list[str]) -> Area:
    if not isinstance(data, dict):
        problems.append(f'{path}: an object with "area" and "roots"')
        return Area(name, [], {}, {})
    unknown = set(data) - AREA_FIELDS
    if unknown:
        problems.append(f'{path}: unknown fields {", ".join(sorted(unknown))}')
    if data.get('area') != name:
        problems.append(f'{path}: its "area" is not {name!r}')
    roots = data.get('roots')
    if not isinstance(roots, list) or not roots or not all(isinstance(r, str) for r in roots):
        problems.append(f'{path}: "roots" is a nonempty list of directories')
        roots = []
    comment = data.get('_comment')
    if comment is not None and not isinstance(comment, str):
        problems.append(f'{path}: "_comment" is a string')
        comment = None
    return Area(name, roots, {}, {}, comment)


def groups_of(path: str, groups, problems: list[str]) -> dict[str, str]:
    """A file's glue groups as key -> reason."""
    glue: dict[str, str] = {}
    if not isinstance(groups, list):
        problems.append(f'{path}: "glue" is a list of groups')
        return glue
    for group in groups:
        reason = group.get('reason') if isinstance(group, dict) else None
        keys = group.get('definitions') if isinstance(group, dict) else None
        if (not isinstance(reason, str) or not reason.strip() or not isinstance(keys, list)
                or set(group) - GROUP_FIELDS):
            problems.append(f'{path}: a glue group has a nonempty "reason" and a list of "definitions", and no more')
            continue
        for key in keys:
            if key in glue:
                problems.append(f'{key}: listed as glue twice')
            glue[key] = reason
    return glue


def take(area: Area, path: str, section: dict, items: dict, problems: list[str], twice: list[str]) -> None:
    """Adds one section of a file to the area's, refusing a key that another file lists in the same section."""
    for key, value in items.items():
        if key in section:
            problems.append(f'{key}: listed in {area.listed.get(key)} and in {path}')
            twice.append(key)
        else:
            area.listed.setdefault(key, path)
        section[key] = value


def read_tree(files: dict[str, str]) -> Map:
    """The map in its layout of one file per source."""
    problems: list[str] = []
    metas: dict[str, tuple[str, object]] = {}
    per_area: dict[str, dict[str, object]] = {}
    texts: dict[str, str] = {}
    unread: list[str] = []
    recorded, marker = False, None
    for path, text in sorted(files.items()):
        rel = path[len(MAP) + 1:] if path.startswith(MAP + '/') else None
        if rel is None or (rel.endswith('.json') and '/' not in rel and path != BASELINE):
            problems.append(f'{path}: a file of the earlier layout of the map: carry its changes over with '
                            'scripts/migrate_correspondence.py --port, then remove it')
            continue
        if not rel.endswith('.json'):
            problems.append(f'{path}: not a map file: every file of the map is JSON')
            continue
        try:
            data = parse(text)
        except ValueError as error:
            problems.append(f'{path}: not JSON ({error})')
            unread.append(path)
            continue
        texts[path] = text
        if path == BASELINE:
            recorded = isinstance(data, dict) and isinstance(data.get('areas'), list)
            marker = data
            if not recorded:
                problems.append(f'{path}: an object with its "_comment" and the "areas" of the map; the baseline is '
                                'in the map files')
        elif rel.split('/', 1)[1] == AREA_FILE:
            metas[rel.split('/', 1)[0]] = (path, data)
        else:
            per_area.setdefault(rel.split('/', 1)[0], {})[path] = data
    out = Map([], recorded, problems, unread=unread)
    if recorded:
        names = marker['areas']
        if not all(isinstance(n, str) for n in names) or len(set(names)) != len(names):
            problems.append(f'{BASELINE}: its "areas" are the names of the map\'s areas, each once')
            names = [n for n in dict.fromkeys(names) if isinstance(n, str)]
        out.recorded_areas = names
        if render(baseline_document(names)) != files[BASELINE]:
            problems.append(f'{BASELINE}: not in the one form of the map\'s files: run --tidy')
        problems += [f'{MAP}/{name}: an area {BASELINE} records, with no map: restore it'
                     for name in sorted(set(names) - set(metas))]
        problems += [f'{MAP}/{name}: an area {BASELINE} does not record: run --tidy'
                     for name in sorted(set(metas) - set(names))]
    for name in sorted(set(metas) | set(per_area)):
        if name not in metas:
            problems.append(f'{MAP}/{name}: has no {AREA_FILE}, so its map files are not read')
            unread += sorted(per_area.get(name, {}))
            continue
        path, data = metas[name]
        area = area_meta(name, path, data, problems)
        if isinstance(data, dict) and render(data) != texts[path]:
            problems.append(f'{path}: not in the one form of the map\'s files: run --tidy')
        for file, data in sorted(per_area.get(name, {}).items()):
            if not isinstance(data, dict) or not isinstance(data.get('source'), str):
                problems.append(f'{file}: an object that names its "source"')
                unread.append(file)
                continue
            out.read.add(file)
            unknown = set(data) - SOURCE_FIELDS
            if unknown:
                problems.append(f'{file}: unknown fields {", ".join(sorted(unknown))}')
            area.sources[file] = data['source']
            entries, baseline = data.get('entries', {}), data.get('baseline', {})
            if not isinstance(entries, dict):
                problems.append(f'{file}: "entries" is an object of definition keys')
                entries = {}
            if not isinstance(baseline, dict):
                problems.append(f'{file}: "baseline" is an object of definition keys')
                baseline = {}
            glue = groups_of(file, data.get('glue', []), problems)
            if not entries and not glue and not baseline:
                problems.append(f'{file}: lists no definition: remove it (--tidy)')
            elif render(source_document(data['source'], entries, glue, baseline)) != texts[file]:
                problems.append(f'{file}: not in the one form of the map\'s files: run --tidy')
            take(area, file, area.entries, entries, problems, out.twice)
            take(area, file, area.glue, glue, problems, out.twice)
            take(area, file, area.baseline, baseline, problems, out.twice)
        out.areas.append(area)
    if not recorded and any(area.baseline for area in out.areas):
        problems.append(f'{BASELINE} is missing, yet map files list baseline keys: restore it')
    return out


def read_flat(files: dict[str, str]) -> Map:
    """The map in its earlier layout: `MAP/<area>.json` with `roots`, `entries` and `glue`, and the baseline
    `FLAT_BASELINE`. It reads a base that has that layout, and the migration (scripts/migrate_correspondence.py);
    it goes with the migration once no open pull request's base has it."""
    problems: list[str] = []
    out = Map([], FLAT_BASELINE in files, problems)
    for path, text in sorted(files.items()):
        if not (path.startswith(MAP + '/') and path.endswith('.json') and '/' not in path[len(MAP) + 1:]):
            continue
        name = path[len(MAP) + 1:-len('.json')]
        try:
            data = parse(text)
        except ValueError as error:
            problems.append(f'{path}: not JSON ({error})')
            continue
        if not isinstance(data, dict):
            problems.append(f'{path}: an object with "area", "roots", "entries" and "glue"')
            continue
        unknown = set(data) - AREA_FIELDS - {'entries', 'glue'}
        if unknown:
            problems.append(f'{path}: unknown fields {", ".join(sorted(unknown))}')
        area = area_meta(name, path, {k: v for k, v in data.items() if k in AREA_FIELDS}, problems)
        entries = data.get('entries', {})
        if not isinstance(entries, dict):
            problems.append(f'{path}: "entries" is an object of definition keys')
            entries = {}
        area.entries = entries
        area.glue = groups_of(path, data.get('glue', []), problems)
        area.listed = {key: path for key in [*area.entries, *area.glue]}
        out.areas.append(area)
    if FLAT_BASELINE in files:
        try:
            baseline = parse(files[FLAT_BASELINE]).get('areas', {})
        except (ValueError, AttributeError) as error:
            problems.append(f'{FLAT_BASELINE}: not JSON ({error})')
            baseline = {}
        names = {area.name for area in out.areas}
        problems += [f'{FLAT_BASELINE}: area {name} has no map file' for name in sorted(set(baseline) - names)]
        for area in out.areas:
            keys = baseline.get(area.name, {}) if isinstance(baseline, dict) else None
            if not isinstance(keys, dict):
                problems.append(f'{FLAT_BASELINE}: the baseline of {area.name} is an object of definition keys')
                keys = {}
            area.baseline = dict(keys)
            for key in area.baseline:
                area.listed.setdefault(key, FLAT_BASELINE)
    return out


def read_map(files: dict[str, str]) -> Map:
    """The map in whichever layout `files` hold."""
    found = layout(files)
    if found is Layout.TREE:
        return read_tree(files)
    if found is Layout.FLAT:
        return read_flat(files)
    return Map([], False)


# ------------------------------------------------------------------ writing the map

def placed(mapped: Map, found: dict[str, dict[str, Found]], baseline: dict[str, dict] | None) -> dict[str, str]:
    """Every file of the map with its text: a key in its definition's source's map file, a key that names no current
    definition where it was listed, `_area.json` for each area, and the baseline's record unless `baseline` is None
    (no baseline recorded yet). The record keeps the areas it named."""
    files: dict[str, str] = {}
    if baseline is not None:
        names = set(mapped.recorded_areas) | {area.name for area in mapped.areas}
        files[BASELINE] = render(baseline_document(names))
    for area in mapped.areas:
        files[f'{MAP}/{area.name}/{AREA_FILE}'] = render(area_document(area))
        mine = found.get(area.name, {})
        by_file: dict[str, tuple[str, dict, dict, dict]] = {}
        sections = (area.entries, area.glue, (baseline or {}).get(area.name, area.baseline))
        for index, section in enumerate(sections):
            for key, value in section.items():
                source = mine[key].file if key in mine else area.sources.get(area.listed.get(key, ''))
                path = home(area, source) if key in mine else area.listed.get(key)
                if path is None or source is None:
                    raise ValueError(f'{key}: no map file to write it to: it names no definition of {area.name}, '
                                     'and no map file of the tree lists it')
                if path == f'{MAP}/{area.name}/{AREA_FILE}':
                    raise ValueError(f'{source}: its map file would be {path}, the area\'s own file')
                if path in by_file and by_file[path][0] != source:
                    raise ValueError(f'{path}: the map file of both {by_file[path][0]} and {source}')
                by_file.setdefault(path, (source, {}, {}, {}))[index + 1][key] = value
        for path, (source, entries, glue, base) in by_file.items():
            files[path] = render(source_document(source, entries, glue, base))
    return files


def write_tree(root: Path, mapped: Map, found: dict[str, dict[str, Found]],
               baseline: dict[str, dict] | None) -> int:
    """Writes the map, one file per source; removes the map files it read that now list nothing, and the
    directories they leave empty. Returns the number of map files written or removed."""
    files = placed(mapped, found, baseline)
    changed = 0
    for path, text in sorted(files.items()):
        target = root / path
        if not target.is_file() or target.read_text(encoding='utf-8') != text:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding='utf-8')
            changed += 1
    for path in sorted(mapped.read - set(files)):
        (root / path).unlink(missing_ok=True)
        changed += 1
        directory = (root / path).parent
        while directory != root / MAP and directory.is_dir() and not any(directory.iterdir()):
            directory.rmdir()
            directory = directory.parent
    return changed


# ------------------------------------------------------------------ definitions, suites and pins

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

class NoBase(Exception):
    """The base of the change cannot be read where it must be."""


def base_baseline(root: Path, environment: dict[str, str]) -> dict[str, dict] | str:
    """The baseline of the change's base, in either layout, or why there is none to compare with. On a pull request
    in CI the base is the merge commit's first parent, which must be there (`NoBase` otherwise); elsewhere it is the
    merge base with origin/main, where that history is at hand."""
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
    files = revision_files(root, base.stdout.strip())
    if layout(files) is Layout.TREE and not read_tree(files).recorded:
        raise NoBase(f'the base has map files but no readable {BASELINE}')
    baseline = read_map(files).baseline()
    return 'the base has no baseline yet (this is the first)' if baseline is None else baseline


# ------------------------------------------------------------------ the check

@dataclass
class Outcome:
    problems: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    unmapped: dict[str, dict[str, Found]] = field(default_factory=dict)
    counts: dict[str, dict[str, int]] = field(default_factory=dict)
    found: dict[str, dict[str, Found]] = field(default_factory=dict)
    mapped: Map = field(default_factory=lambda: Map([], False))


def placement_problems(area: Area, mine: dict[str, Found], found: list[Found]) -> list[str]:
    """Each key in its source's map file, each map file at its source's path, and one source per map file."""
    out = []
    homes: dict[str, str] = {}
    for item in found:
        path = home(area, item.file)
        if path == f'{MAP}/{area.name}/{AREA_FILE}':
            out.append(f'{item.file}: its map file would be {path}, the area\'s own file')
        elif homes.setdefault(path, item.file) != item.file:
            out.append(f'{item.file} and {homes[path]}: both have the map file {path}')
    for path, source in sorted(area.sources.items()):
        want = home(area, source)
        if want is None:
            out.append(f'{path}: its source {source} is not a Scala source under the area\'s roots')
        elif want != path:
            out.append(f'{path}: names the source {source}, whose map file is {want}: run --tidy')
    for key in sorted(set(area.entries) | set(area.glue) | set(area.baseline)):
        listed = area.listed.get(key)
        if key in mine and listed != home(area, mine[key].file):
            out.append(f'{key}: listed in {listed}, but defined in {mine[key].file}, whose map file '
                       f'is {home(area, mine[key].file)}: run --tidy')
        elif key in mine and area.sources.get(listed) != mine[key].file:
            out.append(f'{listed}: names the source {area.sources.get(listed)}, but its key {key} is defined in '
                       f'{mine[key].file}: run --tidy')
    return out


def check(root: Path, sources: Path | None, write: bool, environment: dict[str, str] | None = None) -> Outcome:
    environment = dict(os.environ) if environment is None else environment
    outcome = Outcome()
    mapped = read_tree(disk_files(root))
    outcome.mapped = mapped
    all_areas = mapped.areas
    registered = pins(root)
    clones = Clones(sources)
    suites = test_suites(root)
    baseline = mapped.baseline()
    outcome.problems += mapped.problems
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
        outcome.found[area.name] = mine
        for key in sorted(set(area.entries) | set(area.glue)):
            if key in area.entries and key in area.glue:
                outcome.problems.append(f'{key}: both an entry and glue; a definition is listed once')
            if key not in mine:
                outcome.problems.append(f'{area.name}: {key} is listed but is not a definition of the area: '
                                        'remove it, or correct its key (--show)')
        outcome.problems += placement_problems(area, mine, found)
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
    baseline_check(root, baseline, outcome, write, environment)
    return outcome


def baseline_check(root: Path, baseline: dict[str, dict] | None, outcome: Outcome, write: bool,
                   environment: dict[str, str]) -> None:
    now = {area: set(keys) for area, keys in outcome.unmapped.items()}
    names = {area.name for area in outcome.mapped.areas}

    def record(kept: dict[str, dict]) -> None:
        write_tree(root, outcome.mapped, outcome.found, kept)

    if baseline is None:
        try:
            base = base_baseline(root, environment)
        except NoBase as error:
            outcome.problems.append(f'baseline growth cannot be checked: {error}')
            return
        if not isinstance(base, str):
            outcome.problems.append(f'{BASELINE} is missing and its base has one: restore it; a baseline is '
                                    'never written again from scratch')
        elif not write:
            outcome.problems.append(f'{BASELINE} is missing: run with --write-baseline to record the first one')
        elif outcome.problems:
            outcome.problems.append(f'{BASELINE} not written: the problems above come first')
        else:
            record({area: {k: None for k in keys} for area, keys in now.items()})
            outcome.notes.append(f'wrote the first baseline: {sum(map(len, now.values()))} definitions')
        return
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
            outcome.problems.append('keys added to the baseline since its base: a new definition is mapped or glue, '
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
        record({area: {k: v for k, v in baseline.get(area, {}).items() if k in now.get(area, set())}
                for area in sorted(names)})
        outcome.notes.append(f'wrote the baseline: {sum(map(len, now.values()))} definitions')
    elif gone and not write:
        outcome.problems.append('fewer unmapped definitions than the baseline lists: run with --write-baseline and '
                                'commit it:')
        outcome.problems += [f'  {area}: {key}' for area, key in gone]


def tidy(root: Path) -> int:
    """Moves each key to its source's map file and writes every file in its one form, the data unchanged."""
    mapped = read_tree(disk_files(root))
    if mapped.unread or mapped.twice:
        print('correspondence map: not tidied: join by hand first what these files list twice, or make them '
              'readable:\n' + '\n'.join(mapped.unread + sorted(set(mapped.twice))))
        return 1
    found = {area.name: {f.definition.key: f for f in definitions(root, area)[0]} for area in mapped.areas}
    try:
        changed = write_tree(root, mapped, found, mapped.baseline())
    except ValueError as error:
        print(f'correspondence map: not tidied: {error}')
        return 1
    after = read_tree(disk_files(root))
    if after.model() != mapped.model():
        print('correspondence map: tidying changed the data; restore the map from version control')
        return 1
    print(f'correspondence map: tidied, {changed} files written or removed')
    return 0


def show(root: Path, keys: list[str]) -> int:
    mapped = read_tree(disk_files(root))
    found = {}
    for area in mapped.areas:
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
                   else 'baseline' if key in area.baseline else 'unlisted')
        spans = ', '.join(f'{a}-{b}' for a, b in item.definition.lines)
        print(f'{key}\n  area {area.name}, {listing}\n  {item.file}:{spans}\n  digest {item.definition.digest}\n'
              f'  map file {home(area, item.file)}')
        hint = area.baseline.get(key)
        if hint:
            print(f'  cited counterpart: {json.dumps(hint)}')
    return status


def main(argv: list[str] | None = None, root: Path = ROOT, environment: dict[str, str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--sources', type=Path, default=None,
                        help=f'a directory of upstream clones, one per repository (default: ${SOURCES_ENV})')
    parser.add_argument('--write-baseline', action='store_true', help='record a smaller baseline; refuses new keys')
    parser.add_argument('--tidy', action='store_true',
                        help='move each key to its source\'s map file and write every file in its one form')
    parser.add_argument('--show', nargs='+', metavar='KEY', help='print where each definition is and its digest')
    parser.add_argument('--unmapped', metavar='AREA', help='list the baselined definitions of an area, by file')
    args = parser.parse_args(argv)
    if args.show:
        return show(root, args.show)
    if args.tidy:
        return tidy(root)
    sources = args.sources or (Path(os.environ[SOURCES_ENV]) if os.environ.get(SOURCES_ENV) else None)
    outcome = check(root, sources, args.write_baseline, environment)
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
