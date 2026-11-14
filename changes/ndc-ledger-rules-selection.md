### Changed (conformance)

- The ledger-rules report of `LedgerRulesMain` names the run's `--select` (`first` or `expected-in-failures`) in a top-level `selection` key. The scenario's schema does not declare the key, but it admits keys that it does not name, and the site keeps them when it reads and publishes a report.
- The sidecar (`--sidecar <path>`) is now an object:
  - `selection` names the same mode;
  - `cases` holds what the sidecar held before, one entry per fixture, each with its `selected` rule.

  A reader of the earlier sidecar, which was a bare array, reads `cases` instead.
