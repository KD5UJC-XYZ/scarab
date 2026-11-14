### Changed (conformance)

- The ledger-rules sidecar (`LedgerRulesMain --sidecar <path>`) is now an object: `selection` names the run's `--select` (`first` or `expected-in-failures`), and `cases` holds what the sidecar held before, one entry per fixture with its `selected` rule. The report keeps the site's schema, which has no field for the selection, so a saved report is identified by the sidecar written beside it. A reader of the earlier sidecar, a bare array, reads `cases`.
