# Native V9 snapshot fixtures

Scarab fixture encodings are Apache-2.0. These throwaway-network states contain public
keys/signatures only, with no signing keys or operational node data; see [NOTICE](../../NOTICE).

These complete checksummed native V9 files are the maintained snapshot test inputs. V9 is the V8
layout (the V5 body behind a checkpoint binding, the Byron update state's installer metadata in the
sidecar) whose sidecar names the eras with Dijkstra among them (issue
[#54](https://github.com/KD5UJC-XYZ/scarab/issues/54)). The encoding of every era V8 could name is
unchanged; the sidecar's schema fingerprint is new.

Every file here is the output of the registered V8 → V9 migration step (`SnapshotMigration.toCurrent`
through `Migrate`, i.e. `SnapshotV9.fromV8`) applied to the file of the same name in
[`fixtures/snapshot-v8`](../snapshot-v8/README.md), whose own conversion provenance stays pinned
there. The step keeps the binding and the Haskell bytes, reads the sidecar in the frozen V8 schema
and writes it in the current one, so the converted files differ from their sources only in the magic
and the sidecar's schema fingerprint. `manifest.json` lists each source and target hash;
`SnapshotGoldenSuite` re-derives every target from its source and compares the bytes, and
`SHA256SUMS` binds the files.

Recipe (in the JDK image, from the checkout, into a directory that does not exist yet):

```sh
bash scripts/sbtw 'node/Test/runMain xyz.kd5ujc.scarab.storage.SnapshotV9FixtureMain /work/fixtures/snapshot-v8 /work/fixtures/snapshot-v9'
```

The V8, V7 and V5 files remain the previous-version inputs of the migration tests (ADR 0006); they
are not read as current files.
