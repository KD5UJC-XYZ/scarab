### Fixed (ledger snapshots, #838)

- `export-ledger-snapshot` no longer refuses a snapshot of a block without transactions with `Unsettled`.
  The UTxO store had stayed at the last block that changed an entry, so every such snapshot was refused,
  among them the shutdown snapshot of a chain whose last blocks were empty. The store now moves to the
  block of the state it is brought to, and the snapshot is settled when written. Restoring these
  snapshots was already correct. A snapshot that an earlier build wrote at such a block still refuses to
  export; the next snapshot of the node replaces it.

### Fixed (oracle, #838)

- The devnet oracle finds the reference node of a plain `devnet.py up` cluster, which `cardano-testnet`
  starts as `/opt/reference/bin/cardano-node run`, to pause it while the follower's tip settles.

### Added (oracle, #838)

- `oracle_compare.py reference --network-none --full-ledger-validation` runs each `db-analyser` with
  Docker networking disabled and with full ledger validation, as the reference-loading acceptance does.
