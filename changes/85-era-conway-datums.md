### Added (era types, #85)

- The datums and the redeemers of a Conway transaction are checked on the era types (`AlonzoEraUTxO`'s supplemental datums, `missingRequiredDatums` and `hasExactSetOfRedeemers`). Their verdicts equal the existing rule's on every recorded Conway refusal, and where the reference refused a transaction for an unallowed supplemental datum alone, the refusal is the reference's byte for byte; the node still judges with the existing rule, so nothing changes in behaviour.
