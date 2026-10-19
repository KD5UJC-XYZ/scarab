# SCLS binary import and export

**Status:** Current, [#77](https://github.com/KD5UJC-XYZ/scarab/issues/77). The owner approved
both interchange directions alongside Haskell snapshot import/export. Native restart snapshots
remain V5; stored UTxO values remain CBOR. The pinned profile's codecs, complete-base mapping,
offline import/export services, durable publication and CLI are implemented. Final integration
gates passed on frozen source `47c9667e`; evidence is recorded below.

## Reference profile

The supported profile is `ledger-f649-conway-v0`: cardano-ledger canonical-state
`f649f9751074d2ab3de033fc3912f29c9862c1f5` with Tweag cardano-cls
`8e2bba9442cb388089f53d708cc4aedcb47726ea`. Its twelve namespaces differ from the draft CIP-0165
sixteen-namespace description. Source pins, original Haskell output, independent verification and
the complete namespace list are in [the fixture documentation](../../fixtures/scls/README.md).
This profile does not claim compatibility with every future revision of the proposed CIP.

The artifact represents UTxO, current-epoch block counts, accounts, voting entities, pools and
governance. It omits consensus state, the block hash, genesis identity, restart bookkeeping,
reward/snapshot caches and other ledger fields. A verified artifact is therefore insufficient to
construct a complete node state without an explicit complete base.

## Import completeness and admission

`SclsBase.resource` holds an offline ownership scope over a verified native snapshot and its
exact immutable UTxO checkpoint. Its base context includes the complete network identity (including
Alonzo and Conway genesis hashes) and the exact block reference. The supplied expected context must
agree with both. A matching slot alone does not establish a matching chain point.

Before publication, the service must project **all twelve** namespaces from that same base and
compare their canonical entry counts and commitments with the fully validated incoming artifact.
This includes a complete streaming UTxO pass; comparing only the small metadata namespaces is
insufficient. Chunk counts, chunk boundaries and diagnostic summary metadata may differ. The
`SclsAdmission` checker produces a private admission value only after the context, slots, every
namespace commitment and global commitment agree. Its caller must derive the base context and
projection within the same owned snapshot/checkpoint scope. The checker does not acquire that
scope or authorize storage publication by itself.

The base reader rejects missing/mismatched identity, declared state gaps, a different anchor,
ledger/header era disagreement, pending UTxO changes and a checkpoint at another generation. Its
Conway ledger state and read-only entry stream remain scoped to database ownership. Temporary
RocksDB diagnostics live in an owned scratch directory, outside the source, and are removed on
release. Integration tests use the real Haskell state/tables fixture in a throwaway native database,
remove source write permissions and verify every source-file hash after reading or refusal.

Import reconstructs represented fields from incoming typed values while retaining the explicit
base's unrepresented state. Missing base information is a typed refusal. It must not instantiate a
default `NewEpochState`, infer genesis from SCLS, or describe this contract as standalone bootstrap.
Final reconstruction must reproduce the incoming canonical projection and satisfy the ledger's
cross-field invariants before native snapshot readback and publication.

## Lossy projections

The pinned Haskell exporter normalizes dormant DRep expiries and resets its dormant counter before
serializing (`Conway/Rules/Certs.hs:308-328`; `CanonicalState/Conway/Export.hs:363-371`). The counter
is absent from the artifact. Word64 wrapping allows two original expiries to have the same exported
expiry, so subtraction plus a forward roundtrip cannot recover the original uniquely.
`SclsDRepMapper` instead checks each canonical DRep against the base's normalized value and retains
that base's known original expiry and counter. Ten calls to the unmodified Haskell function,
including an explicit collision, are recorded in the small dormancy fixture.

Canonical Conway PParams contain exactly 31 fields. `Params.projectConway` and `overlayConway`
capture/apply those fields with typed descriptors and preserve absent-era values from the explicit
base. Canonical execution units are unbounded Naturals, while the native state codec retains its
existing bounds. Canonical cost-model maps and lists use definite lengths; native snapshot framing
is unchanged. Missing future-PParams rows cannot distinguish `NoPParamsUpdate` from
`PotentialPParamsUpdate(None)`, so reconstruction must retain the admitted base's exact variant.
Represented future selectors must agree with that variant.

Proposal reconstruction must preserve order and IDs and validate a contiguous order sequence.
The decoded SCLS `OnChain` wrapper retains its original embedded procedure bytes, but the actual
Haskell `toGovActionState` conversion discards that wrapper and `fromGovActionState` serializes the
typed procedure again (`CanonicalState/Conway.hs:270-292`, `BasicTypes.hs:93-94`). A nonminimal
deposit encoding therefore normalizes through ledger state. The recorded actual conversion in
`fixtures/scls/proposal-roundtrip` preserves this negative byte-equality result. The mapper follows
that reference behavior; it does not add raw-byte provenance fields to native or legacy models.
Complete-base admission still requires the full input projection to match its base. Pool maps and
accounts are complete replacements; omitted old entries must not be merged back in.

## Bounded storage and publication

`SclsStreaming` validates bounded feeds, framing, typed keys/values, ordered uniqueness, commitments
and exact EOF. Its callbacks write only to unpublished staging. A manifest or trailing-input
failure never yields a verified container. Caller-scoped typed error capabilities remain active
through consumption. Import also enforces per-namespace entry budgets from the complete-base
projection before accumulation, so an oversized input cannot grow typed metadata beyond that base
while waiting for its terminal manifest.

The UTxO streams between a stable checkpoint and a new staged store; it is never collected into a
whole-set Scala map. `SclsEntrySorter` provides a resource-owned temporary RocksDB with bounded
native buffers and canonical raw namespace/key ordering. Native UTxO keys use a big-endian index;
SCLS uses a little-endian index, so sorting cannot be skipped. Original output CBOR is retained.
An iterator holds its native resource lease until it is closed, including on cancellation.

`SclsInterchange.exportFile` writes an owned temporary file on the destination filesystem, flushes
and syncs its data, closes it, then streams its actual bytes through the independent validator. The
readback manifest must equal the generated manifest. Publication uses an atomic hard-link creation
that refuses an existing destination, followed by directory sync; a directory-sync failure removes
the newly published link. The temporary directory is resource-owned and removed on every outcome.
The output parent must already exist and support hard links. It must resolve outside the source DB.

`SclsInterchange.importFile` owns a separate target database and verifies its complete identity and
exact immutable block before staging. Source and target paths must be disjoint. The import stage
lives beside the target so publication stays on the same filesystem. The existing native import
publication capability rechecks collisions, syncs checkpoint/staging directories and publishes the
checkpoint before the snapshot, with rollback on typed publication failure. Existing native import
failure regressions cover that reused boundary. Its rename capability takes the active typed
publication-error scope at each call, so a constructor captured in another service cannot bypass
rollback by reporting a filesystem error on a different channel. Source logs, sorters and import staging all reject
scratch paths resolving within the source DB, including symlink aliases. No persisted native format
changes are part of this issue.

## Stages and evidence

1. Reference artifacts, typed container/value codecs, commitments and bounded output: implemented;
   the original 6,060-byte Haskell artifact is reproduced exactly.
2. Streaming bridge and disk ordering: implemented; effectful cancellation and native-resource
   lifetime tests execute under `CatsEffectSuite`.
3. Typed metadata reconstruction and admission checks: implemented for all eleven non-UTxO
   namespaces, with an immutable incremental builder, canonical PParams helpers and DRep preimage
   policy backed by focused reference regressions.
4. Offline services: native-base export and unpublished staged import are implemented. Import
   checks stake/deposited invariants, preserves the complete base sidecar, closes/checkpoints its
   store and reopens the native output to compare all twelve canonical namespace commitments.
   Durable file export/import publication and `export-scls` / `import-scls` CLI commands are
   implemented. Each command requires a complete base DB, external scratch directory, local
   `--config` or `--genesis-dir`, and exact Conway point (`--slot`, `--block-no`, `--block-hash`).
   `import-scls` additionally requires an existing target DB at that same immutable point; SCLS
   cannot bootstrap a database by itself. Both commands use a 16 MiB record bound by default;
   `--record-bytes` accepts explicit values from 4 KiB through 256 MiB and sets the CBOR input and
   string-byte limits for each entry. The independent CBOR item limit remains 100,000 at every byte
   setting. The sorter entry bound includes an additional 4 KiB for the namespace and packed key.
   Export diagnostics use the fixed
   local summary `createdAt=local`, `tool=scarab-scala-lab`; an optional `--comment` is available.
5. Final gates passed on `47c9667e`: full Docker `check` (4,932 passed, 16 known optional-data
   skips, zero failures/errors across fourteen modules), explicit `scalafixAll --check`, and
   `fuzz 100000 1 4` (76 targets, 400,000 cases each, 30.4 million total). The private
   `v02-scls77` restart passed with stops of 0.7/0.8 seconds and no failures; its runtime source
   `f7ce324b` has identical storage-tree and `Main.scala` identities to the final source. The
   subsequent change only retains the independent CLI CBOR item ceiling, with a focused decoder
   regression and the final full gates. The owned devnet was removed.

Stock Haskell equality of the complete re-imported fixture is false because two UTxO outputs change
internal MemPack constructors. Non-UTxO state and on-chain output CBOR agree, and SCLS re-export is
byte-identical. This remains an explicit limitation rather than a native-storage or continuation
claim. Large acceptance datasets remain outside Git. The V5 base file and typed non-UTxO ledger state are
materialized using the existing native reader; bounded UTxO/frame streaming does not establish a
fixed total heap bound for arbitrarily large bases. Broader profiles, other eras and large-state
acceptance are tracked by [K8a](../roadmap.md), outside this pinned Conway contract.

The export service now streams metadata and the owned native checkpoint through the sorter and
bounded binary writer, checks observed UTxO count/coin, and validates all generated framing and
commitments before returning a proof. A real native-base integration test reproduces the independent
Haskell artifact exactly. Staged import reconstructs a native snapshot and checkpoint from that
artifact; malformed/trailing input, wrong exact context and cancellation remove unpublished staging
and preserve the original source. Native snapshot schema/digest goldens are unchanged. The filesystem
publication APIs and CLI integration are implemented and the final whole-feature gates pass.
