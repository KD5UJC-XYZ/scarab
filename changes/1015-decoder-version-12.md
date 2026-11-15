### Fixed (ledger, #1015)

- Mirror the remaining version-12 record, bounded-rational and auxiliary-script branches when reading Shelley-to-Conway parts at an explicit decoder version. Each era's default decoding and persisted formats remain unchanged.
- Add boundary regressions and an explicit-version block fuzz target.
