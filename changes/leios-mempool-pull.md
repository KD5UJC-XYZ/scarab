### Fixed (Leios fetch latency, #54)

- Take an endorser block's transactions from the node's mempool when its body arrives, as the reference
  does (`getLeiosTxIndex`, `mkMempoolPull`): the mempool keeps its Dijkstra transactions by the hash an
  endorser block lists them by, only the transactions it misses are fetched, and a closure it holds whole
  is acquired with its body. Before, every closure was fetched from the peer in the fetch decision's next
  round, half a second later, so the node's votes on the blocks it received came too late.
