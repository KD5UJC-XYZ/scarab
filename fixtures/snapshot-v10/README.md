# Native V10 snapshot fixtures

Scarab fixture encodings are Apache-2.0. These throwaway-network states contain public
keys/signatures only, with no signing keys or operational node data; see [NOTICE](../../NOTICE).

These complete checksummed native V10 files are the maintained snapshot test inputs. V10 is the V9
layout with the reference build whose layout the Haskell state bytes are in recorded after the magic
(issue [#826](https://github.com/KD5UJC-XYZ/scarab/issues/826)): a length byte and the build's
`--reference-build` name, here `11.1`. The binding and the Haskell state bytes are those of V9; the
sidecar holds the follow state (the era of the last block applied and the epoch verdicts) where V9's
holds the bootstrap (ledger-lite) state. V10's sidecar was changed in place, before any deployment,
by the owner's decision of 2026-11-14 ([#988](https://github.com/KD5UJC-XYZ/scarab/issues/988)), and
these files were regenerated then. It was changed in place again, before any deployment, by the
owner's decision of 2026-11-16 ([#85](https://github.com/KD5UJC-XYZ/scarab/issues/85),
[#54](https://github.com/KD5UJC-XYZ/scarab/issues/54)): PlutusV4 among the kinds of a stored script,
which moves the sidecar's schema fingerprint and nothing else, and these files were regenerated again.
It was changed in place a third time, before any deployment, by the owner's decision of 2026-11-17
([#826](https://github.com/KD5UJC-XYZ/scarab/issues/826)): Praos2, the protocol of Dijkstra in the
Leios build, among the chain-dependent states, which again moves the sidecar's schema fingerprint and
nothing else, and these files were regenerated again.

Every file here is the output of the registered V9 → V10 migration step (`SnapshotMigration.toCurrent`
through `Migrate`, i.e. `SnapshotV10.fromV9`) applied to the file of the same name in
[`fixtures/snapshot-v9`](../snapshot-v9/README.md), whose own conversion provenance stays pinned
there. The step records the 11.1 build, the only one whose layout a V9 file can hold, keeps the
binding and the Haskell bytes byte for byte, and writes the sidecar in the V10 schema: the bootstrap
state's flag and lite ledger dropped, its era and verdicts kept. `manifest.json` lists each source
and target hash; `SnapshotGoldenSuite` re-derives every target from its source, compares the bytes
and checks that the binding and the Haskell section are the source's and the sidecar the source's
converted, and `SHA256SUMS` binds the files. Two runs of the recipe write identical bytes.

Recipe (in the JDK image, from the checkout, into a directory that does not exist yet):

```sh
bash scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.storage.SnapshotV10FixtureMain /work/fixtures/snapshot-v9 /work/fixtures/snapshot-v10'
```

The V9, V8, V7 and V5 files remain the previous-version inputs of the migration tests (ADR 0006);
they are not read as current files.
