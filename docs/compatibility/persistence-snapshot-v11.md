<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence: ledger snapshot format (V11) without the bootstrap mode

Part of the [compatibility matrix](../compatibility.md); the register entry is `persistence-snapshot-v11` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: native V11 (`SCRBSNPB`, #988) is the V10 layout ([V10](persistence-snapshot-v10.md)) whose sidecar holds the follow state (`FollowState`: the era of the last block applied and the leader-check verdicts of the current and the last completed epoch, from which the follower's `eraEntered` and `epochValidated` events are made) where V10's held the bootstrap (ledger-lite) state. That state's flag had been set by no writer since the era-indexed rules (V5), and its lite ledger, built from the Shelley genesis, was never advanced or read; both are gone from the node with the mode, and so is the forger's refusal on the flag. The previous sidecars (V5 to V10) are read into frozen copies of the types they were written with (`FrozenSidecar`), so the steps between previous versions carry the bootstrap state whole and the V5/V7, V8 and V9/V10 schema fingerprints are unchanged. The registered V10 → V11 step keeps the recorded build (whatever it is), the binding and the Haskell state bytes byte for byte, and writes the sidecar again in the current schema with the flag and the lite ledger dropped and the era and the epoch verdicts kept; no value the node reads is lost, so every V10 file whose sidecar reads converts. The export and the SCLS base read a V9 or V10 file of a database not yet opened as its migration would write it, in memory.

## Evidence

`fixtures/snapshot-v11` holds the registered conversions of the 17 `fixtures/snapshot-v10` files; `SnapshotGoldenSuite` re-derives every one byte for byte, checks that the build, the binding and the Haskell bytes are the source's and the sidecar the source's converted, and migrates the V5, V7, V8, V9 and V10 goldens once, all ending at the same V11 bytes. `CodecSuite` pins the new sidecar fingerprint, keeps the former one as the frozen V9/V10 schema's, and the V5/V7 and V8 ones unchanged. `SnapshotV11MigrationSuite`: the frozen V10 schema writes every committed V10 file back byte for byte; the committed files hold eras and epoch verdicts (and no set flag); each converts to its maintained V11 file with the follow state equal to its era and verdicts and every other sidecar value equal; `preflight`, `detect`, `run` (twice, the second a no-op) migrate it; a V10 file whose flag is set and whose lite ledger holds state in every field converts to the same bytes as one with neither; a V10 file recorded under the Leios build migrates with its build and without its state bytes being read; a directory of V5 to V11 files migrates in one run; an interrupted step leaves the V10 file for the next run. `LedgerSnapshotExportSuite` and `SclsBaseSuite` export from a V10 file not yet migrated.

## Explicitly unchecked or unconfirmed

Preprod/mainnet writer upgrades remain #129 and #37/#115.
