### Fixed (difference derivation, #206)

- An enum case keeps its derived `Diff` instance: it is made once on first use (a `Memo` captured by the derivation) instead of being derived on every same-case comparison, which built a fresh instance — its labels and closures — per comparison. Differences, paths and field names are unchanged.
