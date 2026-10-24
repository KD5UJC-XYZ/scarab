<!-- SPDX-License-Identifier: Apache-2.0 -->
# Databases, snapshots and imports

Part of [Running the node and the devnet scenarios](../running.md).

Caveats:

- A persistent Scarab writer owns a stable sibling file `.<database name>.scarab-lock` in the
  database's parent. The process needs write access to that parent or to the existing lease file.
  A container running as a non-root user with only `-v <host database>:/db` must also bind the same
  host lease inode at `/.db.scarab-lock`; do not create an unrelated container-local lease. For example:

  ```sh
  DB=/absolute/path/to/db
  LEASE="$(dirname "$DB")/.$(basename "$DB").scarab-lock"
  touch "$LEASE"
  docker run --rm --user "$(id -u):$(id -g)" \
    -v "$DB":/db -v "$LEASE":/.db.scarab-lock ... follow --db /db ...
  ```

  Mounting a writable parent instead (for example `/data` with `--db /data/db`) also works. Keep the
  lease file in place after the process exits: unlinking it can split ownership between two inodes.
  This coordinates new Scarab followers and import commands, not older Scarab or Haskell writers.

- A `--db` records the network it was created for (`<db>/network-identity`). Format V6 binds the
  protocol magic, Byron and Shelley genesis hashes, and BLAKE2b-256 of the exact loaded Alonzo and
  Conway genesis bytes. A mismatch refuses to open with `NetworkMismatch`. V1/V2 identity records
  lack the Alonzo and Conway bindings and are never upgraded automatically. A populated legacy
  identity or database without an identity requires `--adopt-legacy-network-identity`; the original
  legacy identity bytes are retained beside the new file, and the recorded operator confirmation is
  not historical proof. Before adoption, the node checks every known identity field, the first
  immutable block when available, all snapshot headers, and immutable format/index headers. Any
  refusal happens before RocksDB, recovery or format migration. A pre-identity Shelley-start database
  cannot be historically distinguished from another network with the same first-block predecessor.
  The devnet rewrites its genesis at every cluster start, so a `--db` of an earlier cluster is refused too.
- A database written before the rename (format 1: `cardano-scala-lab ...` identifiers, index magic
  `CSLIDX01`) is migrated to format 2 the first time it opens: every chunk index and the
  `format` file are rewritten one by one (temporary file, fsync, rename), `formatMigrated` events say what,
  and the second open does nothing. A crash in the middle is harmless, the next open finishes. The
  migration is one way and an older build cannot open the result: preserve a consistent database copy
  before first open if rollback matters. The identity file is separately backed up during explicit
  legacy adoption.
  A database of a format newer than the build refuses to open (`UnknownVersion`) and nothing is rewritten.
- Native ledger snapshots remain format 5 (`SCRBSNP5`, Haskell ledger CBOR plus a typed sidecar,
  framing and checksum). Snapshot payload formats 1–4 are known retired identifiers, refused with
  `RetiredVersion`; an unknown identifier of the family is `UnknownVersion`. Database ownership,
  read-only identity verification and snapshot preflight happen before opening RocksDB, migrating
  identity or recovering immutable/volatile stores. The old readers and one-shot conversions are
  recoverable from pinned historical source; they are not retained in the runtime. Preserve the
  original database and convert a separate copy with the matching historical build before opening
  it. A pre-idle-pools fingerprint also needs its exact historical schema, not a guessed decoder.
  [Storage migrations](../storage-migrations.md) describes backup, inventory, verification and the
  local input requirements for retirement and the separate deployment transition. An existing older
  producer stays on its compatible pinned binary until its final database copy is converted and
  verified for an explicitly approved upgrade (#37/#115); this source change does not restart it.
  Do not delete snapshots to force replay as an upgrade procedure.
- A format-5 sidecar holds the mirror state without the old rules' flat bookkeeping. Historical
  conversions preserve the source gaps and observed eras rather than claiming information the old
  state lacked. Live states retain these gaps and observed era starts through rollback and V5
  restore/rewrite (#129). Missing likelihood history remains marked; arbitrary imported delegator
  counts need three observed snapshot rotations, with the proof conservatively restarting after
  restore. An old Byron-start snapshot before Allegra can lack the redeem stash. Keep the source
  and matching checkpoint backup. Operational preprod/mainnet upgrades remain separate; see
  [the provenance upgrade procedure](../storage-migrations.md#per-state-provenance-upgrades-129).
- **The UTxO checkpoints beside the snapshots** (`<db>/utxo/checkpoints/<snapshot stem>`) are RocksDB
  directories whose key is `txid ++ index` as a big-endian u16 (34 bytes, the Haskell node's) since K6
  (2026-10-14). Older checkpoints used `txid ++ u32` (36 bytes). The independent checkpoint-key
  converter can restore that layout into `<db>/utxo/live` under current keys without modifying the
  checkpoint; it does not make retired snapshot payloads readable. A usable current-format snapshot
  and its matching checkpoint are still required. A mixed layout, an output index above 65,535 or a
  key of another length is refused with `StorageError.UtxoCheckpoint`.
  Preserve the database and its matching snapshot/checkpoint pairs before investigating a refusal;
  follow [the storage migration procedure](../storage-migrations.md) to convert or recover a separate
  copy. Replaying from immutable chunks is an operator choice, not an upgrade step that requires
  deleting the only recoverable state. Historical #145 involved a bad restore producing mixed key
  layouts; current restoration rejects that condition rather than silently using it.
- The volatile blocks (the last `k`) are kept in `<db>/volatile/` and applied again on start
  (`volatileResumed`), and a graceful stop (SIGTERM, SIGINT, `--seconds`) writes a snapshot at the
  immutable tip (`shutdownSnapshot`): the next start replays no immutable block when that snapshot
  is usable, reapplies the retained volatile log through the rules, and continues at the
  previous tip. After `kill -9` the node resumes from the newest periodic snapshot, replays the stored
  blocks after it, and takes the volatile chain from the log as far as the log reached the disk.
  Run the node so that the signal reaches the JVM (`docker stop`, not a shell wrapper that eats it).
- **When a snapshot is taken** (`SnapshotPolicy`, the Haskell node's default policy: ouroboros-consensus 4.2.0.0,
  `Storage/LedgerDB/Snapshots.hs` `defaultSnapshotPolicy` and `mithrilSnapshotPolicyArgs`, with `NumOfDiskSnapshots 2`):
  when the immutable tip passes a multiple of `40 * k` slots (86,400 on mainnet and Preprod, a day on mainnet) and the
  previous snapshot was taken at least 10 minutes ago, so a replay or a sync far behind the tip writes at most one
  snapshot per 10 minutes (each costs about 50 s of background CPU on mainnet) and a node at the tip writes one a day;
  a crossing that falls inside the 10 minutes is skipped. Two snapshots are kept. `--snapshot-every` replaces the
  policy by "once the tip is this far past the previous snapshot" with no time limit (`--snapshot-every 1epochs` is
  the earlier default), and `--keep-snapshots` the number kept. The snapshot at a graceful stop is independent of
  both. Details and numbers: [performance.md](../performance.md) section 13.

**Checking a database before a node is moved to this build.** First inventory its snapshot versions.
This procedure requires V5 or V7 snapshots and their associated checkpoints (V5 files migrate to V7
on open, #395). V1–V4 inputs
need separate verified current-format replacements using pinned historical source, as described in
[Storage migrations](../storage-migrations.md); this build refuses retired formats before mutable stores
open. Work on a copy; the original is never opened by the new build. With the node stopped (a running
node's last chunk and volatile files are moving), `D` the database and `C` the copy, on the same file
system as `D`:

```sh
mkdir -p "$C/volatile"
cp -a "$D/network-identity" "$D/snapshots" "$D/utxo" "$C/"     # snapshots and every checkpoint
cp -al "$D/immutable" "$C/immutable"                           # hard links: the chunks cost no space
# the build rewrites the index files and may extend the newest chunk: those must be real copies
newest=$(ls "$D/immutable" | grep chunk | sort | tail -n 2 | sed 's/\.chunk//')
for c in $newest; do rm "$C/immutable/$c".{chunk,index}; cp "$D/immutable/$c".{chunk,index} "$C/immutable/"; done
rm "$C/immutable/format"; cp "$D/immutable/format" "$C/immutable/format"
```

The space needed is that of `du -sh "$D/snapshots" "$D/utxo"` (`cp -a` keeps the hard links between the
checkpoints' files, so it is what `du` counts once) plus, during the run, a snapshot and a checkpoint's new
files (the live store is linked from the checkpoint, and the first snapshot written adds a file the size of
one snapshot). Then run the build to be checked on the copy with the flags of the node, in a container the
way the node runs (the replay takes place before any peer is contacted, so a peer that is never reached
does not matter), and stop it when enough blocks have gone through:

```sh
LEASE="$(dirname "$C")/.$(basename "$C").scarab-lock"
touch "$LEASE"
docker run -d --name check-old-db -v "$C":/db -v "$LEASE":/.db.scarab-lock ... \
  follow --genesis-dir /config --peer <relay> --db /db --keep-snapshots 2000 > node.log
until [ "$(grep -c '"event":"replayProgress"' node.log)" -ge 5 ] || grep -q '"event":"error"' node.log; do sleep 10; done
docker stop -t 120 check-old-db                                 # a graceful stop writes a snapshot
grep -E '"event":"(formatMigrated|snapshotRestored|snapshotUnbound|replayStarted|replayProgress|snapshotRefused|snapshotSkipped|error|shutdownSnapshot)"' node.log | cut -c1-300
```

What shows the current-format database opens: `snapshotRestored` with the `anchor` of the newest
snapshot; `replayStarted` from that anchor; `replayProgress` lines whose `tip` moves past it, with
`replayed` growing by the progress interval each time and no `"event":"error"` (a `ValidationError ... BadInputsUTxO` right after the restore is this
bug); no `snapshotRefused`, no `snapshotSkipped`, no `UtxoCheckpoint` error; for a V5 database, one
`formatMigrated` from `V5` to `V7` and a `snapshotUnbound` for the restored file (it was written
before checkpoint binding); and, after the stop, a
`snapshotWritten`/`shutdownSnapshot` at a block past the restored anchor. A replay from the newest
snapshot of a mainnet follower that is a day behind covers some thousands of blocks, so
`replayFinished` appears; for a database whose newest snapshot is older, five progress lines (the
`replayed` counts) are enough. Remove the copy afterwards.

Historical builds that supported V4→V5 migration also emitted `formatMigrated` with
`"kind":"SnapshotFile"`, `"from":"V4"`, `"to":"V5"` and the number of migrated files. That is
historical conversion evidence, not an automatic upgrade supported by this build.

## `import-haskell-db`: start from a Haskell node's blocks

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main import-haskell-db \
  --haskell-db <haskell db dir> (--genesis-dir <dir> | --config <config.json>) --db <dir> [--stop-slot N]
```

Reads `<haskell db dir>/immutable` (a Haskell node's ImmutableDB; never written, so a running
node's directory may be given, but copy it if you can) and appends its blocks to the immutable store
of `--db`. A following `follow --db <dir>` replays them locally instead of downloading them, then
intersects the peer at the imported tip. The command refuses a database whose `protocolMagicId` is not
the configured network's, a first block that does not name the configured genesis, blocks that do
not link or whose header hash or slot differ from the index, and a corrupt chunk (a typed error naming
the chunk and the fault); the newest chunk, which the node may be writing, is cut back to its valid
blocks and the number ignored is printed. `--stop-slot N` stops after the last block at or before slot
`N`. A second run continues after the blocks already stored. It prints JSON lines
(`importHaskellDb`: `progress`, `done` or `error`). The Haskell node's ledger snapshots are not read:
the ledger state is rebuilt by the replay.

Measured (2026-10-15, 3 CPUs, 200 Preview chunks copied from the Haskell node's database): 40,357
blocks (slots 0 to 863,996, 46 MB) imported in 2 s; `follow` then replayed them in 42.7 s, connected
with the intersection at the imported tip (slot 863,996), and went on to slot 3.33 M (epoch 38) in
the following 6.5 minutes.

## `import-mithril`: certified bootstrap into a fresh database

Build the reviewed Linux amd64 verifier with Docker (an existing clean pinned upstream clone can
be supplied with `--source`; otherwise the script fetches it):

```sh
python3 scripts/prepare_mithril_client.py --out /tmp/scarab/mithril-client
```

The script verifies source, Cargo lockfile, immutable Docker image, integration patch and resulting
executable digest. Mount that executable, configuration and two trusted verification-key files into
the JDK container. Mount the destination's parent writable so its sibling ownership lease and staging
directory share the same filesystem. Then run inside the container:

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main import-mithril \
  --artifact <exact lowercase artifact hash> --aggregator <https aggregator URL> \
  --genesis-verification-key <trusted genesis.vkey> \
  --ancillary-verification-key <trusted ancillary.vkey> \
  --mithril-client <pinned executable> --db <fresh database> --config <config.json>
```

`--genesis-dir <directory>` can replace `--config`. The destination must not exist. The default is
an official full download including ancillary files; `--source-db <Haskell database>` instead copies
an offline cache into private staging and runs full certification verification there. Metadata and
certificate retrieval still use the configured aggregator. An isolated loopback HTTP aggregator is
accepted for recorded offline fixtures; public endpoints require HTTPS. No implicit `latest`, range,
overwrite or missing-file acceptance is available. `--timeout-seconds N` bounds the verifier process
(default 1,800 seconds).

Certification and ancillary trust are separate: the genesis key verifies the Mithril certificate
chain and immutable proof; the ancillary key authenticates the ledger snapshot and unfinished trio.
This command requires both. The generic snapshot command below retains the explicit unauthenticated
opt-out. The bootstrap records these distinctions in `mithril-bootstrap.json` and refuses certificate
network or artifact substitution before ledger import. Byron and non-Mem snapshots are refused.

Successful JSON `importMithril` output names the artifact and ledger anchor. A subsequent ordinary
`follow --db <database>` restores its checkpoint and replays recorded or newly received blocks.
Failure leaves staging subject to best-effort cleanup and never overwrites an existing database;
a failed parent sync with denied rollback may retain the complete owned destination. Inspect the
reported error before retrying. [The design](../design/mithril-bootstrap.md) records exact proof scope,
executable provenance, typed refusals and acceptance evidence.

## `import-ledger-snapshot`: start from a reference ledger snapshot

Run offline in the JDK container, with the extracted ancillary archive and configuration mounted
read-only, and the destination database and its sibling ownership lease mounted writable (as above):

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main import-ledger-snapshot \
  --haskell-ledger <archive>/ledger/<slot> --db <database> --config <config.json> \
  --ancillary-verification-key <trusted-ancillary.vkey>
```

First import the immutable blocks with `import-haskell-db` (above). The destination must contain the
configured network identity and the exact snapshot point in its current-format immutable chunks,
and no existing `utxo/`, `snapshots/`, `volatile/` or `ledger/` artifacts. Copy a database if its node
is running. The new follower and both import commands share an ownership lease; older Scarab builds
and Haskell nodes do not participate, so keep their inputs offline too.

Verification is required by default. Supply the network's trusted Mithril ancillary verification key
in its native hex-encoded JSON format. The default manifest is `<archive>/ancillary_manifest.json`;
`--ancillary-manifest <file>` overrides its location. All listed files, including the unfinished
immutable chunk trio, must be extracted and match their digests. `--no-verify-ancillary` explicitly
accepts an unauthenticated ledger instead; the result records `ancillaryVerified: false`.

The command checks metadata, era, exact immutable block and recomputed instant stake, builds a fresh
RocksDB checkpoint and a V7 snapshot bound to it in staging, and publishes only after validation. It never changes
existing immutable files or overwrites ledger artifacts. JSON `importLedgerSnapshot` events report
`done` with slot/hash, era, output count, lovelace and trust, or a typed `error`. A following `follow`
restores that checkpoint and replays local blocks after the snapshot. Byron and non-Mem snapshots
are refused. Import/export details and recovery of an interrupted staged import are in
[the accepted design](../design/haskell-snapshots.md#10-stage-3-as-built-offline-import); export and
cross-node acceptance are recorded in Step D S4/S5 (#140).

## `export-ledger-snapshot`: write a reference ledger directory

Run offline in the JDK container, with the source database readable and the output directory
writable. The source's sibling ownership lease must be writable too (see the Docker lease
example above); source database files can remain read-only.

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main export-ledger-snapshot \
  --db <database> --out <ledger directory> [--slot N]
```

The default is the newest snapshot; `--slot` selects an exact stored snapshot slot, an unsigned
64-bit number. The command checks the existing V7 file's checksum and schema, preserves its
Haskell state bytes, admits its checkpoint against the snapshot's binding and reads it without
recovery or migration. A state
with recorded gaps, a Byron state, an unsettled checkpoint or a missing selection is refused.
This means an imported reference state can be exported; a state whose recorded gaps have not
been resolved cannot be exported by inventing those fields.

The fresh directory `<ledger directory>/<slot>_scarab` contains `state`, `tables` and `meta`.
The suffix prevents the Haskell node's temporary-snapshot policy from deleting it. The command
streams sorted MemPack outputs, closes and forces staged files, then publishes the directory
atomically. An existing destination is refused, and the output must be outside the source
database. Failure or cancellation removes staging. JSON `exportLedgerSnapshot` events report
`done` with the point, output count and directory, or a typed `error`.

Reference replay and epoch-boundary acceptance are recorded in #140; the command alone does not prove
that acceptance. It preserves the native snapshot and the node's stored CBOR values.

## `export-scls` and `import-scls`: canonical ledger-state interchange

Run these offline in the JDK container, as with the Haskell snapshot tools above. Both require a
complete Conway native snapshot/checkpoint, external writable scratch, the exact block reference,
and local configuration/genesis inputs for the complete network identity. The supported pinned
SCLS profile and lossy fields are described in [the design](../design/scls-interchange.md).

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main export-scls \
  --db <complete base DB> --scratch <external scratch dir> --genesis-dir <genesis dir> \
  --slot <slot> --block-no <block number> --block-hash <64 hex digits> --out <new file>
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main import-scls \
  --db <complete base DB> --scratch <external scratch dir> --genesis-dir <genesis dir> \
  --slot <slot> --block-no <block number> --block-hash <64 hex digits> \
  --input <SCLS file> --target-db <separate DB with immutable blocks>
```

`--config <file>` can supply the node configuration; `--genesis-dir` is optional with it and supplies
an explicit genesis override when both are given. The record and per-entry CBOR budget defaults to
16 MiB; `--record-bytes` accepts 4 KiB through 256 MiB. The independent decoded-item ceiling
remains 100,000 at every byte setting. Export takes an optional `--comment` and
uses fixed local diagnostic metadata. The output parent must already exist and support hard links;
an existing file is refused. Output and all scratch must resolve outside the source DB.

SCLS omits restart and consensus information. Import therefore compares every namespace commitment
with the explicit same-point complete base, preserves unrepresented fields, validates the new native
files, and publishes only after the target's full identity and immutable block match. The target
must have no existing UTxO, snapshots, volatile or ledger artifacts. This is interchange against an
explicit base, not standalone bootstrap. Both commands retain native (V7) restart snapshots, stored CBOR,
and the existing Haskell import/export tools. JSON events report the point/count/path or typed error.
