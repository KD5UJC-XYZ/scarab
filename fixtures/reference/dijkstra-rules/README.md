<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra rules: BBODY, UTXOW, ENTITIES, CERTS and GOV

117 cases measured against the unchanged Haskell ledger of the public Leios testnet build (cardano-ledger
`9df9f98c`, cardano-ledger-dijkstra 0.5.0.0, which cardano-node `b004fe0c` of release `rc-2026w44` and
`b9c1dbea` of `rc-2026w47` both pin): 68 on 2026-11-08, for
[#54](https://github.com/KD5UJC-XYZ/scarab/issues/54), the two pools' votes on parameter changes on
2026-11-21, and 47 more on 2026-11-24, for [#85](https://github.com/KD5UJC-XYZ/scarab/issues/85), each
re-recording leaving the earlier cases unchanged and appending the new ones. The oracle source and
[recipe](../../../scripts/oracle/dijkstra-rules/README.md) are committed; `provenance.json` records the
image identity and the program's hash. Raw run artifacts are not published.

`cases.jsonl` holds one case per line: the rule (`kind`), the parameters it reads and its signal, and the
reference's verdict: `accepted`, or each predicate failure of the refusal as its `EncCBOR` at version 12
(`failures`) and as `show` renders it, in the order `applySTS` returns them. Each rule is applied on its own
(`Control/State/Transition/Extended.hs`, `applySTS`, every check run), with network `Testnet`.

| kind | state | cases |
|---|---|---|
| `bbody` (`Dijkstra/Rules/Bbody.hs`) | the default ledger state, a chain account of zero | the eight Dijkstra blocks of the [devnet chain](../../dijkstra-devnet/README.md) as they are; the block with the transaction again on a UTxO set of the genesis output it spends, and with that transaction flagged phase-2-invalid and the header's body hash made anew; the first with protocol major 13, with no execution units or reference-script bytes allowed per block, and with another body hash in its header, and with both another body size and another body hash |
| `utxow` (`Dijkstra/Rules/Utxow.hs`, with UTXO and Conway's UTXOS) | the UTxO set of the case, the default certificate state, the transaction annotated as `mkDijkstraStAnnTopTx` does, slot 225 | the devnet transaction on the genesis output it spends (`extraConfig.initialFunds` of the Shelley genesis); on no UTxO; with a higher minimum fee, a lower maximum transaction or value size, a higher cost per UTxO byte, no execution units; without its witnesses; flagged phase-2-invalid; and transactions spending an input locked by a native script (all of none, a signature, a time start), with the script, without it, and with an extraneous one; a deregistration of the script's stake credential whose declared refund is not the deposit held, balanced on either (CERTS of the registration first); a burn of more tokens than the input holds; and a collateral return to a pointer address (`PtrPresentInCollateralReturn`) |
| `certs` (Conway's CERTS with Dijkstra's CERT, POOL and GOVCERT) | the default certificate state | stake registration, deregistration and delegation, DRep and committee certificates, a pool retirement, each accepted or refused, and four certificates whose failures interleave; a pool's registration with a voting key and its proof of possession (a devnet pool's key and proof from the [Leios genesis corpus](../leios-genesis/README.md)), and with another key's proof, refused as `BlsKeyInvalidProofOfPossession`; a registration below the minimum cost, with a mainnet reward account, with a VRF key already registered, and a retirement past `eMax` and in the current epoch |
| `entities` (`Dijkstra/Rules/Entities.hs`, with Conway's CERTS) | CERTS of a stake registration first, whose accounts are the original ones too, the transaction annotated as for UTXOW | transactions spending an input locked by a native script whose body withdraws from, deposits directly to or bounds the balance (current or original) of the registered account, an account on mainnet or an unregistered one, accepted or refused, and a deregistration whose refund is not the deposit held |
| `gov` (`Dijkstra/Rules/Gov.hs`, Conway's transition) | CERTS of a stake and a DRep registration first, then the default store of proposals | proposals of every action but a parameter change, accepted or refused (deposit, return account, network, treasury withdrawals, hard-fork successor, committee update), and votes by unknown and known voters; and, after a pool's registration too, a parameter change of key 38 (the pledge leverage, Technical/NoStakePool) with the pool's vote on it, refused as `DisallowedVoters`, and one of key 47 (the endorser block's execution units, Network/Security) with the pool's vote, accepted; the same vote on a change of every other key Dijkstra adds (34 to 56, the updates of the [parameter corpus](../dijkstra-pparams/README.md)), refused on key 39 alone; the empty update and a zero `maxBBSize`, refused by `ppuWellFormed` as `MalformedProposal`; at protocol 9, a DRep's vote on a parameter change and a proposal of treasury withdrawals, refused for the bootstrap phase; and the votes of three voters on four actions that do not exist |

The parameters are the devnet's (its genesis files), those the rules read. The block of the devnet with a
transaction is refused by the oracle on the default ledger state (`bbody/devnet-37`: the set holds no
output to spend), and accepted on the genesis output it spends (`bbody/devnet-37-genesis-utxo`), as the
reference cluster accepted it; the node's rules halt on both at ENTITIES. Flagged phase-2-invalid
(`bbody/devnet-37-invalid-flag`), it is refused through LEDGERS, LEDGER, UTXOW, UTXO and UTXOS, which the
node's rules judge in full.

`DijkstraRulesOracleSuite` (node) compares the node's era rules with every case, their failures written by
the test-side encoder of Dijkstra's era failures, and the flat rules with the 70 cases recorded before it,
which halt on a pool's registration. Scope: each rule on its own. Not LEDGER on its own or the SUB
rules; not ENTITIES of sub-transactions or in Plutus legacy mode; not UTXOW of a transaction with Plutus
scripts, redeemers, datums, guards, sub-transactions, direct deposits or balance intervals; not a vote on
an expired action, or by a committee member.
