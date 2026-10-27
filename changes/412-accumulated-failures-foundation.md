### Changed (rule judgment, #412)

- The rule interpreter can record predicate failures and continue, as the reference's small-steps
  interpreter does: a `Judge` capability (a cats-mtl `Tell` with the failing flag and sub-rule
  nests), its interpretation in `RuleT` under an `Accumulate` or `FirstFailure` policy, and the
  reference's order of the reported failures. A refused transaction (`LedgerFailure.Rejected`)
  now holds a non-empty list of predicate failures, and the defects that leave no verdict are a
  separate `LedgerDefect` type. Every rule still reports its first failure, so verdicts and
  refusals are unchanged.
