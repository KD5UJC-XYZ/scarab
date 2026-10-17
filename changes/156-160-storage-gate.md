### Fixed (UTxO store enumeration, #156)
- **The RocksDB UTxO store no longer returns a part of the set when its data is damaged.** `all`, `select`, `selectAddresses`,
  `scan` and `size` (and so the `materialize*` helpers and the queries and state digests built on them) fail with the same
  `StorageError.Corrupt` a direct read gives when a stored value does not decode, when a key has a length the layout does not
  have, or when the iterator stops on an error; before, such entries were left out and the result looked complete. A failed
  block checksum is reported as `Corrupt` and other database failures as `Io`. The stamp value must be 8 bytes. Reading keys
  with the batch read also reports a damaged block instead of answering that the keys are absent. The restore of an older
  checkpoint checks the iterator status as well, and treats only the key `0x00` as the stamp key. No stored format changes;
  `UtxoScanBench` shows no measurable difference at 4.5 million entries.

### Changed (build gate, #160)
- **The `blstNative` module and its `BlstSuite` are part of `check`**, and so of CI. A change to the native library selection,
  the start-up checks or the export list of the JNI glue now fails the gate. `UtxoScanBench` can synthesise a store
  (`generate <dir> <count>`).
