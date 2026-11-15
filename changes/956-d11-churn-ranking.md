### Fixed (peer selection, #956)

- The governor ranks hot peers for demotion by the churn mode, as cardano-node 11.1.3 does
  (`simpleChurnModePeerSelectionPolicy`): by the headers and blocks a peer was first with in Praos
  deadline mode, and by the bytes of the blocks it was first with in Praos bulk sync and in Genesis
  while the ledger is too old. It used to rank by headers and blocks in every mode. The churn
  governor writes the mode before each set of targets it asks for and at the start of each round,
  so a change of the fetch mode while it sleeps reaches the ranking with its next round, and the
  round's `peerSelectionChurn` line shows `Genesis` as its `mode` in that mode.
- A block fetched counts, on the peer metrics board, the size its header announces
  (`estimateBlockSize`), as in the reference, not the bytes received.
