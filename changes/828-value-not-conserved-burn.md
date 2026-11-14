### Fixed (ledger, #828)

- `ValueNotConservedUTxO` of a transaction that burns tokens reports the minted tokens as consumed and
  the burned ones as produced, as the reference does (`getConsumedMaryValue`, `burnedMultiAssets`);
  the signed mint field was added to the consumed value. The verdict is unchanged.
