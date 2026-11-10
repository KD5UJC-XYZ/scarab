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
  - pool registration and parameter-change proposals, whose state fields are not yet represented (#826);
  - the rules in block application and at epoch boundaries, which still refuse with `DijkstraNotYet.Rules` (#54, T11);
  - the node-to-client queries and failure encoders;
  - a crossing made by the Scala node on a devnet.
- **Persistence:**
  - the Dijkstra ledger state in the Haskell `state` layout (#826);
  - before the crossing block, the mempool, forger and node-to-client paths that tick into Dijkstra still need the stored-output upgrade wired into their reads (#54, T11).
- **The eras before Dijkstra on a Leios network** still follow cardano-node 11.1.3 (ledger f649f975, consensus 82ecba32). The Leios build changes their state layout and some of their rules. A Leios node's database cannot be imported yet (#826, owner decision on #54). Its endorser-block store has no counterpart either (#830).
