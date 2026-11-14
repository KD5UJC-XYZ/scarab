# Native V8 snapshot fixtures

Scarab fixture encodings are Apache-2.0. These throwaway-network states contain public
keys/signatures only, with no signing keys or operational node data; see [NOTICE](../../NOTICE).

These complete checksummed native V8 files were the maintained snapshot test inputs until V9 (#54);
they are now the previous-version inputs of the V8 → V9 migration tests, and
[`fixtures/snapshot-v9`](../snapshot-v9/README.md) holds their registered conversions, whose own
conversions in [`fixtures/snapshot-v10`](../snapshot-v10/README.md) are the maintained inputs. V8 is the V7
layout (the V5 body behind a checkpoint binding) whose sidecar keeps the installer metadata of the
Byron update state's software proposals and application versions (issue
[#726](https://github.com/KD5UJC-XYZ/scarab/issues/726)).

Every file here is the output of the registered V7 → V8 migration step (`SnapshotMigration.toCurrent`,
i.e. `SnapshotV8.fromV7`) applied to the file of the same name in
[`fixtures/snapshot-v7`](../snapshot-v7/README.md), whose own conversion provenance stays pinned
there. The step keeps the binding and the Haskell bytes, reads the sidecar in the frozen V7 schema
and writes it in the current one. None of these states holds a Byron software proposal or
application version, so none lost metadata and each converts; the converted files differ from their
sources only in the magic and the sidecar's schema fingerprint. `manifest.json` lists each source and
target hash; `SnapshotGoldenSuite` re-derives every target from its source and compares the bytes,
and `SHA256SUMS` binds the files.

Recipe (in the JDK image, from the checkout, into a directory that does not exist yet):

```sh
bash scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.storage.SnapshotV8FixtureMain /work/fixtures/snapshot-v7 /work/fixtures/snapshot-v8'
```

`SnapshotV8FixtureMain` runs the V7 → V8 `step` of the registered migration (before V9 it ran the
whole migration, whose current version was V8). The V8, V7 and V5 files remain the previous-version
inputs of the migration tests (ADR 0006); they are not read as current files.
