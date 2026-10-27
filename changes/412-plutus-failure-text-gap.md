### Changed (verification, #412, #491)

- `ReferenceRefusalSuite` records the one known gap of the reference-refusal corpus: the `PlutusFailure` text of the two `utxos-script-fails-flag-true` cases, which needs plutus-ledger-api's script-context printer and plutus-core's evaluation-error text (#491). Everything else in those refusals is still compared byte for byte; the cases are reported as canceled (incomplete) and fail once the text matches the reference, so that the entry is removed.
