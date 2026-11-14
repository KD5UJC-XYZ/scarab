### Fixed (mempool, #841)

- Measure a transaction's reference scripts over the union of its inputs and reference inputs, as the reference's `txMeasureRefScripts` does. An output that is both spent and a reference input, which protocol version 11 allows, now counts once in the per-transaction limit, in the capacity and in what LocalTxMonitor reports.
