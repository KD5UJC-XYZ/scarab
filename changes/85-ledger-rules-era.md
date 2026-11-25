### Changed (conformance, #85)

- The ledger-rules report of `LedgerRulesMain` judges each fixture by Conway's era rules, as the node judges a submitted transaction: the report's results are unchanged over the pinned corpus.
- In the sidecar, each failure's `rule` is the reference's constructor (for example `StakeKeyRegisteredDELEG`, `BabbageOutputTooSmallUTxO`), and its `detail` text is replaced by `cbor`, the failure's encoding as the reference writes it (hex), or `null` with an `unencodable` reason where the reference has no encoding for it.
