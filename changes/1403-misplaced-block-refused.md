### Fixed (chain selection, #1403)

- **A block whose header is not the next of its chain is an invalid block, not a stop of the node.** A block that
  names its predecessor by hash and has another block number than the next, or a slot that is not after its
  predecessor's, is now refused when it is applied: recorded invalid, and its sender punished, as a block the rules
  refuse is (`BlockFailure.HeaderStructure`, with the cause header-first validation names). Before, extending the
  chain with it failed as an internal error (`ChainError.NotConnected`), which stopped the node: the block rules of
  the eras before Dijkstra leave a header's place in the chain to that step (Byron's check the slot, not the
  number). Header-first validation refuses such a header before its block is fetched, so only a node run with
  `--header-first off` met one, in a block a slot leader signed. A delivered block is written to the received-block
  log before it is applied and a start seeds chain selection from that log, so the next start, with any setting,
  met the block again. A previous hash that is not the tip's is still the node's own failure: a candidate follows
  the predecessor links from a point of the node's chain.
- `ChainError.NotConnected` names the check the header fails (its block number, its slot or its previous hash), and
  its log text ends with it.
