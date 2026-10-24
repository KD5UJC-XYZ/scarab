# Native V5 snapshot fixtures

Scarab fixture encodings and the conversion wrapper are Apache-2.0. These throwaway-network states
contain public keys/signatures only, with no signing keys or operational node data; see [NOTICE](../../NOTICE).

These complete checksummed native V5 files were the maintained snapshot test inputs until V7
(#395); they are now the previous-version inputs of the V5 → V7 migration tests, and
[`fixtures/snapshot-v7`](../snapshot-v7/README.md) holds their registered conversions, which are the
maintained inputs. They replace `fixtures/snapshot-v3`; the historical originals remain in Git at
the blobs in `manifest.json`.
The current registry recognizes V1–V4 identifiers and refuses their retired payload formats; it
migrates native V5 to V7. The original readers are retained in pinned historical source, not on the
runtime classpath. Future format changes still require migration and previous-version tests under
[ADR 0006](../../docs/adr/0006-versioned-formats-migrate-on-open.md). Operational writer upgrades
remain [#37](https://github.com/KD5UJC-XYZ/scarab/issues/37) /
[#115](https://github.com/KD5UJC-XYZ/scarab/issues/115). The native V5 layout is unchanged.

`eras/` contains fifteen verified conversions of the historical V3 files, covering Byron through
Conway. Their precise original writer execution revision was not recorded. The files and conversion
manifest are reused unchanged from `83e74e0e4da0f14ad522983baf0b24475e2e956e`. Conversion used unchanged
`SnapshotV5.migrated` at `648aed117f826d06117f616b5c0a3716a50485a7`, including its Full canonical
read-back check. The wrapper additionally compared anchor, UTxO count/coin, exact gaps and observed
eras, and complete typed Byron state where present. The complete fifteen-file conversion exited 0.
The first wrapper attempt compared an unused Shelley placeholder in Byron and failed; the corrected
wrapper compares actual Byron state. No failing attempt counts as passed evidence.

`preview-4320.bin` is a verified conversion of a small V2 reproduction by pinned historical writer
`9ff0ca7d08acaab81282437e8398c1c895b6a359`. That writer read an original V1 capture's payload with its
original codec and wrote a V2 wrapper. The V1 capture's exact writer revision is unknown. The
original/reproduction hashes and distinction are in the manifest. `conway-native-730.bin` is a fresh
native V5 writer reproduction after the complete era capture. Its gaps differ from carried historical
gap metadata: live provenance persistence remains [#129](https://github.com/KD5UJC-XYZ/scarab/issues/129).

`SHA256SUMS` binds all maintained files. `SnapshotGoldenSuite` pins complete-file hashes and asserts
anchor/epoch/UTxO/coin values and exact carried gap metadata. `SnapshotV7Suite` independently replays
the original block capture and compares chain position, consensus state and UTxO accounting for every
era fixture. Historical reward bookkeeping is not claimed equal to every current-rule ledger field.
Recorded-data tools use `NativeSnapshots` (which brings V5 to V7 in memory by the registered conversion) and the verified conversion corpus; they do not decode
old captures through a flat-state adapter. Original private archives remain unchanged.

The optional conversion recipe is `scripts/fixtures/snapshot-v5/Snapshot83BridgeMain.scala`, compiled
against pinned historical source rather than the current build:

1. Export `git archive 648aed117f826d06117f616b5c0a3716a50485a7` to a clean directory.
2. Copy the wrapper to `node/src/main/scala/xyz/kd5ujc/scarab/node/tools/Snapshot83BridgeMain.scala`.
3. In `scarab-jdk:local`, mount that tree at `/work`, the dependency cache at `/work/.cache`, original
   inputs at `/inputs:ro`, genesis at `/genesis:ro`, and a fresh output at `/output`. Set
   `SBT_GLOBAL_BASE=/tmp/sbt-global` and run:

   ```sh
   bash scripts/sbtw 'node/runMain xyz.kd5ujc.scarab.node.tools.Snapshot83BridgeMain /inputs /output /genesis'
   ```

The wrapper rejects unexpected checksums, anchor/name disagreement, metadata changes and an existing
output with different bytes. It emits its complete manifest only after every conversion succeeds.
Historical `sourceFile` paths in the manifest describe the pinned original Git blobs; current loaders
never open those paths. Preserve original signed/hashed bytes and keep archived input read-only.
