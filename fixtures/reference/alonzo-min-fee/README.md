# Alonzo minimum fee: the block of cardano-node issue 4228

Status: Current. Regression for the minimum fee of Alonzo (cardano-node issue 4228).

`hex-block-node-issue-4228.cbor` is the upstream golden file
`eras/alonzo/test-suite/golden/hex-block-node-issue-4228.cbor` of cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`, byte for byte (Apache-2.0; SHA-256 in
`provenance.json`): the hex of an Alonzo block, the block alone without the era tag BlockFetch puts
before it, posted on [cardano-node issue
4228](https://github.com/input-output-hk/cardano-node/issues/4228#issuecomment-1195707491). Its
header is block 7,547,634 at slot 67,283,737. The upstream golden "Alonzo Block"
(`eras/alonzo/test-suite/test/Test/Cardano/Ledger/Alonzo/Golden.hs:230-268`) records that the first
of its 18 transactions is invalid with `FeeTooSmallUTxO (Coin 1006053) (Coin 1001829)` and that
"Nodes that had the bug determined the minimum fee to be 1001829", and requires `alonzoMinFeeTx` of
that transaction to be 1,006,053 lovelace under `emptyPParams` with a fee per byte of 44, a fixed
fee of 155,381 and execution prices of 0.0577 (memory) and 0.0000721 (steps). The changelog entry of
the fix reads "The Alonzo UTxO rule was previously using the incorrect minfee function (from
Shelley)" (`CHANGELOG.md:893-895`, cardano-ledger pull request 2936).

The transaction (id `5fde6536…5c97`) carries a fee of 1,001,829. Its size for the fee is 5,238 bytes:
the list header, the body (432 bytes), the witness set (4,804 bytes) and a null for the missing
auxiliary data (`sizeAlonzoTxF` and `toCBORForSizeComputation`,
`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:324-332, 432-443`). Its one redeemer budgets
7,000,000 memory units and 3,000,000,000 steps, whose price is the ceiling of 403,900 + 216,300 =
620,200 (`txscriptfee`, `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs:185-190`).
So the minimum is 44 × 5,238 + 155,381 + 620,200 = 1,006,053.

`scripts/oracle/alonzo-min-fee/run.py --check` computes it with the released ledger of the 11.1.3
build (cardano-ledger-alonzo 1.16.0.0) and requires the `reference` record of `provenance.json`: the
golden's own definitions of the parameters, the block body decoded at `eraProtVerHigh @AlonzoEra`.
The golden decodes the whole block with its `BHeader`; that package is not in the oracle's
environment, so the harness skips the header as one CBOR term and decodes the body after it with the
same decoder at the same version.

`AlonzoMinFeeGoldenSuite` reads the block with Scarab's block decoder (`Block.decodeShelley`, after
the era tag 5 it adds), reads the first transaction with `Tx.fromBlock`, requires its id, fee, size
and execution units, and requires the UTXO checks of Alonzo (`UtxoChecks.of`), which compute the
minimum fee, to refuse it with exactly `FeeTooSmall(Mismatch(1001829, 1006053))`.

Scope: the parameters are the three the golden sets on `emptyPParams`; the suite sets every other
field to zero as `emptyPParams` does, and the minimum fee reads none of them. Only the fee failure is
compared: the transaction's inputs are not in the empty UTxO the suite gives, so the other checks of
the rule are not the golden's and are not asserted. The block's header is decoded but not
validated, and its other 17 transactions are only counted.
