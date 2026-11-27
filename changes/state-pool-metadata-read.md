### Fixed (storage: a registered pool's metadata, #1511)
- **A registered pool's metadata in the ledger state is read as the reference reads it.** The state codecs kept a
  pool's `spsMetadata` as the bytes it came in without reading it, so a one-item record (nightly fuzz, `leios-state`
  seed 20784481) was accepted and then refused by the writer. It is now read with `DecCBOR PoolMetadata`, a record of a
  URL bounded by the version and a hash, in the 11.1 and Leios layouts, as the future parameters' metadata already is.
  This is a storage format change of the reader only (FMT-1, approved 2026-11-27): the writer is unchanged, and the
  node's own states, which it always writes as `[url, hash]`, read as before. A state holding a genesis pool whose
  metadata URL is over 128 bytes is now refused on reading, as the reference refuses it.
