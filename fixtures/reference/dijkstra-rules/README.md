<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra rules: BBODY, CERTS and GOV

48 cases measured on 2026-11-08 against the unchanged Haskell ledger of the public Leios testnet build
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
| `bbody` (`Dijkstra/Rules/Bbody.hs`) | the default ledger state, a chain account of zero | the eight Dijkstra blocks of the [devnet chain](../../dijkstra-devnet/README.md) as they are; the first with protocol major 13, with no execution units or reference-script bytes allowed per block, and with another body hash in its header |
| `certs` (Conway's CERTS with Dijkstra's CERT, POOL and GOVCERT) | the default certificate state | stake registration, deregistration and delegation, DRep and committee certificates, a pool retirement, each accepted or refused, and four certificates whose failures interleave |
| `gov` (`Dijkstra/Rules/Gov.hs`, Conway's transition) | CERTS of a stake and a DRep registration first, then the default store of proposals | proposals of every action but a parameter change, accepted or refused (deposit, return account, network, treasury withdrawals, hard-fork successor, committee update), and votes by unknown and known voters |

The block of the devnet with a transaction (`bbody/devnet-37`) is refused by the oracle only because its
default ledger state has no UTxO and its parameters are otherwise empty (the reference cluster accepted
it); it is kept as a case of the rule that the node's rules halt on (ENTITIES), not as a reference verdict
on the block.

`DijkstraRulesOracleSuite` (node) compares the node's rules with every case they complete. Scope: each rule
on its own. Not LEDGER, UTXOW, UTXO, ENTITIES or the SUB rules, and not a parameter-change proposal or a
pool registration, which the node's rules halt on.
