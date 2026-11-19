<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra (next era)

Part of the [compatibility matrix](../compatibility.md); the register entry is `dijkstra` in [compatibility.json](../data/compatibility.json). The reference for Dijkstra is the build of the public Leios testnet (cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, ouroboros-consensus `2416493b`; [decisions](../decisions.md), #711 and #54).

## Status as recorded

partial: the era is named and the node refuses to enter it. Its maximum major protocol version is 11, and the Dijkstra trigger never fires below 12 (owner decision OD-4 on #54). Implemented as the Leios testnet build does it:
- the era, its place in the hard-fork sequence, and the typed `DijkstraNotYet` refusals (#801);
- block, header and transaction decoding (#806);
- the Dijkstra genesis and its protocol parameters (#807);
- ledger rules BBODY, LEDGERS, LEDGER, UTXOW and UTXO (with Conway’s UTXOS), CERTS, CERT, POOL, GOVCERT and GOV, with failure types and reference tags, as entry points only tests reach (#54, T6);
- UTXOW's check of the failing native scripts in Dijkstra's form, as `evalDijkstraNativeScript` evaluates them with the body's guards: a reference script with a guard, which the stored output keeps without a time-lock tree, is read again from the output's bytes and never passes unevaluated (#1126);
- the translation of a Conway ledger state into Dijkstra (#816);
- its transactions on the era types, of both levels, read from what the block decoder reads and equal to it on every Dijkstra transaction the repository holds, each pool registration with its parameters at the state's types and its voting key (`sppBlsKey`; #85);
- the upgrade of stored outputs with native reference scripts after a validated crossing block, preserving outputs created in Dijkstra (#815);
- the other parts of the crossing (#823): the mempool's transaction translation and rematch, the ledger-view forecast across the boundary, and the Praos to Praos2 chain-dependent state;
- the header gate's rules for a Dijkstra header under `--reference-build leios-b004fe0c` (#54): Dijkstra's own ledger-view forecast or the one across the boundary, the chain-dependent state translated into Praos2's, and Praos2's envelope and protocol rules, as the build's ChainSync client runs them; the release builds keep refusing a Dijkstra header as of the wrong era.

Under the Leios build the node's maximum major version is the configured one (12 on the testnet) and its era history enters Dijkstra, from the genesis on the public testnet, where the header gate validates the chain's headers (`DijkstraHeaderGateSuite`); `follow` still refuses that build and no Dijkstra block is applied (`DijkstraNotYet.Rules`). The release builds' maximum stays 11, which never enters Dijkstra.

The continuation is tracked in [#826](https://github.com/KD5UJC-XYZ/scarab/issues/826) for the
reference-build behavior, committee and state lifecycle, and Dijkstra persistence, and in
[#54](https://github.com/KD5UJC-XYZ/scarab/issues/54) for the remaining rules and production
integration. The experimental protocol-version work in
[#788](https://github.com/KD5UJC-XYZ/scarab/pull/788) follows an end-to-end crossing by Scarab;
the reference-only devnet evidence below does not satisfy that condition.

## Evidence

- **Unit tests:** `DijkstraNotYetSuite`, `DijkstraCodecsSuite`, `DijkstraGenesisSuite`, `DijkstraTranslationSuite`, `DijkstraTxTranslationSuite`, `DijkstraCrossingSuite`, `DijkstraStoredOutputsSuite`, `DijkstraStateTranslationSuite`, `DijkstraRulesSuite`, `DijkstraRulesOracleSuite`, `DijkstraNativeScriptEvalSuite`, `DijkstraHeaderRulesSuite` and `DijkstraHeaderGateSuite`.
- **Reference oracles** on the Leios build, each with its recorded cases:
  - `scripts/oracle/dijkstra-genesis` (`fixtures/reference/dijkstra-genesis`);
  - `scripts/oracle/dijkstra-translation` (`fixtures/reference/dijkstra-translation`);
  - `scripts/oracle/dijkstra-state-translation` (`fixtures/reference/dijkstra-state-translation`): the ledger's translation of the captured Conway state `fixtures/leios/state/conway-195`, equal byte for byte to `Translate.conwayToDijkstra`'s (`DijkstraStateTranslationSuite`); the consensus parts of the crossing are not compared;
  - `scripts/oracle/dijkstra-crossing` (`fixtures/reference/dijkstra-crossing`);
  - `scripts/oracle/dijkstra-rules` (`fixtures/reference/dijkstra-rules`): individual rules agree on the cases they complete; cases reaching ENTITIES are incomplete gates, not agreement.
- **Stored-output translation:** `DijkstraStoredOutputsSuite` checks the reference translation cases against both memory and RocksDB stores, including bytes, hashes, sizes, totals and spent outputs. The node crossing suite checks entry from earlier eras and that an invalid block never triggers the store pass.
- **The Leios devnet chain `fixtures/dijkstra-devnet`** (Conway to Dijkstra, from the reference-only cluster of #791):
  - its blocks and headers decode;
  - its Conway blocks replay through the node's rules;
  - every Dijkstra header the reference adopted passes `Praos2.validate` against the translated chain-dependent state and the forecast view;
  - and the node's header rules accept them, each read from its ChainSync envelope, with the replayed Conway state as the anchor (`DijkstraHeaderRulesSuite`; a changed KES signature is refused as `InvalidKesSignatureOCERT`).
- **The public Leios testnet:** from its ledger state at slot 2,168,639 (`fixtures/leios/state/testnet-2168639`), the node's header rules accept the eight headers that follow it (`fixtures/leios/testnet/headers-after-2168639.tsv`), the leader check on the stake of that state's 154 pools; a certificate flag set too soon after the announcement is refused as `LeiosCertTooYoung`, before the signatures (`DijkstraHeaderRulesSuite`); the node's canonical header gate validates the same eight headers from that state (`DijkstraHeaderGateSuite`). A local run on a copy of a reference node's database of that chain (2026-11-18, not published) accepted the 275 headers within the forecast range of its ledger snapshot at slot 2,570,380 and stopped at the range, and Praos2's tick and reupdate over the 6,500 headers from there to slot 2,721,550 gave the Praos2 state of the reference's snapshot at that slot, field for field.

## Explicitly unchecked or unconfirmed

- **Not implemented:**
  - SUB rules and ENTITIES: LEDGER completes only for phase-2-invalid transactions without sub-transactions whose UTXOW checks are implemented (#54, T7);
  - guards, direct deposits, balance intervals, Plutus scripts, redeemers, datums and forms the Conway decoder refuses: they halt with `DijkstraNotYet.Rules` (#54);
  - the pool-registration transition and its VRF-key occurrence accounting (#826);
  - parameter-change proposals, whose store still holds Conway updates (#826);
  - the rules in block application, which still refuse with `DijkstraNotYet.Rules` (#54, T11); the epoch boundary is implemented: Dijkstra's EPOCH, SNAP (the Leios committee of `ppLeiosCommitteeSize`, the key age the KES constants give) and POOLREAP (one VRF-key reference released per pool), checked byte for byte by `DijkstraEpochReferenceSuite` against the build's own TICK on both captured Dijkstra states across two boundaries, with and without the reward update, and on states derived from them with retirements and re-registrations (`fixtures/reference/dijkstra-epoch`); a re-registration of a pool that holds a voting key is not held yet (#826);
  - the node-to-client queries and failure encoders;
  - a crossing made by the Scala node on a devnet.
- **Persistence:**
  - the Dijkstra ledger state in the 11.1 line's Haskell `state` layout (`DijkstraNotYet.LedgerState`); the Leios build's is read and written ([layout support](persistence-leios-state-layout.md)), but for a pool's BLS key in its parameters, a proposal's Dijkstra parameter update and a Peras epoch context (#826);
  - before the crossing block, the mempool, forger and node-to-client paths that tick into Dijkstra still need the stored-output upgrade wired into their reads (#54, T11).
- **A Leios network:** state snapshots in every Shelley-based era, Dijkstra among them, can be imported and exported under the selected Leios build ([layout support](persistence-leios-state-layout.md)). A Dijkstra snapshot of a real database cannot be imported yet: its point must be a block of the database, and `import-haskell-db` does not store a Dijkstra block (`DijkstraNotYet.Rules`). Dijkstra's genesis injection is implemented at the ledger level (Conway's injection, the VRF key hashes counted and the Leios committee seated on the initial snapshots, `Dijkstra/Transition.hs:59-101`); under the Leios build the node starts a chain in Dijkstra when its configuration has `ExperimentalHardForksEnabled` and `TestDijkstraHardForkAtEpoch: 0`, as the public testnet's has (its maximum major protocol version is its own, 12, `Cardano/Node.hs:548-549`, ouroboros-consensus 2416493b), and `DijkstraGenesisStartSuite` checks that start, and its UTxO, against the build's own genesis state byte for byte. Following that build remains refused; the [selection scope](persistence-leios-state-layout.md#explicitly-unchecked-or-unconfirmed) distinguishes implemented rule selection from the remaining lifecycle work (#826). The endorser-block store has no counterpart (#830).
