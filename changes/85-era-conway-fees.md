### Added (era types, #85)

- The reference's `EraUTxO` class on the era types, with Conway's value consumed, value produced and minimum fee (`getConsumedValue`, `getProducedValue`, `getMinFeeTxUtxo`), and the deposit and refund totals of certificates and bodies they count. They equal the existing rule's on every recorded Conway refusal.
- More of Conway's UTXO predicates run on the era types: the fee and collateral checks (`feesOK` and its parts), the transaction's size and its execution units. Their verdicts equal the existing rule's on every recorded Conway refusal, each of them refused by at least one case. The node still judges with the existing rule, so nothing changes in behaviour.
