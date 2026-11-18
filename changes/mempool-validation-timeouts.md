### Added (mempool, #340)
- **The mempool's validation time limits**, as cardano-node's: `MempoolTimeoutSoft`, `MempoolTimeoutHard` and
  `MempoolTimeoutCapacity` in the configuration, all three or none, default 1 s, 1.5 s and 5 s, so on by default. A
  Conway transaction whose validation takes longer than the soft limit is refused with Conway's `ConwayMempoolFailure`
  and the reference's text, `MempoolTxTooSlow (<duration>) <txid>`, from a client or a peer. The hard limit stops the
  validation, the Plutus evaluator included: a local client's Conway transaction gets that refusal, and otherwise the
  submitter's connection ends while the node goes on. While the held transactions took longer than the capacity to
  validate, together, a new transaction waits for the pool to change. Before Conway there is no such refusal, as in the
  reference. The metrics `cardano_node_metrics_txsMempoolTimeoutSoft_counter` and
  `cardano_node_metrics_txsMempoolTimeoutHard_counter` count them; `scarab_mempool_rejected_total` has the reasons
  `TooSlow` and `TimedOut`.
- **A cancelled admission stops its validation.** The mempool runs it on a thread that cancellation interrupts, and the
  evaluator it is given stops there, with no verdict; the verdicts and budgets of every other evaluation are unchanged.
