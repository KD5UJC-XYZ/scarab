# Pre-Conway instant stake: native Word64 boundaries

Thirty-two cases measured on 2026-10-22 against the unchanged released Haskell ledger libraries used
by node 11.1.3 (issue [#299](https://github.com/KD5UJC-XYZ/scarab/issues/299)). `results.json`
retains each input case (the held stake, the produced outputs and the consumed outputs, with
original address bytes) and the instant stake that the actual `addShelleyInstantStake` followed by
`deleteShelleyInstantStake` returned, in the sequence of `updateUTxOAndInstantStake`
(`Shelley/Rules/Utxo.hs:613-624`). `provenance.json` records the pins, hashes and images, and the
[offline recipe](../../../scripts/oracle/instant-stake/README.md) reproduces the file.

The functions are the released `Cardano.Ledger.Shelley.State.Stake` exports, not a copy of their
arithmetic: the released source file matches pinned cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5` byte for byte (`Stake.hs:108-145`). Every case runs
under the Shelley and the Babbage `TxOut`, and the two results agree for all of them. The same
sixteen cases run with the stake held under a base-address credential and under a pointer.

Coverage: deletion underflow modulo 2^64, addition overflow to exactly zero and past zero,
deletion to exactly zero, insertion of the output amount into a missing entry for addition and
for deletion, zero amounts, zero removal followed by deletion and by reinsertion, deletion in
`TxIn` map order against the order a body lists its inputs, outputs added before consumed outputs
are deleted, and an enterprise address and the other map leaving the entry unchanged. Six
`toCompact` results (-1, 0, 1, 2^64-1, 2^64, 2^64+1) record the released bounds of an output amount.

`ShelleyInstantStakeReferenceSuite` replays every case through the production updater and through
`ShelleyUtxo.update` with the consumed inputs listed in signed order, and the coin bounds through
`CompactCoin`.

Scope: independent execution of the two released functions on single transactions' output and
input maps. It does not execute the full UTXO transition, the Alonzo/Babbage collateral path
(which calls the same two functions on the collateral inputs and return, `Babbage/Rules/Utxo.hs:477-486`),
or the Conway instant stake. `applyUTxOShelleyInstantStake` is not exported by the package; it is
exercised through both exports. No public-network connection, node, or key is involved.
