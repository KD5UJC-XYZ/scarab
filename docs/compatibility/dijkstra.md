<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra (next era)

Part of the [compatibility matrix](../compatibility.md); the register entry is `dijkstra` in [compatibility.json](../data/compatibility.json). The reference for Dijkstra is the build of the public Leios testnet (cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, ouroboros-consensus `2416493b`; [decisions](../decisions.md), #711 and #54).

## Status as recorded

partial: the era is named and the node refuses to enter it. Its maximum major protocol version is 11, and the Dijkstra trigger never fires below 12 (owner decision OD-4 on #54). Implemented as the Leios testnet build does it:
- the era, its place in the hard-fork sequence, and the typed `DijkstraNotYet` refusals (#801);
- block, header and transaction decoding (#806);
- the Dijkstra genesis and its protocol parameters (#807);
- ledger rules BBODY, LEDGERS, LEDGER, UTXOW and UTXO (with Conway’s UTXOS), CERTS, CERT, POOL, GOVCERT and GOV, with failure types and reference tags, as entry points only tests reach (#54, T6);
- the translation of a Conway ledger state into Dijkstra (#816);
- the upgrade of stored outputs with native reference scripts after a validated crossing block, preserving outputs created in Dijkstra (#815);
- the other parts of the crossing (#823): the mempool's transaction translation and rematch, the ledger-view forecast across the boundary, and the Praos to Praos2 chain-dependent state.

None of these is reachable from the node's own chain yet.

The continuation is tracked in [#826](https://github.com/KD5UJC-XYZ/scarab/issues/826) for the
reference-build behavior, committee and state lifecycle, and Dijkstra persistence, and in
[#54](https://github.com/KD5UJC-XYZ/scarab/issues/54) for the remaining rules and production
integration. The experimental protocol-version work in
[#788](https://github.com/KD5UJC-XYZ/scarab/pull/788) follows an end-to-end crossing by Scarab;
the reference-only devnet evidence below does not satisfy that condition.

## Evidence

- **Unit tests:** `DijkstraNotYetSuite`, `DijkstraCodecsSuite`, `DijkstraGenesisSuite`, `DijkstraTranslationSuite`, `DijkstraTxTranslationSuite`, `DijkstraCrossingSuite`, `DijkstraStoredOutputsSuite`, `DijkstraRulesSuite` and `DijkstraRulesOracleSuite`.
- **Reference oracles** on the Leios build, each with its recorded cases:
  - `scripts/oracle/dijkstra-genesis` (`fixtures/reference/dijkstra-genesis`);
  - `scripts/oracle/dijkstra-translation` (`fixtures/reference/dijkstra-translation`);
  - `scripts/oracle/dijkstra-crossing` (`fixtures/reference/dijkstra-crossing`);
  - `scripts/oracle/dijkstra-rules` (`fixtures/reference/dijkstra-rules`): individual rules agree on the cases they complete; cases reaching ENTITIES are incomplete gates, not agreement.
- **Stored-output translation:** `DijkstraStoredOutputsSuite` checks the reference translation cases against both memory and RocksDB stores, including bytes, hashes, sizes, totals and spent outputs. The node crossing suite checks entry from earlier eras and that an invalid block never triggers the store pass.
- **The Leios devnet chain `fixtures/dijkstra-devnet`** (Conway to Dijkstra, from the reference-only cluster of #791):
  - its blocks and headers decode;
  - its Conway blocks replay through the node's rules;
  - every Dijkstra header the reference adopted passes `Praos2.validate` against the translated chain-dependent state and the forecast view.

## Explicitly unchecked or unconfirmed

- **Not implemented:**
  - a chain started in Dijkstra from its genesis: the Dijkstra genesis file is read and checked (#807), but the genesis start is refused with `DijkstraNotYet.Genesis`, because it would seat the Leios committee (`GenesisStart`);
  - SUB rules and ENTITIES: LEDGER completes only for phase-2-invalid transactions without sub-transactions whose UTXOW checks are implemented (#54, T7);
  - guards, direct deposits, balance intervals, Plutus scripts, redeemers, datums and forms the Conway decoder refuses: they halt with `DijkstraNotYet.Rules` (#54);
  - the pool-registration transition and its VRF-key occurrence accounting (#826);
  - parameter-change proposals, whose store still holds Conway updates (#826);
  - the rules in block application and at epoch boundaries, which still refuse with `DijkstraNotYet.Rules` (#54, T11);
  - the node-to-client queries and failure encoders;
  - a crossing made by the Scala node on a devnet.
- **Persistence:**
  - the Dijkstra ledger state in the Haskell `state` layout (#826);
  - before the crossing block, the mempool, forger and node-to-client paths that tick into Dijkstra still need the stored-output upgrade wired into their reads (#54, T11).
- **The eras before Dijkstra on a Leios network:** state snapshots through Conway can be imported and exported under the selected Leios build ([layout support](persistence-leios-state-layout.md)). Following that build remains refused; the [selection scope](persistence-leios-state-layout.md#explicitly-unchecked-or-unconfirmed) distinguishes implemented rule selection from the remaining lifecycle work (#826). Importing Dijkstra state remains unimplemented (#826, owner decision on #54); the endorser-block store has no counterpart either (#830).
