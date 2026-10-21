### Changed (external acceptance, #294)

- Permit fully hash-pinned local input inventories for the external acceptance gate, verified before and after read-only execution without bulk archive copies.
- Separate native Haskell index inputs from Scarab boundary snapshots and pin the actual native inventory count instead of a stale fixed count. Preserve all mandatory-case and native-comparison checks.
- Run all twelve mandatory external cases and 66 declared controls against pinned originals; retain exact-source/runtime evidence. Correct fork heap, working-directory and read-only export-lock handling; keep maintained identity metadata current.
