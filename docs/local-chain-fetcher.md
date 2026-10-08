# Bounded local chain acquisition (v0.15)

`fetcher` is a reusable Cats Effect library, independent of the CLI, ledger and VM.
It consumes original, hash-pinned **local** block files and persists one selected
parent-linked segment. It does not contact a node, explorer, archive, or socket.
No keys, signing, proving, consensus validation, ledger replay, or Mithril
certificate validation are involved.

## Run the retained original-byte samples

From the repository root, with a nonexistent or empty output directory:

```sh
./scripts/sbtw 'app/run chain-fetch run --config fixtures/chain-fetch/shelley.tsv --output /tmp/shelley-segment'
./scripts/sbtw 'app/run chain-fetch inspect --output /tmp/shelley-segment --verify-bytes'
./scripts/sbtw 'app/run chain-fetch run --config fixtures/chain-fetch/shelley.tsv --output /tmp/shelley-segment --resume'
```

`fixtures/chain-fetch/allegra.tsv` similarly selects four Allegra blocks.
`fixtures/chain-fetch/babbage.tsv` selects four preprod Babbage blocks after the
retained first block as exclusive anchor; all five are independently indexed. These
commands call the same public `Fetch.run[F]`, `BlockSource[F]`, and
`SegmentStore[F]` APIs used by another JVM application. They are real local-file
acquisition commands, not protocol fixtures pretending to download remotely.

The Shelley and Allegra windows are independent, not consecutive with each other. Their
predecessor points are source assertions pinned by the manifest. Their genesis,
network identity and archive authentication remain unestablished. The label
`mainnet-labelled-unauthenticated` must not be read as chain authentication.

## Exact supported subset

- Post-Byron disk-era CBOR envelopes with tags 2–7 (Shelley through Conway).
  Byron and later unknown tags, NtN header envelopes, NtC tag-24 wrappers, network
  BlockFetch messages and concatenated chunks are explicitly unsupported.
- Complete original bytes are retained. Header BLAKE2b-256 uses the original
  header slice, not a reconstructed encoding. Raw SHA-256 is a separate identity.
- The index checks the supported envelope/header structures and field sizes,
  and top-level transaction/witness/metadata container shapes. It does not check
  body commitments, transactions, signatures, VRF/KES, ledger state, eligibility,
  canonicality, finality or chain selection.
- A concrete exclusive anchor is required. `origin` is rejected: these retained
  era-start blocks do not establish a genesis-to-era chain. An anchor may equal
  the source predecessor or a block found by verified sequential scanning.
- Positive count and/or concrete inclusive end is required. End wins when both
  conditions become true at the same committed block. Otherwise the first
  satisfied condition wins. Missing/forked end at or past its slot is an integrity
  error, not success. Exhaustion before the requested stop is incomplete.
- Strictly increasing slots and exact parent header hashes are required for the
  supported regular post-Byron blocks. No Byron EBB assumptions are made.
- Fail-on-fork only. Rollback to the current observed point is a no-op; any other
  rollback fails. There is no silent rewind or automatic retry.

## Strict TSV configuration

This first version deliberately uses a small line-oriented format rather than
JSON. It is not the broader proposed JSON/network-genesis schema. Every field
below is required exactly once, order is irrelevant, and every line has exactly
one tab. UTF-8, LF and a final newline are required. No comments, blank lines,
duplicate/unknown fields, escape sequences or extra columns are accepted.

```text
format<TAB>chain-fetch-v1
manifest<TAB>shelley/source.tsv
manifestSha256<TAB>64-lowercase-hex
after<TAB>UINT64:64-lowercase-hex
count<TAB>4
end<TAB>-
maxBlocks<TAB>64
maxBlockBytes<TAB>1048576
maxInputBytes<TAB>8388608
maxStoredBytes<TAB>8388608
maxFiles<TAB>256
maxDurationSeconds<TAB>120
forkPolicy<TAB>fail
```

Replace `<TAB>` with a literal tab. The
included `.tsv` files are directly executable examples. `count` or `end` may be
`-`, but not both. Points retain full UInt64 precision as decimal integers and
lowercase 32-byte header hashes. Paths resolve relative to their containing
config/manifest, must be safe relative paths with ordinary ASCII filename
characters, and cannot contain `.` or `..` components or symlinks.

Source manifests have this exact format, preserving row order:

```text
local-blocks-v1
SOURCE_LABEL<TAB>PREDECESSOR_POINT
relative-block.cbor<TAB>BYTE_LENGTH<TAB>RAW_SHA256
```

The label is 1–100 ASCII letters, digits, dot, underscore or hyphen. Paths must be
unique. Each file's length and SHA-256 is verified before indexing. The manifest
is at most 1 MiB and 4,096 entries; config is at most 64 KiB. No arbitrary URLs,
credentials, synthetic block generation or implicit network lookups exist.

## Bounds and ownership

- `maxBlocks`: 1–4,096 retained blocks. Count selection also has a 4,096 ceiling.
- `maxBlockBytes`: 1–1,048,576 bytes, the indexer's hard raw-input maximum.
- `maxInputBytes`: positive source-byte **admission** budget for pinned manifest
  bytes plus declared file lengths, including repeated reads and resume scans.
  A file reserves its full declared size before reading, so failures can report
  more admitted bytes than actually read. Config reading has its separate 64 KiB
  bound. An EOF probe reads at most one extra byte to detect growth and fails.
  This is not a network bandwidth meter or an exact heap bound.
- `maxStoredBytes`: positive total file bytes in output, including identity,
  checkpoints, historical immutable manifests, crash orphans and staging.
  Admission conservatively reserves all transient append bytes, even if an
  identical orphan could be reused. OS directory/inode overhead is excluded.
- `maxFiles`: 8–20,000 files, including metadata and staging. Immutable full
  manifest generations are retained, so metadata is quadratic in block count;
  byte/file quotas and the hard 4,096-block ceiling bound this implementation.
- `maxDurationSeconds`: 1–86,400, a monotonic CLI acquisition deadline after bounded config loading, covering source opening, store initialization/recovery and the acquisition loop. The reusable `Fetch.run` timer covers only its loop; callers own initialization budgets. Inspection is byte/file bounded and has no time deadline. Cancellation
  is cooperative: an admitted blocking file operation/commit finishes before
  release. This is not a hard real-time deadline or interruptible disk I/O.

One source cursor and one acquisition run per source/store are supported.
Sources are pulled one object at a time. The runner checks the source and selection against the store binding before opening a cursor; mismatched composition is rejected. The NIO store serializes its calls and
owns an exclusive output lock for its resource lifetime; retained handles reject
calls after release. Other applications should scope it with `Resource.use`.
There is no global runtime, detached fiber or `unsafeRunSync` in the library.

Raw bytes, `Vector[Byte]`, decoded CBOR trees and immutable record vectors incur
allocation/copy overhead. The indexer's depth (32), item (100,000), string and raw
limits constrain that work. No `maxWorkingSetBytes` promise or large-scale heap
benchmark is claimed. There are no remote request/idle/decompression quotas
because this version has no corresponding adapters.

## Durable commit and recovery

```text
OUTPUT/
  lock
  identity
  checkpoint
  objects/<raw-sha256>.cbor
  manifests/<manifest-sha256>.manifest
  staging/{identity,object,manifest,checkpoint}.tmp
```

The identity pins format, source manifest digest/label/predecessor, selection,
and fail-on-fork policy. Operational limits are excluded so they may be raised
on resume; lowering below current stored usage fails. The manifest file is
immutable, includes the previous manifest hash, and contains the complete active
record vector. The atomically replaced checkpoint is the sole commit boundary.

Each append writes/forces the original object, atomically installs it, writes/
forces/installs the immutable manifest, then writes/forces and atomically replaces
the checkpoint. File contents and containing directory entries are forced.
Existing immutable targets are reused only if bytes match exactly. No fallback
from `ATOMIC_MOVE` is used. Platforms/filesystems that cannot support these
operations fail, rather than silently weakening the protocol. Directory forcing
and atomic moves were exercised on the test host; hardware/power-loss guarantees
remain dependent on the filesystem, OS and storage device. Tests establish
process-interruption ordering, not universal power-loss durability.

Resume verifies the identity, checkpoint, whole active manifest ancestry and
all referenced original objects. It rescans/reverifies the source overlap under
its new invocation admission budget. It does not trust an offset to skip checking
committed bytes. Source/selection changes, corrupt committed objects, manifest
corruption, unsafe entries and incompatible output fail closed.

A crash before checkpoint publication leaves an orphan, never an advanced
checkpoint. Orphans are retained and count against quotas; matching immutable
objects can be reused. No broad cleanup/deletion is performed. Staging filenames
are tool-owned scratch space and may be overwritten after recovery. Repeated
failures may exhaust quota; explicitly raise it or diagnose the directory.

Interrupted empty initialization can resume only with a compatible identity,
empty object directory, and the exact empty-generation manifest (if installed).
Missing checkpoint beside actual block data is corruption, not an invitation to
promote an orphan. New output must be empty. Unrecognized files are never
silently overwritten. `inspect` locks and always verifies active bytes, even
without `--verify-bytes`; it does not repair or clean output.

Use directories exclusively controlled by this process/user with stable,
trusted ancestors. Symbolic links and traversal are rejected, but path checks
are not a sandbox against a hostile local process concurrently replacing parent
directories. Hard-link attacks and malicious same-user filesystem mutation are
outside this owned-directory contract. Output should not contain source files.

## Results and errors

Run emits one terminal JSON result, with complete/incomplete reason, source label/digest, first/last points, committed
count, admitted source bytes, active manifest SHA-256 and explicit false network,
ledger, consensus, reference replay and Mithril authentication claims. Errors go
to stderr. A timeout or budget stop leaves the last published checkpoint resumable and inspectable. A CLI timeout during initialization emits a smaller terminal JSON without an unverified block count; inspect or resume determines the durable result.
A canceled run may not emit a terminal result; reopen to establish what committed.
There is no persistent terminal-status generation: status is recomputed from the
verified checkpoint and requested selection on the next run.

Exit codes: 0 requested stop reached (or successful inspection), 2 invalid or
unsupported configuration, 3 incomplete/budget, 4 source/I/O failure, 5 source
integrity/fork/anchor violation, 6 locked/incompatible/corrupt output. External
runtime cancellation uses the JVM/runtime's exit behavior; a guaranteed 130 is
not claimed.

## Evidence and provenance

See `fixtures/chain-fetch/PROVENANCE.md` (optional private corpus; not distributed)
for the retained eight source bytes and independent expected header hashes.
Tests include real Shelley/Allegra windows, full UInt64 fields, original-header
encoding preservation, malformed envelopes, selection/forks, cancellation,
exclusive locks, corruption, quotas and injected failure at all fifteen append
write/force/install boundaries. No live service is needed or contacted by these tests.

The v1 manifest format is deliberately unchanged: its era field now accepts the
shared six-era whitelist. Existing Shelley/Allegra manifests remain valid without
migration or invented metadata; byte/hash/parent/slot checks and strict slot ordering
are unchanged. See [post-Byron subset](post-byron-indexing.md) for null-parent,
reference-width and body-commitment limitations.
