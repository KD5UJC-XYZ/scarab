### Added (the Leios build, #54)

- The Dijkstra forger makes the endorser block of the transactions its ranking block leaves, as the
  reference does: the run after the block's prefix that fits the endorser block's capacity in every
  component, each transaction charged its closure measure and the bytes of its reference, within the
  references capacity of the parameters less the body's widest head. The block's hash and its length
  are announced in the ranking block's header, and the block and its closure go through the same
  handlers a peer's go through, into the Leios store, before the ranking block goes to chain
  selection; the LeiosFetch server serves them from there. No endorser block is forged when no
  transaction is left for one. Built on the recorded closure of the endorser block that the
  testnet's block 64,853 announces, the forged block is the recorded one, byte for byte, and the
  header body that announces it is the block's. The pool does not admit Dijkstra transactions yet, so
  the node still forges no endorser block; certifying and voting are not built yet.
