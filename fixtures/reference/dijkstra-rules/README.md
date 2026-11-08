<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra rules: BBODY, UTXOW, CERTS and GOV

58 cases measured on 2026-11-08 against the unchanged Haskell ledger of the public Leios testnet build
(cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, cardano-ledger-dijkstra 0.5.0.0), for
[#54](https://github.com/KD5UJC-XYZ/scarab/issues/54). The oracle source and
[recipe](../../../scripts/oracle/dijkstra-rules/README.md) are committed; `provenance.json` records the
image identity and the program's hash. Raw run artifacts are not published.

`cases.jsonl` holds one case per line: the rule (`kind`), the parameters it reads and its signal, and the
reference's verdict: `accepted`, or each predicate failure of the refusal as its `EncCBOR` at version 12
(`failures`) and as `show` renders it, in the order `applySTS` returns them. Each rule is applied on its own
(`Control/State/Transition/Extended.hs`, `applySTS`, every check run), with network `Testnet`.

| kind | state | cases |
|---|---|---|
| `bbody` (`Dijkstra/Rules/Bbody.hs`) | the default ledger state, a chain account of zero | the eight Dijkstra blocks of the [devnet chain](../../dijkstra-devnet/README.md) as they are; the block with the transaction again on a UTxO set of the genesis output it spends; the first with protocol major 13, with no execution units or reference-script bytes allowed per block, and with another body hash in its header |
| `utxow` (`Dijkstra/Rules/Utxow.hs`, with UTXO and Conway's UTXOS) | the UTxO set of the case, the default certificate state, the transaction annotated as `mkDijkstraStAnnTopTx` does, slot 225 | the devnet transaction on the genesis output it spends (`extraConfig.initialFunds` of the Shelley genesis); on no UTxO; with a higher minimum fee, a lower maximum transaction or value size, a higher cost per UTxO byte, no execution units; without its witnesses; flagged phase-2-invalid |
| `certs` (Conway's CERTS with Dijkstra's CERT, POOL and GOVCERT) | the default certificate state | stake registration, deregistration and delegation, DRep and committee certificates, a pool retirement, each accepted or refused, and four certificates whose failures interleave |
| `gov` (`Dijkstra/Rules/Gov.hs`, Conway's transition) | CERTS of a stake and a DRep registration first, then the default store of proposals | proposals of every action but a parameter change, accepted or refused (deposit, return account, network, treasury withdrawals, hard-fork successor, committee update), and votes by unknown and known voters |

The parameters are the devnet's (its genesis files), those the rules read. The block of the devnet with a
transaction is refused by the oracle on the default ledger state (`bbody/devnet-37`: the set holds no
output to spend), and accepted on the genesis output it spends (`bbody/devnet-37-genesis-utxo`), as the
reference cluster accepted it; the node's rules halt on both at ENTITIES.

`DijkstraRulesOracleSuite` (node) compares the node's rules with every case they complete. Scope: each rule
on its own. Not LEDGER on its own, ENTITIES or the SUB rules; not UTXOW of a transaction with Plutus
scripts, redeemers, datums, guards, sub-transactions, direct deposits or balance intervals; not a
parameter-change proposal or a pool registration: the node's rules halt on all of them.
