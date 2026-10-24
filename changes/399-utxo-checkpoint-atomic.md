### Fixed (memory UTxO store, #399)

- Write the memory UTxO store's checkpoint to a temporary file, fsync it, rename it over `utxo.bin` and fsync the directory, as the other persisted files are, so a crash leaves the previous checkpoint or the new one.
