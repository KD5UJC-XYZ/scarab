<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network identity V6 (previous version)

Status: Current — the previous-version input of the network identity V6 → V9 migration (#824).

`network-identity` is a database's `<db>/network-identity` file in format V6 (`scarab network identity
v6`), the format before the Dijkstra genesis binding of V9 ([ADR 0006](../../docs/adr/0006-versioned-formats-migrate-on-open.md)).
It records the network of the [Conway to Dijkstra devnet](../dijkstra-devnet/README.md): network
magic 1082026, no Byron genesis (the chain starts in Shelley at epoch 0), and the Blake2b-256 of the
Shelley, Alonzo and Conway genesis files in [`../dijkstra-devnet/genesis`](../dijkstra-devnet/genesis),
with the provenance `fresh`.

The file was written by hand in the V6 layout from those hashes. `NetworkIdentityV9Suite` (node) checks
that it is byte for byte what a V6 build records for the identity the node assembles from
`../dijkstra-devnet/genesis/config.json`, then runs `NetworkIdentityFile.migrate` on it with that
identity, as `ChainStorage.open` does: the V9 file it writes binds the Dijkstra genesis file of that
configuration, whose Blake2b-256 is the configuration's `DijkstraGenesisHash` (`ccd5fd42…`), and keeps
the provenance. The suite also migrates it with the same identity without a Dijkstra binding (it
records `none`, which a later Dijkstra binding then refuses) and checks it against the identity of
another network's configuration, `fixtures/eras/genesis` (refused and left as it is).
`ChainStorageSuite` covers the same migration through `ChainStorage.open`.

No key, chain data or third-party content; original Apache-2.0 work of this repository.
