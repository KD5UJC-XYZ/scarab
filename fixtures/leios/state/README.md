# Leios build state files (reference only)

Status: Current — original native ledger snapshots of the Leios build, the inputs of its `state`
layout codec (#826 step b).

Two ledger snapshots (`ledger/<slot>/{state,tables,meta}`) that a node of the build the public Leios
testnet runs wrote on a local devnet: the last state of Conway and the first of Dijkstra. Scarab took
no part in the run. Read with [the Leios fixtures](../README.md) and
[docs/leios.md](../../../docs/leios.md); the layout itself is described in
[haskell-snapshots.md](../../../docs/design/haskell-snapshots.md).

Recorded on 2026-11-08 with

```sh
python3 scripts/devnet.py --name t8state up-dijkstra --blocks-after 8
python3 scripts/devnet.py --name t8state down
```

run through a wrapper that added a snapshot policy to the node configuration, so that every block
that becomes immutable gets a ledger snapshot: `LedgerDB.Snapshots` of `SnapshotInterval` 1,
`NumOfDiskSnapshots` 400, and `SlotOffset`, `RateLimit`, `MinDelay` and `MaxDelay` 0 (cardano-node
`b004fe0c` `Configuration/POM.hs:518-605`); `genesis/config.json` is the configuration the nodes read,
that policy included. The two snapshots were copied from one node's `ledger/` directory.

## Reference

The image `ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet:rc-2026w44`, run by digest
`sha256:70439665b4b93c58ecaa8f73c8919ac7b358f938dd262b6644cd515270773fca` (`devnet.py up-dijkstra`
pins it): cardano-node `b004fe0c50913dcdaa827a98e68a43d0ccce152a`, with cardano-ledger
`9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c` and ouroboros-consensus
`2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b`, the pins of `--reference-build leios-b004fe0c` in
`docs/data/reference-pins.json`. The local oracle image `scarab-leios-testnet:local`
(`sha256:768c48540a3d818a3470a9510dd333b3051fc9e267c31a5aed5a30fe864229ab`, built from that release
by [its README](../../../scripts/oracle/leios-testnet/README.md)) was not used to make these files.

## Network

Network magic 1082026, 3 pools, k = 5, f = 0.2, epochs of 200 slots of 0.1 s. Every Shelley-based era
up to Conway starts at epoch 0, Dijkstra at epoch 1 (slot 200), with `ExperimentalHardForksEnabled`
and protocol version 12.0 throughout. The first Dijkstra block is block 39 at slot 203, header hash
`2b4f17c5963e2a17de88277c18e73d11d666d8d83206b9a0786de787dd48e543`. This is another run than
[`../../dijkstra-devnet`](../../dijkstra-devnet/README.md): the genesis files differ.

## Files

| File | Bytes | SHA-256 | Contents |
|---|---|---|---|
| `conway-195/state` | 12,657 | `736c7ab5d10f12c977bd58761e013e794a01a422f9062373d84fc41e5b19371c` | The extended ledger state after the block of slot 195, the last Conway block: `[1, [ledger, header, Peras state]]` |
| `conway-195/tables` | 692 | `26342a6f034fc527c725daf43b1804f992232f8d9b34c0a234735cebf07bf1b1` | Its UTxO (`tablesCodecVersion` 1) |
| `conway-195/meta` | 69 | `b7893a9d05d7507f3fef17313701554e9f2d6c23e41798c0013e2148ca6eb0c1` | Its metadata: the `utxohd-mem` backend and the CRC-32 of `state` and `tables` |
| `conway-195/block` | 856 | `b7fc4ac5eb0fe2703e825c7792a8b3194c9dd7681323dc459fa5476a2ec4da47` | The block of slot 195 (`[7, block]`, header hash `4cad04a344c4e41890d257657afb774b811ce13fe9e3b563da7d47cfd1c33bf4`), the bytes the node's ImmutableDB chunk `00003` holds at its secondary-index entry, CRC-32 `fe620eb9` as recorded there: the point an import of the snapshot is checked against |
| `dijkstra-203/state` | 20,877 | `c2d0d6ffaab9278061d27bbd1313488e87fba8a5c394b5cc4ee30f2ac20909d7` | The extended ledger state after the first Dijkstra block, slot 203 |
| `dijkstra-203/tables` | 692 | `26342a6f034fc527c725daf43b1804f992232f8d9b34c0a234735cebf07bf1b1` | Its UTxO, the same as slot 195's |
| `dijkstra-203/meta` | 69 | `05fcfd7cc0c871c52e86af594e98ddee742e0948b5fa74f841c0001c4bee54ca` | Its metadata |
| `genesis/*.json` | | | The genesis files and node configuration of the network |

## What the files show

Checked by `LeiosLayoutSuite` (storage):

- Slot 195 is in the Leios layout in every part the 11.1 layout writes differently: three parts of
  the extended state, the Peras state `82 83 01 80 80 80` (no epoch context, no certificate round),
  bounds of four items, the cumulative transaction bytes as the Shelley ledger state's fourth field,
  a `NewEpochState` of six fields, and the mark, set and go snapshots wrapped, the mark's epoch,
  committee size and key age zero and the set's committee empty, as they are before Dijkstra. It is
  read and written again byte for byte, and the 11.1 layout refuses it at the extended state's
  length.
- Slot 203's ledger telescope and header state are in Dijkstra, whose state this build does not read
  yet (`DijkstraNotYet.LedgerState`, #54): it is refused there. Its Peras state is the same
  constant, and the bounds of its seven past eras, in both telescopes, are read and written byte for
  byte. Its mark snapshot is `[snapshot, 1, 900, 40178]` and its set `[snapshot, []]`; reading them
  is the Dijkstra state's work.

`LeiosLayoutImportSuite` (node) imports `conway-195` under `--reference-build leios-b004fe0c`, exports
it with `state`, `tables` and `meta` byte for byte, and checks that `11.1` refuses the import. The
`leios-state` fuzz target takes both states as seeds.

## Provenance and licence

Generated by this project from a throwaway local cluster of the published Leios testnet image
(input-output-hk/ouroboros-leios, Apache-2.0, not redistributed). The chain, keys and genesis content
exist only for that run; no signing key was copied. The other snapshots of the run and its logs are
not published.
