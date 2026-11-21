### Added (the Leios build, #54)

- The Dijkstra forger takes a ranking block's transactions as the reference does: from the pool's
  snapshot of the slot, the longest prefix, oldest first, that fits the block's capacity in every
  component, which is Conway's measure with Dijkstra's per-block reference-script limit read from the
  parameters. A block that certifies an endorser block would carry no transactions. A Dijkstra
  transaction's measure, the pool's Dijkstra capacity with its endorser-block reservation, and the
  LeiosFetch wire guard on that reservation are mirrored too. Built on the testnet's block 64,853,
  the forged body of its 40 transactions is the recorded body, byte for byte, and the forged block
  passes the node's Praos2 header rules and block checks. The pool does not admit Dijkstra
  transactions yet, so the node still forges its ranking blocks without transactions. Endorser
  blocks are not forged yet.
