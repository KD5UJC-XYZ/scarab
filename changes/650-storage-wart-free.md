### Fixed (storage, #650)

- A UTxO store whose rebuild from a checkpoint failed after its database was closed now fails every operation with `StorageError.UtxoStoreClosed`. It used to report an I/O error wrapping a `NullPointerException`. A later restore or reset opens it again; before, those failed the same way. A store that has been released refuses a restore or reset with the same error, before it touches the directory.

### Changed (storage, #650)

- When an operation on a RocksDB handle or a file fails and closing the handle then fails too, the operation's error is the one reported, with the close's attached. Before, the close's error replaced it. A handle that leaked when a later open in the same step failed is now closed.
