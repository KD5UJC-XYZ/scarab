### Changed (mempool, #465)

- An exception the transaction decoder throws on a submitted transaction (`Rejection.InputDefect`) is no refusal of the transaction and no longer a node defect: a local client's connection ends with a `DecoderDefect` of LocalTxSubmission, with no answer, and a peer's TxSubmission2 session ends the same way, the node going on. In the reference the transaction is decoded by the mini-protocol's codec, where an exception ends the connection (ouroboros-network `RethrowPolicy.hs`, `ShutdownPeer`). `scarab_mempool_rejected_total` counts it under `reason="InputDefect"`.
