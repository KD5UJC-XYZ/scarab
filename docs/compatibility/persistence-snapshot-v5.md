<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence: ledger snapshot format (V5)

Part of the [compatibility matrix](../compatibility.md); the register entry is `persistence-snapshot-v5` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: native V5 stays unchanged (`SCRBSNP5`, typed binary sidecar plus Haskell ledger CBOR and checksum). The registry retains historical IDs but retires V1–V4 payload readers; identity then version preflight happens before mutable stores open. Direct migration and restore entry points propagate typed retired/unknown errors. Historical conversion source and verified current-format fixtures remain recoverable; the future migration and reader-retirement procedure is [documented](../storage-migrations.md)

## Evidence

Current fixed-file/gap/semantic goldens and checkpoint restart tests are recorded in B32 below. Historical evidence (before reader retirement): round trip of every state of the Byron-to-Conway capture the writer accepts, in canonical form; the migration of V1 and V2 files of each family of era; all 1,451 snapshots of the K1 Preview sync migrated V1 to V4 (J1.5: the chain-dependent state in the Haskell shape), read back and equal to the direct read in canonical form (0 differences), V4 file 17.5 MB on average and 52.8 MB at the end, write 2.5 s on average, migrate and read back 4.4 s; the 15 V3 files of every era that the build before J1.5 wrote (`fixtures/snapshot-v3`) migrated to the states the rules give, with their gaps and observed eras carried; `ChainStorageSuite` over V3 (restart, crash points, graceful stop, UTxO checkpoints); devnet: a V2 database written by the build before opens migrated (`formatMigrated`, 3 snapshots), restores the newest, replays what follows, and a second start restores a V3 snapshot with 0 blocks replayed (J1.4); a V3 database written by 9dcbec07 opens migrated to V4 (`restart --migrate-from`, `formatMigrated` V3 to V4) and restores; `soak --mirror-check` 24/24 comparisons and 11/11 mirror checks, `e2e` Byron to Conway

## Explicitly unchecked or unconfirmed

Live state and current-format fixtures preserve source gaps and observed eras exactly through block transitions, rollback and V5 save/restore; preprod/mainnet rehearsal and approved writer cutovers remain #129. Source retirement requires verified local/repository replacements and retirement of local old writers. An existing older deployment keeps its compatible pinned binary until a separately approved database conversion and upgrade (#37/#115). Native V5 files remain distinct from the Haskell snapshot-directory format supported by Step D
