### Fixed (ledger failures, #333)

- Report a script integrity hash mismatch from protocol version 11 as `ScriptIntegrityHashMismatch` (Conway UTXOW tag 18: the supplied and recomputed hash, then the bytes the recomputed hash is taken over), and below 11 as `PPViewHashesDontMatch`, as `checkScriptIntegrityHash` does. The verdict is unchanged; only the failure and its wire bytes differ.
