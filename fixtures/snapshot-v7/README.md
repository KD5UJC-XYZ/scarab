# Native V7 snapshot fixtures

Scarab fixture encodings are Apache-2.0. These throwaway-network states contain public
keys/signatures only, with no signing keys or operational node data; see [NOTICE](../../NOTICE).

These complete checksummed native V7 files were the maintained snapshot test inputs until V8
(#726); they are now the previous-version inputs of the V7 → V8 migration tests, and
[`fixtures/snapshot-v8`](../snapshot-v8/README.md) holds their registered conversions, whose own
conversions in [`fixtures/snapshot-v9`](../snapshot-v9/README.md) are the maintained inputs. V7 is the V5 body (sidecar and Haskell ledger state) behind a checkpoint binding
([ADR 0021](../../docs/adr/0021-ledger-state-residency-and-utxo-checkpoints.md), issue
[#395](https://github.com/KD5UJC-XYZ/scarab/issues/395)).

Every file here is the output of the registered V5 → V7 migration step (`SnapshotMigration.toCurrent`,
its V5 → V7 `step`, i.e. `SnapshotV7.fromV5`) applied to the file of the same name in
[`fixtures/snapshot-v5`](../snapshot-v5/README.md), whose own conversion provenance stays pinned
there. The step copies the V5 body after its magic byte for byte and records the
`CheckpointBinding.Legacy` binding: a V5 file never recorded its checkpoint, and these state
fixtures have none. `manifest.json` lists each source and target hash; `SnapshotGoldenSuite`
re-derives every target from its source and compares the bytes, and `SHA256SUMS` binds the files.

Recipe (in the JDK image, from the checkout, into a directory that does not exist yet):

```sh
bash scripts/sbtw 'node/Test/runMain xyz.kd5ujc.scarab.storage.SnapshotV7FixtureMain /work/fixtures/snapshot-v5 /work/fixtures/snapshot-v7'
```

The V5 and V7 files remain the previous-version inputs of the migration tests (ADR 0006); they are
not read as current files.
