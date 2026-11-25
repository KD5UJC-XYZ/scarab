### Changed (block producer identifier, #1348)

- Forged block headers identify Scarab: their minor protocol version is Scarab's block producer
  identifier 73 (no payload) under CIP-0203's layout, where cardano-node writes its own
  release minor; the major is unchanged. An approved Deviation; no ledger or consensus rule reads
  the minor.
- `--producer-id opt-out` forges the opt-out identifier 255 instead; `--producer-id scarab` is the
  default.
