<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence: network identity format (V9)

Part of the [compatibility matrix](../compatibility.md); the register entry is `persistence-network-identity-v9` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: `<db>/network-identity` V9 (`scarab network identity v9`, #824) is the V6 record with a
`dijkstra-genesis` line: the Blake2b-256 of the Dijkstra genesis file the configuration names (read with
`ExperimentalHardForksEnabled`; node setup checks it against `DijkstraGenesisHash` when one is listed), or
`none` for a configuration without one. A new database starts at V9 and an operator's adoption of a legacy
V1/V2 record writes V9. The registered migration rewrites a V6 file as V9 with the configured binding once
its other fields equal the configured identity, keeping its provenance; one of another network is a
`NetworkMismatch` and is left as it is, and a migration with no configured identity is refused
(`IdentityNeedsDijkstraBinding`). A V9 record of `none` adopts a Dijkstra genesis configured later, once,
when `ChainStorage.open` runs (`NetworkIdentityFile.adoptDijkstra`, logged as `networkRecorded` with
`adoptedDijkstraGenesis`); a recorded binding is never replaced, so a different configured one, or none
configured, is a `NetworkMismatch`. `verify` and the read-only preflight accept a V6 file and a pending
adoption without writing. The rules are the owner's decisions OD-2 of #54 and its refinement of 2026-11-08
([decisions](../decisions.md)); the layout is in [persistence](../architecture/persistence.md).

## Evidence

`NetworkIdentityV9Suite` reads the committed previous-version file `fixtures/network-identity-v6` (the
devnet network of `fixtures/dijkstra-devnet`), checks that it is what V6 records for the identity the node
assembles from that configuration, and migrates it: the configured binding, provenance kept, a second run a
no-op; without a Dijkstra genesis it records `none`, which then adopts a configured one once and reads back;
a recorded binding against another or none is a mismatch with the file unchanged; `none` with none stays
`none`; another network's identity and a migration without configuration are refused. `ChainStorageSuite`
runs the migration and the adoption through `ChainStorage.open`, including the logged event and a second
open that logs nothing. `FormatMigrationSuite` round-trips every registered identity version. The restart
scenario (`devnet.py restart`) checks that the database's identity is V9.

## Explicitly unchecked or unconfirmed

The V6 fixture is written by hand in the V6 layout, not taken from a database of a previous release. No
scenario opens a database with a Dijkstra genesis configured: the restart scenario's network has none, so it
exercises the `none` record only, and no public network names a Dijkstra genesis except the Leios testnet,
which the node does not follow yet (#54).
