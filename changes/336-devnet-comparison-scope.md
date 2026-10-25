### Fixed (devnet comparison exemptions, #336)

- The `ntc` scenario compares the validity-interval refusals exactly, raw bytes and text, without masking the current slot. Each refusal case is now submitted to both nodes while they share one tip.
- `withdrawal-network-flag-true` is the one `ntc` refusal compared by its first failure. It is now a declared divergence tied to #412: the reference must list exactly two failures and the Scala node one, and the case fails once the whole refusals agree. The summary reports exact cases and tracked divergences separately.
- The `e2e` ledger comparisons are labelled as projections: `ledgerComparison.scope` lists what they compare and what they leave out (#433), and each result names the Scala fields with no reference counterpart. The Conway comparison now includes datums and reference-script hashes, which it used to drop.

### Fixed (silent skips and loose matching, #337)

- A set but invalid `SCARAB_NATIVE_K1_DB` or `SCARAB_K1_DB` path fails its suite instead of skipping it. A missing committed fixture directory also fails, instead of leaving the suite with no cases.
- Expected `e2e` refusals match the expected predicates as whole constructors. Each refusal records whether the reference's failure list is exactly the expected one or contains others too.
- The coverage register gains a `bounded` status. It marks an assertion that holds but proves less than its scope names, and the entry must name the issue that owns the stronger contract.
