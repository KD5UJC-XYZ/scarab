### Fixed (devnet ledger comparisons, #44)

- Compare every pending cost-model language and ordered parameter value across current and future protocol updates, retaining both original encodings as evidence. Equivalent definite and indefinite CBOR arrays no longer produce false ledger mismatches; malformed comparison fields and comparison errors fail the scenario gates.
