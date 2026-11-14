# Native V11 snapshot fixtures

Scarab fixture encodings are Apache-2.0. These throwaway-network states contain public
keys/signatures only, with no signing keys or operational node data; see [NOTICE](../../NOTICE).

These complete checksummed native V11 files are the maintained snapshot test inputs. V11 is the V10
layout whose sidecar holds the follow state (the era of the last block applied and the leader-check
verdicts of the current and the last completed epoch) where V10's held the bootstrap (ledger-lite)
state, whose flag no writer since V5 set and whose lite ledger nothing read
(issue [#988](https://github.com/KD5UJC-XYZ/scarab/issues/988)). The recorded build, the binding and
the Haskell state bytes are those of V10.

Every file here is the output of the registered V10 → V11 migration step
(`SnapshotMigration.toCurrent`, its `step`, i.e. `SnapshotV11.fromV10`) applied to the file of the
same name in [`fixtures/snapshot-v10`](../snapshot-v10/README.md), whose own conversion provenance
stays pinned there. The step keeps the recorded build, the binding and the Haskell bytes byte for
byte and writes the sidecar again in the current schema, without the bootstrap state's flag and
lite ledger and with its era and epoch verdicts as the follow state. `manifest.json` lists each
source and target hash; `SnapshotGoldenSuite` re-derives every target from its source, compares the
bytes and checks that the build, the binding and the Haskell section are the source's and the
sidecar the source's converted, and `SHA256SUMS` binds the files.

Recipe (in the JDK image, from the checkout, into a directory that does not exist yet):

```sh
bash scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.storage.SnapshotV11FixtureMain /work/fixtures/snapshot-v10 /work/fixtures/snapshot-v11'
```

The V10, V9, V8, V7 and V5 files remain the previous-version inputs of the migration tests
(ADR 0006); they are not read as current files.
