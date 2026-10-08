# Fee/size evidence

See [scope, APIs, source pins and reproduction](../../docs/fee-size-predicates.md).

`projection.json` and binary/TSV files are deterministic derived projections
from the already-vendored pinned Haskell sequence and parameter record.
The archived ledger rejection is for value conservation; both fee/size
expectations are positive and source-derived. Synthetic negative boundaries
are tests, not Haskell goldens. `oracle/` retains historical local CLI estimator
observations, including original argv; those paths are not required at runtime.
`upstream/` retains pinned source; `licenses/` retains full attribution.
