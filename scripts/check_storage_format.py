#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""STORAGE FORMAT CHANGE guard: persisted formats are frozen until Scarab leaves pre-release (FMT-1).

The guard computes the persisted-format surface from the tree and compares it with the committed manifest
`docs/data/storage-format.json`. Any difference fails, with a message that starts `STORAGE FORMAT CHANGE` and
points to FMT-1 (docs/design-rules.md). The surface is:

- `registry`: what `PersistedFormat.scala` declares: the `FormatVersion` cases, each `FileKind`'s label, families
  and history (version and identifier), the `readableFrom` cases, the `UtxoKeyLayout` cases and the current one,
  the `FormatLoss` cases, and a digest of the file's code (comments removed, white space collapsed);
- `magics`: every eight-byte format magic (`SCRB...`, `CSL...`) written as a string literal in a `src/main` Scala
  source of any module, by file, so that a new magic anywhere is seen;
- `migrations`: the registered snapshot steps of `SnapshotMigration.scala` (the `converted` chains and the
  `step` pairs), the `fromV<n>` conversions each `SnapshotV<n>` defines, and the files that implement
  `Migration[F]`;
- `goldens`: for `CodecSuite`, `FormatGoldenSuite` and `SnapshotGoldenSuite`, every string literal (the golden
  bytes, fingerprints, magics and file hashes, and the test names) and a digest of the suite's code;
- `previous_version_suites`: a code digest of each suite that reads a previous version (`*Migration*Suite`,
  `NetworkIdentity*Suite` in the storage test packages);
- `frozen_schemas`: a code digest of each frozen schema (`SnapshotSidecarV<n>.scala`, `Frozen*.scala`);
- `fixtures`: the SHA-256 of every file under `fixtures/snapshot-*` and `fixtures/network-identity-*` but their
  Markdown (the READMEs that record provenance).

Comments do not count, so a comment edit does not fire; a missing anchor (a file, a golden suite, a parse that
finds nothing) is a STORAGE FORMAT CHANGE too, never a smaller surface. Whatever the surface is, the snapshot kind
must stay readable from V8 with a registered migration path from V8 to its current version: deployed stores are
V8 (FMT-1).

The manifest holds the surface and the record of the approval that admitted it. Its `approval` entry holds
`question`, `asked_at`, `answer` and `answered_at` (UTC ISO 8601 with seconds and `Z`, answered after asked, not in
the future), all nonempty, the question naming the storage format, and `surface_sha256`, the digest of the surface
it admits. That entry RECORDS an approval the owner gave in the chat session doing the work, in answer to the
question asked there before the change was written. It is evidence of that answer, never an approval itself: this
guard checks only its presence and its form, and cannot see whether the owner gave it. With no approval entry the
surface must be the baseline this guard pins (`BASELINE_SURFACE_SHA256`), the surface of `main` when the freeze
began, which records what existed and approves nothing.

`FORMAT_CHANGE_PATHS` is the one list of the paths a storage format change may touch: `scripts/pr_policy.py`
refuses a pull request that changes the manifest or this guard and touches anything else, because a storage
format change is a pull request of its own. Every path the surface reads is in it.

    python3 scripts/check_storage_format.py                   check (the default)
    python3 scripts/check_storage_format.py --print-surface   print the surface and its digest as JSON

Pure standard library.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_imperative import comment_end, string_literal  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = 'docs/data/storage-format.json'
GUARD = 'scripts/check_storage_format.py'
RULE = 'docs/design-rules.md#fmt-1'
REGISTRY = 'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PersistedFormat.scala'
STORAGE_MAIN = 'storage/src/main/scala/xyz/kd5ujc/scarab/storage'
MIGRATION = f'{STORAGE_MAIN}/SnapshotMigration.scala'
GOLDEN_SUITES = (
    'node/src/test/scala/xyz/kd5ujc/scarab/storage/CodecSuite.scala',
    'storage/src/test/scala/xyz/kd5ujc/scarab/storage/FormatGoldenSuite.scala',
    'node/src/test/scala/xyz/kd5ujc/scarab/storage/SnapshotGoldenSuite.scala',
)
STORAGE_TESTS = ('node/src/test/scala/xyz/kd5ujc/scarab/storage', 'storage/src/test/scala/xyz/kd5ujc/scarab/storage')
PREVIOUS_VERSION_SUITE = re.compile(r'(?:\w*Migration\w*|NetworkIdentity\w*)Suite\.scala')
FROZEN_SCHEMA = re.compile(r'(?:SnapshotSidecarV\d+|Frozen\w+)\.scala')
FIXTURE_DIRS = ('fixtures/snapshot-*', 'fixtures/network-identity-*')
MAGIC = re.compile(r'(?:SCRB|CSL)[A-Z0-9]{4,5}')
DEPLOYED = 'V8'
# The digest of the surface of main (4a1a7ab96) on 2026-11-14, when the freeze began. It admits that surface with
# no approval entry; it approves nothing.
BASELINE_SURFACE_SHA256 = '5cce4194e3aa3b73e811744a6b0fa00b736c93cb55e34434cb927ff1804531bb'

# The paths a storage format change may touch, and nothing else (FMT-1). First what the owner named: the
# persisted-format code and its tests, the migration, the snapshot fixtures, the manifest, the storage-migration
# documents and a changelog fragment.
OWNER_NAMED_PATHS = (
    MANIFEST,
    GUARD,
    'scripts/tests/test_check_storage_format.py',
    REGISTRY,
    'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/NetworkIdentity.scala',
    f'{STORAGE_MAIN}/*.scala',
    'storage/src/test/scala/xyz/kd5ujc/scarab/storage/*.scala',
    'node/src/test/scala/xyz/kd5ujc/scarab/storage/*.scala',
    'node/src/test/scala/xyz/kd5ujc/scarab/node/NativeSnapshots.scala',
    'tools/src/main/scala/xyz/kd5ujc/scarab/storage/*.scala',
    'fixtures/snapshot-*/**',
    'fixtures/network-identity-*/**',
    'docs/storage-migrations.md',
    'docs/adr/0006-versioned-formats-migrate-on-open.md',
    'docs/architecture/persistence.md',
    'docs/running/snapshots.md',
    'changes/*.md',
)
# Then what the other gates require in the same change: the code map lists suites and entry points, the fixture
# inventory every fixture, the documentation check's baseline its findings, a level 3 change its compatibility
# register entry and page, a decision its register row, and a new decoder its fuzz target (CONTRIBUTING.md).
REQUIRED_BY_OTHER_GATES = (
    'docs/data/codemap.json',
    'docs/codemap.md',
    'docs/data/fixture-inventory.json',
    'docs/data/docs-check-baseline.json',
    'docs/data/compatibility.json',
    'docs/compatibility.md',
    'docs/compatibility/persistence*.md',
    'docs/decisions.md',
    'fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala',
)
FORMAT_CHANGE_PATHS = OWNER_NAMED_PATHS + REQUIRED_BY_OTHER_GATES
# A pull request that changes one of these is a storage format change (scripts/pr_policy.py).
FREEZE_PATHS = (MANIFEST, GUARD)
NOT_A_FRAGMENT = 'changes/README.md'

FIELDS = ('question', 'asked_at', 'answer', 'answered_at')
UTC = re.compile(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z')
CLOCK_SKEW = timedelta(minutes=10)
STORAGE_FORMAT = re.compile(r'\bstorage[ -]format\b', re.IGNORECASE)


class SurfaceError(Exception):
    """An anchor of the surface is missing or no longer parses: a storage format change in itself."""


# ------------------------------------------------------------------ the allowlist

def pattern(glob: str) -> re.Pattern[str]:
    """`**` is any text, `*` any text within one path segment."""
    parts = re.split(r'(\*\*|\*)', glob)
    return re.compile(''.join('.*' if p == '**' else '[^/]*' if p == '*' else re.escape(p) for p in parts))


ALLOWED = tuple(pattern(g) for g in FORMAT_CHANGE_PATHS)


def allowed(path: str) -> bool:
    return path != NOT_A_FRAGMENT and any(p.fullmatch(path) for p in ALLOWED)


def outside_format_change(files) -> list[str]:
    """The changed paths that a storage format change may not touch."""
    return sorted(f for f in files if not allowed(f))


def touches_freeze(files) -> bool:
    return any(f in FREEZE_PATHS for f in files)


# ------------------------------------------------------------------ the approval record

def parse_utc(text) -> datetime | None:
    if not isinstance(text, str) or not UTC.fullmatch(text.strip()):
        return None
    return datetime.fromisoformat(text.strip().replace('Z', '+00:00'))


def approval_problems(entry, now: datetime) -> list[str]:
    """What is wrong with the form of an approval record (`question`, `asked_at`, `answer`, `answered_at`); nothing
    when it is well formed. The form is all a tool can check: whether the owner gave the answer in the chat session
    doing the work is not in the tree."""
    if not isinstance(entry, dict):
        return ['the approval record is an object with question, asked_at, answer and answered_at']
    found = [f'{f} is missing or empty' for f in FIELDS if not isinstance(entry.get(f), str) or not entry[f].strip()]
    if found:
        return found
    asked, answered = parse_utc(entry['asked_at']), parse_utc(entry['answered_at'])
    for name, value in (('asked_at', asked), ('answered_at', answered)):
        if value is None:
            found.append(f'{name} is not a UTC ISO 8601 time with seconds and Z, such as 2026-11-14T09:30:00Z')
    if asked is not None and answered is not None:
        if answered <= asked:
            found.append('answered_at is not after asked_at: the question comes first')
        if answered > now + CLOCK_SKEW:
            found.append('answered_at is in the future')
    if not STORAGE_FORMAT.search(entry['question']):
        found.append('the question does not say plainly that it is a storage format change')
    return found


# ------------------------------------------------------------------ reading Scala sources

def scan(source: str) -> tuple[str, list[str]]:
    """`source` without its comments (string literals kept as written), and the contents of its string literals.
    Uses the literal and comment rules of `check_imperative`."""
    out: list[str] = []
    literals: list[str] = []
    i, n = 0, len(source)
    while i < n:
        if source.startswith('/*', i):
            i = comment_end(source, i)
            out.append(' ')
        elif source.startswith('//', i):
            end = source.find('\n', i)
            out.append(' ')
            i = n if end < 0 else end
        elif source[i] == '"':
            interpolated = i > 0 and (source[i - 1].isalnum() or source[i - 1] == '_')
            triple = source.startswith('"""', i)
            quote = 3 if triple else 1
            end, _ = string_literal(source, i + quote, triple, interpolated)
            out.append(source[i:end])
            literals.append(source[i + quote:max(i + quote, end - quote)])
            i = end
        elif source[i] == "'" and i + 2 < n and (source[i + 2] == "'" or source[i + 1] == '\\'):
            end = source.find("'", i + 2)
            end = n if end < 0 else end + 1
            out.append(source[i:end])
            i = end
        else:
            out.append(source[i])
            i += 1
    return ''.join(out), literals


def code_digest(source: str) -> str:
    code, _ = scan(source)
    return hashlib.sha256(re.sub(r'\s+', ' ', code).strip().encode()).hexdigest()


def read(root: Path, path: str) -> str:
    file = root / path
    if not file.is_file():
        raise SurfaceError(f'{path} is gone')
    return file.read_text(encoding='utf-8')


def required(found, what: str):
    if not found:
        raise SurfaceError(f'{what} not found')
    return found


def block(code: str, start: str, end: str, what: str) -> str:
    """The text from the first match of `start` to the next match of `end` after it."""
    opened = required(re.search(start, code), what)
    closed = re.search(end, code[opened.end():])
    return code[opened.start():opened.end() + (closed.start() if closed else len(code) - opened.end())]


# ------------------------------------------------------------------ the surface

def registry(root: Path) -> dict:
    code, _ = scan(read(root, REGISTRY))
    versions = required(re.search(r'enum FormatVersion\b[^:]*:\s*case ([^\n]+)', code), 'the FormatVersion cases')
    kinds = {}
    for match in required(list(re.finditer(r'case (\w+)\s+extends FileKind\(', code)), 'the FileKind cases'):
        body = block(code[match.start():], r'case \w+\s+extends FileKind\(', r'\n\s*case \w+\s+extends|\n\s*def ',
                      f'FileKind.{match.group(1)}')
        families = required(re.search(r'List\(([^)]*)\)', body), f'the families of {match.group(1)}')
        kinds[match.group(1)] = {
            'label': required(re.search(r'"([^"]*)"', body), f'the label of {match.group(1)}').group(1),
            'families': re.findall(r'"([^"]*)"', families.group(1)),
            'history': [[v, ident] for v, ident in required(
                re.findall(r'FormatVersion\.(V\d+)\s*->\s*"([^"]*)"', body), f'the history of {match.group(1)}')],
        }
    readable = block(code, r'def readableFrom\b', r'\n\s*def ', 'readableFrom')
    layouts = block(code, r'enum UtxoKeyLayout\b', r'\nobject UtxoKeyLayout\b', 'UtxoKeyLayout')
    losses = block(code, r'enum FormatLoss\b', r'\n\s*def ', 'FormatLoss')
    return {
        'versions': [v.strip() for v in versions.group(1).split(',')],
        'kinds': kinds,
        'readableFrom': {k: v.strip() for k, v in required(
            re.findall(r'case (\w+)\s*=>\s*([^\n]+)', readable), 'the readableFrom cases')},
        'utxoKeyLayouts': required(re.findall(r'\n\s*case (\w+)', layouts), 'the UtxoKeyLayout cases'),
        'utxoKeyLayoutCurrent': required(re.search(r'val current: UtxoKeyLayout = (\w+)', code),
                                         'the current UtxoKeyLayout').group(1),
        'formatLosses': required(re.findall(r'\n\s*case (\w+\([^)]*\))', losses), 'the FormatLoss cases'),
        'code_sha256': code_digest(read(root, REGISTRY)),
    }


def scala_main_sources(root: Path) -> list[str]:
    return sorted(p.relative_to(root).as_posix() for p in root.glob('*/src/main/**/*.scala')
                  if '/src_managed/' not in p.as_posix())


def mentioning(root: Path, raw: re.Pattern[str]) -> list[tuple[str, str]]:
    """The main sources whose raw text matches `raw`, with their text: a cheap filter before the lexer, which
    only drops what cannot match after it."""
    return [(p, t) for p in scala_main_sources(root) for t in (read(root, p),) if raw.search(t)]


def magics(root: Path) -> dict:
    found = {}
    for path, text in mentioning(root, MAGIC):
        _, literals = scan(text)
        names = sorted({lit for lit in literals if len(lit) == 8 and MAGIC.fullmatch(lit)})
        if names:
            found[path] = names
    return required(found, 'the format magics in src/main')


def migrations(root: Path) -> dict:
    code, _ = scan(read(root, MIGRATION))
    converted = block(code, r'def converted\b', r'\n\s*(?:/\*\*|def |final class )', 'SnapshotMigration.converted')
    chains = {}
    for match in required(list(re.finditer(r'case FormatVersion\.(V\d+)\s*=>', converted)), 'the converted cases'):
        rest = converted[match.end():]
        nxt = re.search(r'case ', rest)
        chains[match.group(1)] = re.findall(r'(SnapshotV\d+)\s*\.\s*(fromV\d+)', rest[:nxt.start() if nxt else None])
    steps = required(re.findall(r'case \(FormatVersion\.(V\d+),\s*FormatVersion\.(V\d+)\)', code), 'the step pairs')
    conversions = {}
    for path in sorted((root / STORAGE_MAIN).glob('SnapshotV*.scala')):
        body, _ = scan(path.read_text(encoding='utf-8'))
        conversions[path.stem] = sorted(set(re.findall(r'\bdef (fromV\d+)\b', body)))
    implementers = sorted(p for p, t in mentioning(root, re.compile(r'\bMigration\['))
                          if re.search(r'\bnew Migration\[|\bextends\b[^{\n]*\bMigration\[', scan(t)[0]))
    return {
        'converted': {v: [f'{o}.{f}' for o, f in c] for v, c in chains.items()},
        'steps': [list(s) for s in steps],
        'conversions': required(conversions, 'the SnapshotV<n> readers'),
        'implementers': required(implementers, 'the Migration implementations'),
    }


def goldens(root: Path) -> dict:
    out = {}
    for path in GOLDEN_SUITES:
        source = read(root, path)
        _, literals = scan(source)
        out[path] = {'literals': sorted(set(required(literals, f'the literals of {path}'))),
                     'code_sha256': code_digest(source)}
    return out


def digests_of(root: Path, directories, name: re.Pattern[str], what: str) -> dict:
    found = {p.relative_to(root).as_posix(): code_digest(p.read_text(encoding='utf-8'))
             for d in directories for p in (root / d).glob('*.scala') if name.fullmatch(p.name)}
    return dict(sorted(required(found, what).items()))


def fixtures(root: Path) -> dict:
    found = {}
    for glob in FIXTURE_DIRS:
        for directory in root.glob(glob):
            for file in directory.rglob('*'):
                if file.is_file() and file.suffix != '.md':
                    found[file.relative_to(root).as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
    return dict(sorted(required(found, 'the snapshot and identity fixtures').items()))


def surface(root: Path = ROOT) -> dict:
    return {
        'registry': registry(root),
        'magics': magics(root),
        'migrations': migrations(root),
        'goldens': goldens(root),
        'previous_version_suites': digests_of(root, STORAGE_TESTS, PREVIOUS_VERSION_SUITE,
                                              'the previous-version suites'),
        'frozen_schemas': digests_of(root, (STORAGE_MAIN,), FROZEN_SCHEMA, 'the frozen schemas'),
        'fixtures': fixtures(root),
    }


def digest(found: dict) -> str:
    return hashlib.sha256(json.dumps(found, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def surface_paths(root: Path = ROOT) -> list[str]:
    """Every file the surface reads, for the check that the allowlist covers them."""
    found = surface(root)
    return sorted({REGISTRY, MIGRATION, *found['magics'], *found['goldens'], *found['previous_version_suites'],
                   *found['frozen_schemas'], *found['fixtures'],
                   *(f'{STORAGE_MAIN}/{name}.scala' for name in found['migrations']['conversions']),
                   *found['migrations']['implementers']})


# ------------------------------------------------------------------ the checks

def deployed_problems(found: dict) -> list[str]:
    """The snapshot kind stays readable from V8 and migrates from V8 to its current version."""
    reg = found['registry']
    snapshot = reg['kinds'].get('SnapshotFile')
    if snapshot is None:
        return ['the SnapshotFile kind is gone']
    versions, history = reg['versions'], [v for v, _ in snapshot['history']]
    out = []
    oldest = reg['readableFrom'].get('SnapshotFile', 'FormatVersion.' + history[0]).removeprefix('FormatVersion.')
    if DEPLOYED not in history or oldest not in versions or versions.index(oldest) > versions.index(DEPLOYED):
        out.append(f'the snapshot reader of {DEPLOYED} is gone (readableFrom {oldest})')
    steps, at, seen = dict((a, b) for a, b in found['migrations']['steps']), DEPLOYED, {DEPLOYED}
    while at != history[-1] and steps.get(at) not in (None, *seen):
        at = steps[at]
        seen.add(at)
    if at != history[-1]:
        out.append(f'no registered migration path from {DEPLOYED} to {history[-1]} (it stops at {at})')
    return out


def flatten(value, prefix=''):
    if isinstance(value, dict):
        for key, inner in value.items():
            yield from flatten(inner, f'{prefix}.{key}' if prefix else key)
    else:
        yield prefix, value


def differences(recorded: dict, found: dict, limit: int = 40) -> list[str]:
    old, new = dict(flatten(recorded)), dict(flatten(found))
    lines = [f'  added    {k}' for k in sorted(new.keys() - old.keys())]
    lines += [f'  removed  {k}' for k in sorted(old.keys() - new.keys())]
    lines += [f'  changed  {k}' for k in sorted(old.keys() & new.keys()) if old[k] != new[k]]
    return lines[:limit] + ([f'  ... and {len(lines) - limit} more'] if len(lines) > limit else [])


def loud(lines) -> str:
    return '\n'.join(['STORAGE FORMAT CHANGE', *lines, '',
                      f'Persisted formats are frozen until Scarab leaves pre-release ({RULE}). Stop: ask the owner',
                      'explicitly, in the chat session doing the work, before the change is written; a subagent',
                      'stops and reports to its lead. A change the owner approves there is a pull request of its',
                      f'own, and {MANIFEST} records the question and the answer with their UTC times.'])


def problems(root: Path, manifest, now: datetime) -> list[str]:
    """Empty when the tree's surface is the manifest's and the manifest's record admits it; otherwise the report."""
    try:
        found = surface(root)
    except SurfaceError as error:
        return [loud([f'the persisted-format surface cannot be read: {error}'])]
    out = []
    held = deployed_problems(found)
    if held:
        out.append(loud([f'FMT-1: the {DEPLOYED} reader and the migration path from {DEPLOYED} stay.', *held]))
    if not isinstance(manifest, dict) or not isinstance(manifest.get('surface'), dict):
        return out + [loud([f'{MANIFEST} has no surface'])]
    if manifest['surface'] != found:
        out.append(loud([f'the persisted-format surface differs from {MANIFEST}:',
                         *differences(manifest['surface'], found)]))
        return out
    approval, current = manifest.get('approval'), digest(found)
    if approval is None:
        if current != BASELINE_SURFACE_SHA256:
            out.append(loud([f'{MANIFEST} records a surface other than the baseline, with no approval entry.']))
        return out
    trouble = approval_problems(approval, now)
    if isinstance(approval, dict) and approval.get('surface_sha256') != current:
        trouble.append('its surface_sha256 is not the digest of this surface: it records the approval of another '
                       'change')
    if trouble:
        out.append(loud([f'the approval entry of {MANIFEST} is not well formed:', *(f'  {t}' for t in trouble)]))
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--print-surface', action='store_true', help='print the surface and its digest as JSON')
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args(argv)
    if args.print_surface:
        found = surface(args.root)
        print(json.dumps({'surface': found, 'surface_sha256': digest(found)}, indent=1, sort_keys=True))
        return 0
    manifest_file = args.root / MANIFEST
    manifest = json.loads(manifest_file.read_text(encoding='utf-8')) if manifest_file.is_file() else None
    found = problems(args.root, manifest, datetime.now(timezone.utc))
    for report in found:
        print(report, file=sys.stderr)
    if not found:
        print('storage format: unchanged from docs/data/storage-format.json')
    return 1 if found else 0


if __name__ == '__main__':
    sys.exit(main())
