#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""STORAGE FORMAT CHANGE guard: persisted formats are frozen until Scarab leaves pre-release (FMT-1).

The guard computes the persisted-format surface from the tree and compares it with the committed manifest
`docs/data/storage-format.json`. Any difference fails, with a message that starts `STORAGE FORMAT CHANGE` and
points to FMT-1 (docs/design-rules.md). The surface is:

- `registry`: what `PersistedFormat.scala` declares: the `FormatVersion` cases, each `FileKind`'s label, families
  and history (version and identifier), the `readableFrom` cases, the `UtxoKeyLayout` cases and the current one,
  the `FormatLoss` cases, and a digest of the file's code;
- `magics`: every eight-byte format magic (`SCRB...`, `CSL...`) written as a string literal in a `src/main` Scala
  source of any module, by file, so that a new magic anywhere is seen;
- `migrations`: the registered snapshot steps of `SnapshotMigration.scala` (the `converted` chains and the `step`
  pairs), the `fromV<n>` conversions each `SnapshotV<n>` defines, and the files that implement `Migration[F]`.
  They must agree with the registry: one step between each pair of consecutive readable snapshot versions, each
  conversion defined by its `SnapshotV<n>`, and each `converted` chain the steps from its version to the current
  one; a tree in which they do not is refused, whatever the manifest says;
- `format_sources`: a code digest of each source that is nothing but persisted-format code: the readers and writers
  of the snapshot versions (`SnapshotV<n>.scala`), the sidecar schema, the checkpoint binding and its digest, the
  snapshot migration, the generic migration and atomic replacement, the storage codec and its instances, the
  network identity record and its file, and the codecs of the Haskell ledger state that a V10 snapshot's body holds
  (`HASKELL_STATE_SOURCES`, in `haskell/state`: the codec traits from `VocabularyCodecs` to `LedgerFileCodecs`, the
  objects they write with, `Envelope`, `Wire`, `Record`, `Params`, `ConwayCodecs` and `NodeOrders`, and the choice of
  those codecs by layout, `HaskellStateCodecs`, `HaskellLedgerFile`, `HaskellState`, `ReferenceLayout` and
  `ProposalsReading`). The state codecs also write node-to-client answers and a Haskell node's `state` file for
  import and export; an edit made for those is a storage format change too;
- `format_members`: a code digest of each persisted-format definition in a file that also holds other code: the
  UTxO store's key and value coding (`UtxoStore.Coding`), the memory store's `utxo.bin` (`MemoryUtxoStore`'s
  `checkpoint` and `restore`), the RocksDB store's stamp key, its writes and stamp read and its companion (key
  layouts and their conversion), the immutable store's index and `format` file (`FileImmutableStore`'s magic,
  sizes, entry codec and migrations), the volatile log's records (`VolatileRecord`, `VolatileStore`'s sizes,
  kinds, flags, codec and file names), the snapshot file's checksum and name (`FileLedgerSnapshots`) and the
  checkpoint directory's name (`StateBacking.stem`). A definition is its header line and what follows while a
  bracket it opened is open or the lines are indented under it;
- `frozen_schemas`: a code digest of each frozen schema (`SnapshotSidecarV<n>.scala`, `Frozen*.scala`);
- `goldens`: for `CodecSuite`, `FormatGoldenSuite` and `SnapshotGoldenSuite`, every string literal (the golden
  bytes, fingerprints, magics and file hashes, and the test names) and a code digest;
- `previous_version_suites`: a code digest of each suite that reads a previous version (`*Migration*Suite`,
  `NetworkIdentity*Suite` in the storage test packages);
- `fixtures`: the SHA-256 of every file under `fixtures/snapshot-*` and `fixtures/network-identity-*` but their
  Markdown (the READMEs that record provenance).

A code digest is of the source without its comments, with trailing white space and empty lines dropped outside
string literals; literals and indentation are kept as written, so a comment edit does not fire and a change of
meaning does. A
missing anchor (a file, a definition, a parse that finds nothing) is a STORAGE FORMAT CHANGE too, never a smaller
surface. Whatever the surface is, the snapshot kind must stay readable from V8 with a registered migration path
from V8 to its current version: deployed stores are V8 (FMT-1).

The guard does not see a persisted byte that none of these pins: a layout written inline in the rest of a file it
reads in part (the immutable store's chunk and index file names in its `open` and preflight code, the RocksDB
options in the store's class), or an encoding defined in a file not listed here. Of the Haskell state, it does not
see the codecs no V10 snapshot reaches (`UtxoCodecs`, `DijkstraCodecs`, the tables, `meta` file and checksum of a
Haskell node's snapshot directory, the ancillary manifest), the decoders of other packages the state codecs call (a
certificate's, a proposal's, a block's), the CBOR decoder, the values they write as they hold them (an era's index,
a script's tag, bytes kept as they were read), the ledger's conversions and orders they write in (`Convert`,
`Sorted`, the orders of the ledger's types) and the choice of a layout by build (`StateLayout.of`). Its docstring
is the list of what it sees.

The manifest holds the surface and the record of the approval that admitted it. Its `approval` entry holds
`question`, `asked_at`, `decision` and `decided_at` (UTC ISO 8601 with seconds and `Z`, decided after asked, not
in the future), all nonempty, the question naming the storage format, and `surface_sha256`, the digest of the
surface it admits. The question is recorded as it was asked and the decision in neutral words, never as the owner's
exact answer. That entry RECORDS an approval the owner gave in the chat session doing the work, in answer to the
question asked there before the change was written. It is evidence of that decision, never an approval itself: this
guard checks only its presence and its form, and cannot see whether the owner gave it. With no approval entry the
surface must be the baseline this guard pins (`BASELINE_SURFACE_SHA256`), the surface of `main` when the freeze
began, which records what existed and approves nothing.

`FORMAT_CHANGE_PATHS` is the one list of the paths a storage format change may touch, beside the paths of a one-off
exception the owner approved for one pull request (`ONE_OFF_PATHS`, by its number, read from the policy check's
event), and `FREEZE_PATHS` the paths
whose change makes a pull request one: `scripts/pr_policy.py` refuses such a pull request unless it follows FMT-1,
and touches nothing outside the list, because a storage format change is a pull request of its own. Every path the
surface reads is in the list. The guard is self-contained, so that no other script's change alters what it sees.

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
import os
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = 'docs/data/storage-format.json'
GUARD = 'scripts/check_storage_format.py'
GUARD_TESTS = 'scripts/tests/test_check_storage_format.py'
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
SNAPSHOT_READER = re.compile(r'SnapshotV(\d+)\.scala')
# The sources that are nothing but persisted-format code, besides the `SnapshotV<n>` readers and the frozen schemas.
FORMAT_SOURCES = (
    'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/NetworkIdentity.scala',
    f'{STORAGE_MAIN}/NetworkIdentityFile.scala',
    f'{STORAGE_MAIN}/SnapshotSidecar.scala',
    f'{STORAGE_MAIN}/CheckpointBinding.scala',
    MIGRATION,
    f'{STORAGE_MAIN}/Migrate.scala',
    f'{STORAGE_MAIN}/Codec.scala',
    f'{STORAGE_MAIN}/NodeCodecs.scala',
    f'{STORAGE_MAIN}/ProductFields.scala',
)
HASKELL_STATE = f'{STORAGE_MAIN}/haskell/state'
# The codecs of the Haskell ledger state that a V10 snapshot's body holds (`SnapshotV5.parts` and `decodeBody`, through
# `ReferenceLayout`), and nothing else: the files of `haskell/state` that no snapshot reaches are not here. Added under
# the owner's approval of 2026-11-20 (asked 03:23:52Z, answered 03:23:56Z).
HASKELL_STATE_SOURCES = tuple(f'{HASKELL_STATE}/{name}.scala' for name in (
    'ReferenceLayout', 'HaskellLedgerFile', 'HaskellState', 'HaskellStateCodecs', 'ProposalsReading',
    'LedgerFileCodecs', 'ConsensusCodecs', 'EpochStateCodecs', 'RewardCodecs', 'SnapshotCodecs', 'GovStateCodecs',
    'CertStateCodecs', 'NodeTypeCodecs', 'VocabularyCodecs',
    'Envelope', 'Wire', 'Record', 'Params', 'ConwayCodecs', 'NodeOrders',
))
# The persisted-format definitions in files that also hold other code: (name, file, enclosing definition or None,
# the definition), each a header the code holds once; a definition is its header line and the lines indented under it.
FORMAT_MEMBERS = (
    ('UtxoStore.Coding', f'{STORAGE_MAIN}/UtxoStore.scala', None, r'object Coding:'),
    ('MemoryUtxoStore.checkpoint', f'{STORAGE_MAIN}/UtxoStore.scala', r'final class MemoryUtxoStore\b',
     r'def checkpoint\('),
    ('MemoryUtxoStore.restore', f'{STORAGE_MAIN}/UtxoStore.scala', r'final class MemoryUtxoStore\b', r'def restore\('),
    ('RocksUtxoStore.StampKey', f'{STORAGE_MAIN}/RocksUtxoStore.scala', r'final class RocksUtxoStore\b',
     r'private val StampKey\b'),
    ('RocksUtxoStore.write', f'{STORAGE_MAIN}/RocksUtxoStore.scala', r'final class RocksUtxoStore\b', r'def write\('),
    ('RocksUtxoStore.flushed', f'{STORAGE_MAIN}/RocksUtxoStore.scala', r'final class RocksUtxoStore\b',
     r'def flushed\b'),
    ('object RocksUtxoStore', f'{STORAGE_MAIN}/RocksUtxoStore.scala', None, r'object RocksUtxoStore:'),
    *(('FileImmutableStore.' + name, f'{STORAGE_MAIN}/ImmutableStore.scala', r'object FileImmutableStore:', header)
      for name, header in (('IndexMagic', r'val IndexMagic\b'), ('FormatText', r'val FormatText\b'),
                           ('HeaderSize', r'val HeaderSize\b'), ('EntrySize', r'val EntrySize\b'),
                           ('magicOf', r'private def magicOf\('),
                           ('indexMigration', r'private\[storage\] def indexMigration\['),
                           ('formatMigration', r'private\[storage\] def formatMigration\['),
                           ('encode', r'private\[storage\] def encode\('), ('decode', r'private def decode\('),
                           ('crcOf', r'private\[storage\] def crcOf\('),
                           ('entries', r'private\[storage\] def entries\('))),
    ('VolatileRecord', f'{STORAGE_MAIN}/VolatileStore.scala', None, r'enum VolatileRecord:'),
    *(('VolatileStore.' + name, f'{STORAGE_MAIN}/VolatileStore.scala', r'object VolatileStore:', header)
      for name, header in (('HeaderSize', r'val HeaderSize\b'), ('KindBlock', r'private val KindBlock\b'),
                           ('KindRollback', r'private val KindRollback\b'),
                           ('FlagBoundary', r'private val FlagBoundary\b'),
                           ('FlagOrigin', r'private val FlagOrigin\b'), ('encode', r'def encode\('),
                           ('decodeAll', r'def decodeAll\('), ('Scanned', r'private object Scanned:'),
                           ('Suffix', r'private val Suffix\b'), ('nameOf', r'private def nameOf\('))),
    ('FileLedgerSnapshots', f'{STORAGE_MAIN}/LedgerSnapshots.scala', None, r'object FileLedgerSnapshots:'),
    ('StateBacking.stem', f'{STORAGE_MAIN}/StateBacking.scala', r'object StateBacking:', r'def stem\('),
    ('StateBacking.Stem', f'{STORAGE_MAIN}/StateBacking.scala', r'object StateBacking:', r'private val Stem\b'),
    ('StateBacking.isStem', f'{STORAGE_MAIN}/StateBacking.scala', r'object StateBacking:', r'def isStem\('),
)
FIXTURE_DIRS = ('fixtures/snapshot-*', 'fixtures/network-identity-*')
MAGIC = re.compile(r'(?:SCRB|CSL)[A-Z0-9]{4,5}')
DEPLOYED = 'V8'
# The digest of the surface of main (bc0e4995a, after #994 merged) on 2026-11-14, when the freeze began. It admits
# that surface with no approval entry; it approves nothing.
BASELINE_SURFACE_SHA256 = '70764189f895a35b3fd61c4f52d4e968c8fc3e729ea0dfe5d4d8e6f8f8289c0d'

# The paths a storage format change may touch, and nothing else (FMT-1). First what the owner named: the
# persisted-format code and its tests, the migration, the snapshot fixtures, the manifest, the storage-migration
# documents and a changelog fragment.
OWNER_NAMED_PATHS = (
    MANIFEST,
    GUARD,
    GUARD_TESTS,
    REGISTRY,
    'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/NetworkIdentity.scala',
    f'{STORAGE_MAIN}/*.scala',
    *HASKELL_STATE_SOURCES,
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
# register entry and page, a decision its register row, a new decoder its fuzz target (CONTRIBUTING.md), and the
# correspondence map an entry or a glue line for each new definition of a mirrored area (REF-6).
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
    'docs/data/correspondence/*.json',
)
FORMAT_CHANGE_PATHS = OWNER_NAMED_PATHS + REQUIRED_BY_OTHER_GATES
# Paths one storage format change may touch beyond FORMAT_CHANGE_PATHS, by the number of its pull request: an
# exception the owner approved for that pull request only, which no other pull request gets. The number is read
# from the event of the policy check (`GITHUB_EVENT_PATH`), which GitHub writes and the trusted policy reads. A path
# that FORMAT_CHANGE_PATHS came to hold later is dropped from its exception: the codecs of the Haskell state
# (`HASKELL_STATE_SOURCES`), which #1127 and #1173 touched.
ONE_OFF_PATHS = {
    # #1127: PlutusV4 among the kinds of a stored script, V10 changed in place (#85, #54; approved
    # 2026-11-17T01:35:35Z). `ScriptKind` is declared in `Scripts.scala`, and the other files match on it
    # exhaustively (`ConwayCodecs.scala`, one of them, is now on the general list).
    1127: (
        'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Scripts.scala',
        'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Phase2.scala',
        'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/TxChecks.scala',
        'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/era/babbage/BabbageTypes.scala',
        'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/era/conway/ConwayTypes.scala',
        'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/mempack/TxOutMemPack.scala',
    ),
    # #1173: Praos2 among the chain-dependent states, V10 changed in place (#826; approved 2026-11-17T21:13:31Z,
    # these paths 2026-11-17T21:53:53Z). `ChainDepState` is declared in `ChainDepState.scala`; seven files match on it
    # exhaustively (`ConsensusCodecs.scala`, one of them, is now on the general list), `Praos2.scala` gives its state
    # the equality and rendering the enum derives, and the rest are the case's generator, its evidence and tests, and a
    # sentence of `docs/leios.md` the change makes false.
    1173: (
        'consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/Praos.scala',
        'consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/Praos2.scala',
        'consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/TPraos.scala',
        'consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/state/ChainDepState.scala',
        'consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/state/ProtocolState.scala',
        'consensus/src/test/scala/xyz/kd5ujc/scarab/consensus/state/StateGen.scala',
        'docs/leios.md',
        'node/src/main/scala/xyz/kd5ujc/scarab/node/NodeHeaderRules.scala',
        'node/src/main/scala/xyz/kd5ujc/scarab/node/ntc/QueryAnswers.scala',
        'node/src/test/scala/xyz/kd5ujc/scarab/consensus/DijkstraCrossingSuite.scala',
        'storage/src/test/scala/xyz/kd5ujc/scarab/storage/haskell/state/LeiosLayoutSuite.scala',
        'tools/src/main/scala/xyz/kd5ujc/scarab/node/HaskellScalaCompareMain.scala',
    ),
}
# A pull request that changes one of these is a storage format change (scripts/pr_policy.py): the manifest, and the
# guard and its tests, so that weakening the guard is as loud as a format change.
FREEZE_PATHS = (MANIFEST, GUARD, GUARD_TESTS)
NOT_A_FRAGMENT = 'changes/README.md'

FIELDS = ('question', 'asked_at', 'decision', 'decided_at')
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


def event_paths(argv=None, environ=None) -> list[str]:
    """The event files this process was given: `GITHUB_EVENT_PATH`, which GitHub writes for the policy check, and the
    `--event` argument of `scripts/pr_policy.py`, which overrides it there."""
    argv = sys.argv[1:] if argv is None else argv
    environ = os.environ if environ is None else environ
    given = [environ.get('GITHUB_EVENT_PATH')]
    # argparse takes any prefix of `--event` that no other option of pr_policy.py shares, from `--e`, with its value
    # after it or after `=`
    def event_option(arg: str) -> bool:
        return len(arg) >= 3 and '--event'.startswith(arg)
    for i, arg in enumerate(argv):
        option, equals, value = arg.partition('=')
        if event_option(option) and equals:
            given.append(value)
        elif event_option(arg) and i + 1 < len(argv):
            given.append(argv[i + 1])
    return [p for p in given if p]


def policy_pull_request(argv=None, environ=None) -> int | None:
    """The pull request the policy check is for: the one every event this process was given names; none when there
    is no event, or the events disagree or name none, so an exception is never granted on a doubt."""
    numbers = {event_pull_request(p) for p in event_paths(argv, environ)}
    return numbers.pop() if len(numbers) == 1 else None


def event_pull_request(path: str | None) -> int | None:
    """The number of the pull request the event at `path` names; none when it is missing, unreadable or names no
    pull request."""
    if not path:
        return None
    try:
        event = json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError, UnicodeDecodeError):
        return None
    pull = event.get('pull_request') if isinstance(event, dict) else None
    number = pull.get('number') if isinstance(pull, dict) else None
    return number if isinstance(number, int) and not isinstance(number, bool) else None


def outside_format_change(files, pull_request: int | None = None) -> list[str]:
    """The changed paths that a storage format change may not touch: those outside FORMAT_CHANGE_PATHS and outside
    the one-off exception of its pull request (by default the one the policy check's events name)."""
    number = pull_request if pull_request is not None else policy_pull_request()
    once = ONE_OFF_PATHS.get(number, ()) if number is not None else ()
    return sorted(f for f in files if not allowed(f) and f not in once)


def touches_freeze(files) -> bool:
    return any(f in FREEZE_PATHS for f in files)


# ------------------------------------------------------------------ the approval record

def parse_utc(text) -> datetime | None:
    if not isinstance(text, str) or not UTC.fullmatch(text.strip()):
        return None
    try:
        return datetime.fromisoformat(text.strip().replace('Z', '+00:00'))
    except ValueError:
        return None


def approval_problems(entry, now: datetime) -> list[str]:
    """What is wrong with the form of an approval record (`question`, `asked_at`, `decision`, `decided_at`); nothing
    when it is well formed. The form is all a tool can check: whether the owner gave that decision in the chat
    session doing the work is not in the tree."""
    if not isinstance(entry, dict):
        return ['the approval record is an object with question, asked_at, decision and decided_at']
    found = [f'{f} is missing or empty' for f in FIELDS if not isinstance(entry.get(f), str) or not entry[f].strip()]
    if found:
        return found
    asked, decided = parse_utc(entry['asked_at']), parse_utc(entry['decided_at'])
    for name, value in (('asked_at', asked), ('decided_at', decided)):
        if value is None:
            found.append(f'{name} is not a UTC ISO 8601 time with seconds and Z, such as 2026-11-14T09:30:00Z')
    if asked is not None and decided is not None:
        if decided <= asked:
            found.append('decided_at is not after asked_at: the question comes first')
        if decided > now + CLOCK_SKEW:
            found.append('decided_at is in the future')
    if not STORAGE_FORMAT.search(entry['question']):
        found.append('the question does not say plainly that it is a storage format change')
    return found


# ------------------------------------------------------------------ reading Scala sources

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


def literal_end(source: str, i: int, triple: bool, interpolated: bool) -> int:
    """From just after the opening quote: the index after the closing quote. An interpolation's `${...}` is skipped
    as code, so a quote or a brace inside it does not end the literal."""
    n = len(source)
    while i < n:
        if triple and source.startswith('"""', i):
            i += 3
            while i < n and source[i] == '"':
                i += 1
            return i
        if not triple and source[i] in '"\n':
            return i + 1
        if interpolated and source.startswith('${', i):
            i = expression_end(source, i + 2)
        elif interpolated and (source.startswith('$$', i) or source.startswith('$"', i)):
            i += 2
        elif source[i] == '\\' and not triple:
            i += 2 if not interpolated or source[i + 1:i + 2] in ('"', '\\') else 1
        else:
            i += 1
    return n


def expression_end(source: str, i: int) -> int:
    """From just after `${`: the index after its closing brace, with literals and comments inside it skipped."""
    depth, n = 0, len(source)
    while i < n:
        if source.startswith('/*', i):
            i = comment_end(source, i)
        elif source.startswith('//', i):
            end = source.find('\n', i)
            i = n if end < 0 else end
        elif source[i] == '"':
            triple = source.startswith('"""', i)
            interpolated = i > 0 and (source[i - 1].isalnum() or source[i - 1] == '_')
            i = literal_end(source, i + (3 if triple else 1), triple, interpolated)
        elif source[i] == '}' and depth == 0:
            return i + 1
        else:
            depth += {'{': 1, '}': -1}.get(source[i], 0)
            i += 1
    return n


def lex(source: str) -> list[tuple[str, str]]:
    """`source` as code and literal pieces, comments removed (a comment becomes a space, and keeps its line breaks):
    (`code`, text) and (`literal`, the string or character literal as written, quotes included)."""
    out: list[tuple[str, str]] = []
    i, n, start = 0, len(source), 0

    def code_to(j: int) -> None:
        if j > start:
            out.append(('code', source[start:j]))

    while i < n:
        if source.startswith('/*', i):
            end = comment_end(source, i)
            code_to(i)
            out.append(('code', ' ' + '\n' * source.count('\n', i, end)))
            i = start = end
        elif source.startswith('//', i):
            end = source.find('\n', i)
            end = n if end < 0 else end
            code_to(i)
            out.append(('code', ' '))
            i = start = end
        elif source[i] == '"':
            interpolated = i > 0 and (source[i - 1].isalnum() or source[i - 1] == '_')
            triple = source.startswith('"""', i)
            end = literal_end(source, i + (3 if triple else 1), triple, interpolated)
            code_to(i)
            out.append(('literal', source[i:end]))
            i = start = end
        elif source[i] == "'" and i + 2 < n and (source[i + 2] == "'" or source[i + 1] == '\\'):
            end = source.find("'", i + 2)
            end = n if end < 0 else end + 1
            code_to(i)
            out.append(('literal', source[i:end]))
            i = start = end
        else:
            i += 1
    code_to(n)
    return out


def scan(source: str) -> tuple[str, list[str]]:
    """`source` without its comments (string and character literals kept as written, line breaks kept), and the
    contents of its string literals."""
    pieces = lex(source)
    contents = []
    for kind, text in pieces:
        if kind == 'literal' and text.startswith('"'):
            quote = 3 if text.startswith('"""') else 1
            contents.append(text[quote:max(quote, len(text) - quote)])
    return ''.join(text for _, text in pieces), contents


def masked(source: str) -> tuple[str, list[str]]:
    """`source` without its comments, each literal replaced by a placeholder of one line, and the literals in order:
    white space is read outside literals only, and their braces and line breaks do not count as code."""
    texts: list[str] = []
    out = []
    for kind, text in lex(source):
        if kind == 'literal':
            out.append(f'\0{len(texts)}\0')
            texts.append(text)
        else:
            out.append(text)
    return ''.join(out), texts


def normalized(code: str) -> str:
    """Code without trailing white space and empty lines: indentation is kept."""
    return '\n'.join(line.rstrip() for line in code.splitlines() if line.strip())


def digest_of_masked(code: str, texts: list[str]) -> str:
    """The digest of masked code: normalized outside its literals, which are put back as written."""
    plain = re.sub('\0(\\d+)\0', lambda m: texts[int(m.group(1))], normalized(code))
    return hashlib.sha256(plain.encode()).hexdigest()


def code_digest(source: str) -> str:
    return digest_of_masked(*masked(source))


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


def indent(line: str) -> int:
    return len(line) - len(line.lstrip())


def depth_change(line: str) -> int:
    return sum({'(': 1, '[': 1, '{': 1, ')': -1, ']': -1, '}': -1}.get(c, 0) for c in line)


def member(code: str, scope: str | None, header: str, what: str) -> str:
    """In masked code, the definition whose header line matches `header` (once, within `scope`'s definition when one
    is given): the header line, and every line after it while a bracket it opened is still open or the line is empty
    or indented further, so that a signature over several lines and a body in braces are whole."""
    if scope is not None:
        code = member(code, None, scope, what)
    lines = code.splitlines()
    heads = [i for i, line in enumerate(lines) if re.match(r'\s*' + header, line)]
    if len(heads) != 1:
        raise SurfaceError(f'{what}: {len(heads)} definitions where one was expected')
    start, depth = heads[0], depth_change(lines[heads[0]])
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if depth <= 0 and lines[i].strip() and indent(lines[i]) <= indent(lines[start]):
            end = i
            break
        depth += depth_change(lines[i])
    return '\n'.join(lines[start:end])


# ------------------------------------------------------------------ the surface

def registry(root: Path) -> dict:
    source = read(root, REGISTRY)
    code, _ = scan(source)
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
        'code_sha256': code_digest(source),
    }


def scala_main_sources(root: Path) -> list[str]:
    return sorted(p.relative_to(root).as_posix() for p in root.glob('*/src/main/**/*.scala')
                  if '/src_managed/' not in p.as_posix())


def mentioning(root: Path, raw: re.Pattern[str]) -> list[tuple[str, str]]:
    """The main sources whose raw text matches `raw`, with their text: a cheap filter before the lexer, which only
    drops what cannot match after it."""
    return [(p, t) for p in scala_main_sources(root) for t in (read(root, p),) if raw.search(t)]


def magics(root: Path) -> dict:
    found = {}
    for path, text in mentioning(root, MAGIC):
        _, literals = scan(text)
        names = sorted({lit for lit in literals if len(lit) == 8 and MAGIC.fullmatch(lit)})
        if names:
            found[path] = names
    return required(found, 'the format magics in src/main')


def snapshot_readers(root: Path) -> dict[str, Path]:
    return {f'V{m.group(1)}': p for p in sorted((root / STORAGE_MAIN).glob('SnapshotV*.scala'))
            for m in (SNAPSHOT_READER.fullmatch(p.name),) if m}


def migrations(root: Path) -> dict:
    code, _ = scan(read(root, MIGRATION))
    converted = block(code, r'def converted\b', r'\n\s*(?:def |final class |object |val )', 'SnapshotMigration.converted')
    cases = list(re.finditer(r'case\s+FormatVersion\s*\.\s*(V\d+)\s*=>', converted))
    if len(cases) != len(re.findall(r'\bcase\b', converted)) - len(re.findall(r'case\s+_\s*=>', converted)):
        raise SurfaceError('SnapshotMigration.converted has a case this guard cannot read')
    chains = {}
    for at, match in enumerate(required(cases, 'the converted cases')):
        rest = converted[match.end():cases[at + 1].start() if at + 1 < len(cases) else None]
        rest = re.split(r'\bcase\s+_\s*=>', rest)[0]
        chains[match.group(1)] = [f'{o}.{f}' for o, f in re.findall(r'(SnapshotV\d+)\s*\.\s*(fromV\d+)', rest)]
    steps = required(re.findall(r'case\s*\(\s*FormatVersion\s*\.\s*(V\d+)\s*,\s*FormatVersion\s*\.\s*(V\d+)\s*\)',
                                code), 'the step pairs')
    conversions = {}
    for version, path in snapshot_readers(root).items():
        body, _ = scan(path.read_text(encoding='utf-8'))
        conversions[path.stem] = sorted(set(re.findall(r'\bdef (fromV\d+)\b', body)))
    implementers = sorted(p for p, t in mentioning(root, re.compile(r'\bMigration\s*\['))
                          if re.search(r'\bnew\s+Migration\s*\[|\bextends\b[^{\n]*\bMigration\s*\[', scan(t)[0]))
    return {
        'converted': chains,
        'steps': [list(s) for s in steps],
        'conversions': required(conversions, 'the SnapshotV<n> readers'),
        'implementers': required(implementers, 'the Migration implementations'),
    }


def migration_problems(reg: dict, found: dict) -> list[str]:
    """Where the registered snapshot migration disagrees with the registry: the readable versions are the snapshot
    history from `readableFrom` on, and each but the current one has its step to the next, defined by the next
    version's reader, and a `converted` chain of the steps to the current one."""
    snapshot = reg['kinds'].get('SnapshotFile')
    if snapshot is None:
        return ['the SnapshotFile kind is gone']
    history = [v for v, _ in snapshot['history']]
    oldest = reg['readableFrom'].get('SnapshotFile', 'FormatVersion.' + history[0]).removeprefix('FormatVersion.')
    if oldest not in history:
        return [f'readableFrom {oldest} is not a snapshot version']
    readable = history[history.index(oldest):]
    pairs = list(zip(readable, readable[1:]))
    out = []
    if sorted(map(tuple, found['steps'])) != sorted(pairs):
        out.append(f'the step pairs {found["steps"]} are not the consecutive readable versions {pairs}')
    for at, (before, after) in enumerate(pairs):
        conversion = f'fromV{before[1:]}'
        if conversion not in found['conversions'].get(f'Snapshot{after}', []):
            out.append(f'Snapshot{after} does not define {conversion}')
        expected = [f'Snapshot{b}.fromV{a[1:]}' for a, b in pairs[at:]]
        if found['converted'].get(before) != expected:
            out.append(f'converted {before} is {found["converted"].get(before)}, not the steps {expected}')
    extra = sorted(set(found['converted']) - {a for a, _ in pairs})
    if extra:
        out.append(f'converted has cases for {extra}, which are not readable versions before the current one')
    return out


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


def format_sources(root: Path) -> dict:
    paths = [*FORMAT_SOURCES, *HASKELL_STATE_SOURCES,
             *(p.relative_to(root).as_posix() for p in snapshot_readers(root).values())]
    return {path: code_digest(read(root, path)) for path in sorted(set(paths))}


def format_members(root: Path) -> dict:
    out = {}
    for name, path, scope, header in FORMAT_MEMBERS:
        code, texts = masked(read(root, path))
        out[f'{path}#{name}'] = digest_of_masked(member(code, scope, header, f'{path}#{name}'), texts)
    return out


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
        'format_sources': format_sources(root),
        'format_members': format_members(root),
        'frozen_schemas': digests_of(root, (STORAGE_MAIN,), FROZEN_SCHEMA, 'the frozen schemas'),
        'goldens': goldens(root),
        'previous_version_suites': digests_of(root, STORAGE_TESTS, PREVIOUS_VERSION_SUITE,
                                              'the previous-version suites'),
        'fixtures': fixtures(root),
    }


def digest(found: dict) -> str:
    return hashlib.sha256(json.dumps(found, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def surface_paths(root: Path = ROOT) -> list[str]:
    """Every file the surface reads, for the check that the allowlist covers them."""
    found = surface(root)
    return sorted({REGISTRY, *found['magics'], *found['format_sources'], *found['frozen_schemas'],
                   *found['goldens'], *found['previous_version_suites'], *found['fixtures'],
                   *(path for _, path, _, _ in FORMAT_MEMBERS), *found['migrations']['implementers']})


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
                      f'own, and {MANIFEST} records the question and the decision with their UTC times.'])


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
    inconsistent = migration_problems(found['registry'], found['migrations'])
    if inconsistent:
        out.append(loud(['the registered snapshot migration disagrees with the format registry:',
                         *(f'  {p}' for p in inconsistent)]))
    if not isinstance(manifest, dict) or not isinstance(manifest.get('surface'), dict):
        return out + [loud([f'{MANIFEST} is missing, is not JSON, or has no surface'])]
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


def load_manifest(root: Path):
    """The manifest, or None when it is missing or is not JSON (`problems` reports either)."""
    file = root / MANIFEST
    if not file.is_file():
        return None
    try:
        return json.loads(file.read_text(encoding='utf-8'))
    except (ValueError, UnicodeDecodeError):
        return None


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--print-surface', action='store_true', help='print the surface and its digest as JSON')
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args(argv)
    if args.print_surface:
        try:
            found = surface(args.root)
        except SurfaceError as error:
            print(loud([f'the persisted-format surface cannot be read: {error}']), file=sys.stderr)
            return 1
        print(json.dumps({'surface': found, 'surface_sha256': digest(found)}, indent=1, sort_keys=True))
        return 0
    found = problems(args.root, load_manifest(args.root), datetime.now(timezone.utc))
    for report in found:
        print(report, file=sys.stderr)
    if not found:
        print('storage format: unchanged from docs/data/storage-format.json')
    return 1 if found else 0


if __name__ == '__main__':
    sys.exit(main())
