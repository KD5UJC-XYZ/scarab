### Fixed (memory UTxO store, #399)

- The memory UTxO store's checkpoint file `utxo.bin` is now durable (fsync, through `AtomicFile`) before the snapshot that binds it is written. Before, it was not forced to disk, so a crash could leave a snapshot whose checkpoint was torn; the checkpoint digest refused that snapshot at restore and an older one was used. The reference has no such file: its snapshots are directories whose metadata records a checksum.
