### Added (the Leios build, #54)

- The mempool takes Dijkstra transactions under the Leios build. It reads a submission once, in its
  mempool form. It checks the first measure (`maxTxSize`, `maxTxExUnits`) and the second
  (`maxRefScriptSizePerTx`), each with Dijkstra's failure. It then applies Dijkstra's MEMPOOL at
  `mkMempoolEnv` of the slot after the tip, and a peer's transaction gets `applyAlonzoBasedTx`'s
  flag retry.
- Revalidation at a new tip reapplies each held transaction on the annotation it was validated with,
  or validates it again in full when the major protocol version changed (`reapplyValidatedTx`).
  Across the crossing, a held Conway transaction is redecoded into Dijkstra.
- The pool's capacity is a ranking block and one endorser block's reservation. Parameters whose
  endorser block the LeiosFetch codec cannot carry stop the node when the pool first learns them
  (`guardLeiosWireLimit`).
- LocalTxSubmission and TxSubmission2 take Dijkstra transactions, and LocalTxMonitor lists them in
  era 7. A refusal under a Dijkstra ledger has no reply bytes yet. The forger takes its ranking
  block's and its endorser block's transactions from the pool's snapshot of the slot
  (`getSnapshotFor`), and withdraws those the rules have no verdict on.
- A transaction proposing an update of the parameters Dijkstra adds (keys 34 to 56) is not held, and
  no peer is charged for it: the state's record cannot hold such a proposal until #826.
- The release builds are unchanged: they do not read a Dijkstra transaction, and keep no Dijkstra
  ledger.
