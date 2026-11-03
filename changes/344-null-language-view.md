### Fixed (ledger, #344)

- The script integrity hash has a language view for every Plutus language a transaction needs, as the reference's `getLanguageView` gives it: a language whose cost model the protocol parameters do not have is written with `null` for the model (`41 f6` for PlutusV1, `f6` for PlutusV2 and V3) instead of being left out. A transaction hashed without such a language is now refused with `PPViewHashesDontMatch`; one hashed with the null view passes UTXOW and fails collection with `NoCostModel`, as on the reference.
