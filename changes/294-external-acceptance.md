### Changed (external acceptance, #294)

- Permit fully hash-pinned local input inventories for the external acceptance gate, verified before and after read-only execution without bulk archive copies.
- Separate native Haskell index inputs from Scarab boundary snapshots and pin the actual native inventory count instead of a stale fixed count. Preserve all mandatory-case and native-comparison checks.
