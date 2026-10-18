### Added (certified bootstrap, #27)

- `import-mithril` verifies a pinned official Mithril certificate chain and the complete immutable database, authenticates the ancillary ledger with a separately supplied operator key, then atomically publishes a fresh Scarab database. Downloads and isolated cached inputs share typed failure, network/artifact binding, read-only overlap refusal before ownership, timeout and cancellation checks.
- A reproducible Docker build recipe pins the official verifier and its small receipt/manifest-preservation patch. V5 snapshots and stored CBOR remain unchanged.
