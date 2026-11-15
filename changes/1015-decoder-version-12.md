### Fixed (ledger, #1015)

- Mirror the remaining version-12 record, bounded-rational and auxiliary-script branches when reading Shelley-to-Conway parts at an explicit decoder version. Each era's default decoding and persisted formats remain unchanged.
- Add boundary regressions and an explicit-version block fuzz target.
- Select auxiliary-data forms by era: Allegra and Mary retain strict lists and refuse tag 259 and Plutus V4 even at decoder version 12; the version-12 extensions apply only to the Alonzo family.
