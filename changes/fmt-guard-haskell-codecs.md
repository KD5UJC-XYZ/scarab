### Changed (storage format guard)

- `scripts/check_storage_format.py` watches the codecs that write a V10 snapshot's Haskell ledger
  state: the codecs of `haskell/state` that the snapshot reaches, the models runtime's CBOR writer
  and codec base, and the pool relays' encoding. An edit to any of them is a storage format change
  (FMT-1), refused until the owner approves it in a pull request of its own. No persisted byte
  changes.
