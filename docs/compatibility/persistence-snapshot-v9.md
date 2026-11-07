<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence: ledger snapshot format (V9)

Part of the [compatibility matrix](../compatibility.md); the register entry is `persistence-snapshot-v9` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: native V9 (`SCRBSNP9`, #54) is the V8 layout ([V8](persistence-snapshot-v8.md)) whose sidecar names the eras with Dijkstra among them and holds the node's protocol parameters with the fields Dijkstra adds (`DijkstraPParams`, cardano-ledger 9df9f98c). Adding an era to the node's `Era` changes the shape of its derived codec, and with it the schema fingerprint of every sidecar that holds an era. The readers of V5, V7 and V8 sidecars (`SnapshotSidecarV7`, `SnapshotSidecarV8`) take a frozen era codec (`FrozenEra`, Byron to Conway under the name `Era`), whose shape and bytes are those the files were written with, read totally into the live era; for the same reason they take a frozen parameter codec (`FrozenProtocolParams`, the Conway-era record under the name `ProtocolParams`), read into the live parameters with Dijkstra's fields at their pre-Dijkstra defaults, zero or none. The registered V8 → V9 step keeps the binding, the sidecar's values and the Haskell bytes and rewrites the sidecar's fingerprint; every V8 file converts, since every era it can name is a V9 era encoded the same way and its parameters are those of a node before Dijkstra. V5 and V7 files reach V9 through V8. The node does not enter Dijkstra yet, so no snapshot it writes names that era.

## Evidence

`fixtures/snapshot-v9` holds the registered conversions of the 17 `fixtures/snapshot-v8` files, each differing from its source only in the magic, the sidecar's schema fingerprint and, in the Shelley-based files, the Dijkstra parameter fields, at their defaults, in the sidecar's parameters (a Byron sidecar holds none); `SnapshotGoldenSuite` re-derives every one byte for byte, checks the binding, sidecar and Haskell bytes against the source, migrates the V5, V7 and V8 goldens once and leaves the V9 one unchanged, all ending at the same V9 bytes. `CodecSuite` pins the V9 sidecar fingerprint and the unchanged V5/V7 and V8 ones. `SnapshotV9MigrationSuite`: the frozen V8 writer reproduces committed V8 files byte for byte; committed V8 files migrate once to the maintained V9 files and read as them; every frozen era is the live era of the same index; the V9 sidecar keeps a Dijkstra era, and the frozen V5/V7 and V8 schemas refuse one (`InvalidVariant`).

## Explicitly unchecked or unconfirmed

No snapshot naming Dijkstra exists: the node refuses to enter the era until the rest of #54 lands, and a Dijkstra ledger state in the Haskell section is refused by its reader. Preprod/mainnet writer upgrades remain #129 and #37/#115.
