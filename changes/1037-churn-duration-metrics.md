### Added (metrics, #1037)

- The reference's churn duration gauges, `cardano_node_metrics_peerSelection_churn<Action>_duration_real`, one for
  each of the twelve steps of a churn round (`DecreasedActivePeers`, `IncreasedKnownBigLedgerPeers`, ...): the
  seconds the last step of that kind took to meet its targets, set as cardano-node 11.1.3 sets them on
  `TraceChurnAction`. A step that times out sets nothing, so a gauge appears when its step first meets its
  targets. The names are as a running cardano-node 11.1.3 exports them, without a dot after `churn`. The
  `governor` devnet scenario checks each gauge against the durations in the node's log.
- running.md "Metrics" lists the cardano-node 11.1.3 metrics Scarab does not export, from an inventory at the
  pin (#1037). The seventeen `txSubmission_*_int` gauges, among them the wait counters, are exported by the
  reference only with `TxSubmissionLogicV2`, which Scarab does not implement, so they are not mirrored.
