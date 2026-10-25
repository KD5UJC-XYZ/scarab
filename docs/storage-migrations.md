# Storage migrations

**Status: Current.** This is the contributor procedure for changing a persisted format and the
operator procedure for preserving a usable database through that change. The decision is
[ADR 0006](adr/0006-versioned-formats-migrate-on-open.md); the current layout is described in
[architecture.md](architecture.md). Commands for following a chain belong in
[running.md](running.md).

## Register the change before changing the writer

[PersistedFormat.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PersistedFormat.scala) owns `FormatVersion` and `FileKind`. Append a version, never renumber
one. Add a file kind when its identity and migration are independent; otherwise append the changed
kind's `(version, identifier)` entry to `history`. An unchanged kind keeps its last layout and is not
rewritten merely because another kind changed. `identifier`, `current` and `versionOf` are the
registry, rather than independent magic strings scattered through callers.

Freeze a complete previous-version file before editing its writer or schema. Keep its checksum,
header, schema fingerprint, source revision, file hash and the command that actually wrote or
captured it. Distinguish a captured original from a reproduction by a pinned historical writer. A
reproduction proves that writer's format; it does not establish an unknown capture's writer revision.
Keep small public fixtures in Git. Large captures, checkpoint directories and logs stay outside the
checkout with a hashed manifest and reproducible provenance.

The snapshot fingerprint golden in `CodecSuite` must change together with the version, migration and
previous-version regression. Regenerating a fingerprint to make a test pass is not a migration.
Native snapshots are V7: the V5 body (Haskell ledger CBOR plus a typed binary sidecar) behind a
checkpoint binding, with framing and checksum; stored values remain CBOR. The binding has its own
fingerprint golden in `CodecSuite`, next to the sidecar's. MemPack is an import/export format, not a replacement stored value.

## Convert typed values without inventing state

Keep the old reader frozen while its migration is supported, including its transitive persisted
types and codec instances; importing evolving live types can silently change the old schema. Decode
its own schema into its own closed types, then perform a pure typed conversion to the new types.
Use closed error ADTs and cats-mtl capabilities at file boundaries. Preserve anchor, era, protocol parameters, UTxO association,
and all semantic state the old file actually held. Hashes and signatures continue to use original
transaction/block bytes; re-encoding diagnostics or a container format does not replace those bytes.

For ledger snapshots, `FileLedgerSnapshots.verified` checks the file checksum before a payload is
used. `SnapshotV7.format[F](SnapshotCheck.Full).decode(content)` reads current content, and
`SnapshotV7.sidecarOf` and `SnapshotV7.bindingOf` expose carried metadata. `SnapshotV5` is the
frozen previous reader: its body parser is shared, and `SnapshotV7.fromV5` is the migration step. The `Full` choice controls encode-time read-back
checking; decoding itself validates the current file in either mode.
A converter must carry the source `observed` eras and `gaps` exactly. Missing non-myopic data must
remain missing; the caller's newer environment is not evidence that the old file observed an era.
A derivation is allowed only when the old state supplies every input and a test proves the derivation.
Record any unavoidable loss explicitly rather than filling it with an apparently complete default.

Test semantic invariants as well as fixed bytes. Include a control that fails when a carried gap is
dropped or missing state is invented. For snapshots backed by RocksDB, verify the checkpoint at the
same anchor, its key layout and its association with the snapshot's UTxO handle. A file checksum or
a header inventory alone does not prove ledger/checkpoint agreement. Distinguish exhaustive file
inventory from representative complete-state restoration in the evidence.

## Register atomic migration and refuse incompatible input first

[Migrate.scala](../storage/src/main/scala/xyz/kd5ujc/scarab/storage/Migrate.scala) defines the generic `Migration[F]` (`kind`, `detect`, `step`) and `Migrate`.
A step writes the exact next changed version of its file kind (`nextVersion`), with the explicit
`step(file, from, to)` contract. Shared versions that did not change that kind require no rewrite. Snapshot
migration uses `SnapshotMigration[F]` and its report while retaining that generic structure. Add the
new conversion to the registered migration, rather than a startup-only special case that another
caller can bypass. Enforce version support in direct `detect`, `step` and `run` entry points too.

`AtomicFile.replace` / `replaceAll` write a sibling temporary file, force its data, atomically rename
it and force the directory. A temporary file left by interruption is not a candidate snapshot. Each
step must leave either a complete old file or a complete new file. A restart discovers each file's
actual version and resumes; a second successful migration changes no bytes and reports no work.
Do not replace this with delete-then-write or an in-place payload edit.

Global V6 is the complete network identity and global V7 the snapshot's checkpoint binding
(`SCRBSNP7`, #395). V5 stays readable while that migration is supported. `Migration.detect`
identifies either registered header; `step(file, V5, V7)` validates that exact transition, verifies
the old checksum, checks the V5 sidecar against its fingerprint, copies the V5 body unchanged behind
a `Legacy` binding (no checkpoint digest is invented for a file that recorded none) and atomically
writes V7. `Migrate` then has no work for V7. The identity kind remains at V6 and unchanged kinds
retain their own current versions. A future snapshot change appends global `V8`. Retire the V5
reader only after the inventory and conversion conditions below hold, then advance the snapshot
kind's `readableFrom` to V7. Use caller-scoped `Raise[F, StorageError]` for every migration operation;
do not capture an earlier caller's error interpreter in a long-lived migration object.

Acquire `DatabaseOwnership` before inspecting a database that could have another writer. Its lock
bookkeeping is separate from format mutation. `ChainStorage.preflight` first checks the complete
network identity read-only, then supported snapshot headers and any immutable format/index checks
required for identity adoption. `Main` runs this before acquiring RocksDB backing;
`ChainStorage.open` also runs it before identity admission/migration, immutable/volatile recovery or
backing reset. Preserve network mismatch precedence when several files are wrong. Complete identity
binds the protocol magic and exact Byron, Shelley, Alonzo and Conway genesis inputs. Incomplete or
missing identities require explicit adoption under #159, with original-byte backup and the existing
first-block/predecessor checks; reader retirement must not weaken those admission requirements.

Known retired versions raise `StorageError.RetiredVersion`. A recognized family with an unknown
identifier raises `UnknownVersion`. Both refuse admission without falling back to replay or rewriting
another file. The generic `FileLedgerSnapshots.restore` also propagates these typed incompatibility
errors rather than skipping them. Its `open` tidies temporary files, so direct callers that need the
whole-database no-mutation guarantee must run `ChainStorage.preflight` before opening it. A corrupt current-version snapshot remains subject to the existing recovery policy:
it can be skipped for an older usable snapshot or replay. Do not classify an incompatible but known
version as ordinary corrupt input to obtain that fallback.

## Worked example: snapshot V5 to V7

The checkpoint binding (#395, [ADR 0021](adr/0021-ledger-state-residency-and-utxo-checkpoints.md))
went through every step above. Read its code and tests before a new snapshot version:

| Step | V5 to V7 |
|---|---|
| Register | `FormatVersion.V7` appended; `FileKind.SnapshotFile.history` gained `V7 -> "SCRBSNP7"`; the identity kind stayed at V6 and the other kinds at their own versions |
| Freeze the previous reader | `SnapshotV5` kept as the reader of the previous version; its body parser is shared with `SnapshotV7` |
| Convert without inventing state | `SnapshotV7.fromV5` copies the V5 body byte for byte behind `CheckpointBinding.Legacy`, because a V5 file recorded no checkpoint digest |
| Register the migration | `SnapshotMigration.toCurrent`, run through `Migrate`; its step writes with `AtomicFile.replaceAll` |
| Refuse first | `ChainStorage.preflight` runs `SnapshotMigration.preflight` before mutable stores are acquired |
| Goldens | the binding fingerprint and encodings next to the sidecar's in `CodecSuite` |
| Previous-version evidence | `fixtures/snapshot-v5` kept as the source; `fixtures/snapshot-v7` is its registered conversion, re-derived byte for byte by `SnapshotGoldenSuite` |
| Refusal and transition tests | `SnapshotPreflightSuite` (retired and unknown versions refused before recovery, backing reset or a direct migration step), `FormatMigrationSuite` (version transitions, interruption, unknown versions) |

A RocksDB layout change goes through the same steps plus the checkpoint rules of the next section.
The change is level 3 ([CONTRIBUTING](../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change));
the area scenario is `restart`.

## RocksDB layouts and checkpoints

RocksDB key/value and column-family layouts are not `FileKind` values: they are admitted through the
snapshot that records them ([ADR 0021](adr/0021-ledger-state-residency-and-utxo-checkpoints.md),
amendment of ADR 0006). From V7 a snapshot's `CheckpointBinding` records the `UtxoKeyLayout` and the
digest of its checkpoint directory (`CheckpointDigest`: data files by name and size, small files by
content, RocksDB's `LOG*` and `LOCK` excluded), and `StateBacking.admit` refuses a checkpoint whose
layout or digest differs, with `StorageError.UtxoCheckpoint`; the snapshot is skipped as any unusable
one is. The live store is never admitted by itself: each open replaces it by an admitted checkpoint
or empties it. A new key, value or column-family layout is a new `UtxoKeyLayout` case and a snapshot
version bump, with a conversion or refusal of the existing checkpoints, coordinated with every
database/checkpoint consumer (`LedgerSnapshotExport` and `SclsBase` admit checkpoints the same way).
`UtxoBacking.retain` deletes only unlisted directories named as checkpoint stems.

The existing `RocksUtxoStore.hasCurrentKeys` / `rekeyInto` path recognizes the historical 36-byte
`txid | index-u32` checkpoint keys and restores them as current 34-byte `txid | index-u16` keys.
It preserves the stamp and original output CBOR values. Its full scan rejects `MixedKeyLayouts`,
`IndexTooLarge` and `UnknownKeyLength`; inspecting only the first and last key is not equivalent
proof. This checkpoint conversion remains supported independently of retired snapshot readers.

Work on consistent database/checkpoint copies, with all writers stopped or a supported checkpoint
capture, rather than editing an active database in place. Verify complete restored count/coin,
stamp and the snapshot's diff overlay as well as file integrity and key widths. The checkpoint is
associated with the snapshot anchor by its directory stem and, from V7, by the snapshot's binding;
a `Legacy` (migrated V5) snapshot has only the stem, is restored with a `snapshotUnbound` warning,
and ages out as newer snapshots are written. A converted checkpoint (`rekeyInto`) is a new directory
whose digest a later snapshot records. Its last flushed change stamp can
legitimately be behind that anchor. Compare the checkpoint's flushed stamp with the saved handle and
apply the carried diff before comparing complete count/coin; do not require every checkpoint stamp
to equal the block number or compare the raw backing set alone. Test mixed/invalid
layouts, an interrupted restore and restart. The backup must include a matching snapshot and its
checkpoint; a standalone converted snapshot is not proof of a usable restored UTxO set.

## Verify and record the result

Run builds only in the JDK image, with the command and cache mount of
[CONTRIBUTING](../CONTRIBUTING.md#build-docker-only) (`scripts/sbtw check node/runtimeClasspathFile`).

Use [CONTRIBUTING's gates](../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) for the actual touched paths,
including the storage restart scenario when applicable. Test previous-version bytes, semantic/gap
preservation, current round trips, idempotence, mixed-version directories, interruption/restart,
unknown and retired versions, and network-mismatch precedence. Retain byte-for-byte assertions for
identity files, torn immutable/volatile files and backing state after preflight refusal. Test the
real startup path too, so a late check cannot hide an earlier RocksDB open.

Record actual process exit status, exact source/runtime binding, commands, fixture and image pins,
what was compared, and any limits in [compatibility.md](compatibility.md). Update the matching design
stage, roadmap item and changelog fragment with the substantive implementation commit. A started
process, partial output or a timed-out assertion is not a passed gate.

## Per-state provenance upgrades (#129)

Native V5 already has `gaps` and `observed` sidecar fields. The live mirror now carries those
fields explicitly, restores the saved values, and writes them back from the selected state instead
of using a constant gap set or the writer's newer environment. This is a writer behavior correction;
the sidecar schema and fingerprint remain V5. A fresh native genesis and a complete native Haskell
import start complete. An existing snapshot keeps every saved gap, even when the historical writer
used an overly conservative flag. Empty likelihood history alone cannot establish its provenance.

Missing non-myopic history does not heal merely because a new reward update is computed. The old
reward-attribution, pending-update and Shelley-transition gaps can clear at a successful epoch
boundary that replaces their affected state. Delegator-count provenance requires three observed
native snapshot rotations to replace arbitrary imported mark/set/go snapshots. Its rotation proof
is transient: a restore retains the gap and restarts the proof, so repeated restarts can delay its
clearance. It never infers that a missing rotation happened from elapsed epoch numbers alone.

The preprod and mainnet writer upgrades remain separate operational work under
[#129](https://github.com/KD5UJC-XYZ/scarab/issues/129). Before either upgrade:

1. Inventory that network's actual writer revision, complete identity, file versions, saved gaps,
   era observations, snapshot anchors and matching checkpoint handles. Do not assume V5 from the
   format used by repository fixtures; the older preprod producer has a separate #37/#115 transition.
2. Prepare a consistent complete backup and prove that its pinned old runtime can restore it.
   Rehearse on separate copies under the procedure above; never open the operational original
   with the candidate runtime or share writable backing/checkpoint inodes with it.
3. With the exact proposed runtime, verify snapshot/checkpoint restoration, complete count/coin
   including the diff overlay, fixed-range continuation and a second restart. Preserve source
   gaps and era observations through rewriting; require unchanged V5 framing/fingerprint for
   V5 input. Retired input needs its established pinned-source conversion first.
4. Present the per-network manifests, results and rollback plan for owner approval before stopping
   or switching its writer. Upgrade one network at a time. Do not infer downgrade safety from
   unchanged framing: an older writer can read V5 yet drop provenance when it rewrites the state.

Source validation and private devnet restarts do not establish that either operational network has
been migrated. Record each network's completed rehearsal and approved cutover explicitly before
closing its rollout acceptance.

## Inventory, backups and reader retirement

Before opening a new build against existing data, inventory every active database, snapshot corpus,
checkpoint, optional test input and conversion tool. Include writers that may still emit an older
format. Separate active inputs from immutable historical evidence and archives. Preserve originals
and their hashes; create current-format copies in new paths and verify them before retargeting tools
or changing the active database. Preserve hard links within a copied checkpoint corpus without
sharing target inodes with the originals if the copies will be opened or modified.

Migrations are one way. Keep a complete compatible backup for a downgrade, including snapshots and
checkpoints at matching anchors, identity, immutable and volatile data. Do not expect an old binary
to read a new file or mix arbitrary old checkpoints with new snapshots. Any operator transition of a
running writer is an explicit operation; producing verified copies does not update that writer.

For this greenfield repository, an old payload reader can leave the development runtime once all
local active inputs and repository test/tool dependencies have verified current-format replacements,
local writers no longer emit that format, and useful historical evidence is recoverable from pinned
Git source or an archived one-shot bridge. There is no speculative release support horizon. Keep
historical identifiers in the registry; a future reader or migration is added deliberately when a
new format is introduced.

Source landing and deployment are separate. An existing deployment may keep its pinned older
binary and compatible database; reader retirement does not upgrade that writer or its data. Before
using the new binary there, capture a consistent full rollback backup, bridge a separate copy of its
final old snapshots with their matching checkpoints, and verify restoration with the exact new
runtime. The existing preprod producer's V4-to-V5 rollout remains an explicit operational step under
[#37](https://github.com/KD5UJC-XYZ/scarab/issues/37) /
[#115](https://github.com/KD5UJC-XYZ/scarab/issues/115). Do not deploy a V5-only reader against its
unconverted V4 database, stop that producer, or access signing keys as part of the source change.

A one-shot bridge must name its exact old source/compiler/schema, refuse unexpected fingerprints,
keep original inputs read-only, write separate outputs and verify them with the current reader.
Archive its recipe and conversion manifest. There is no general shipped snapshot-bridge CLI: do not
present a private conversion harness as an installed command. Retiring snapshot readers does not
by itself remove legacy domain code still used by genesis conversion (#134) or the independent
RocksDB checkpoint-key migration.
