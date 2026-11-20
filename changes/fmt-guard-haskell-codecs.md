### Changed (storage format guard)

- `scripts/check_storage_format.py` watches what writes or reads a V10 snapshot's Haskell ledger
  state: the codecs of `haskell/state` that the snapshot reaches and the models runtime's CBOR
  writer and codec base, whole; the definitions of other packages on that path as members (the
  pool relays, a pool's future parameters, a stored parameter update, the orders of maps and
  sets, the era indices, the layout of each build, the CBOR decoder); and the hashes of golden
  V10 bodies in `fixtures/snapshot-v10-bodies`, which `V10BodyGoldenSuite` reads and writes again.
  An edit to any of them is a storage format change (FMT-1), refused until the owner approves it
  in a pull request of its own. A comment, inline or on its own line, no longer fires the guard.
  No persisted byte changes.
