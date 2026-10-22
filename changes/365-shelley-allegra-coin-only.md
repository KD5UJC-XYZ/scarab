### Fixed (Shelley and Allegra outputs, #365)

- A Shelley or Allegra transaction output whose value carries a multi-asset part no longer decodes: the era's value is a `Coin` (`ShelleyTxOut`), so the output, and the body that holds it, are refused as the reference refuses them. Mary onward and the stored UTxO are unchanged.
