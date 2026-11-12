### Changed (storage, #870)

- A graceful stop waits for a ledger snapshot still being written in the background by its completion signal, instead of checking every 20 ms; `ChainStorage.Recovered.snapshotsWritten` completes once no snapshot is being written, and `ChainStorageSuite` waits on it instead of sleeping.
