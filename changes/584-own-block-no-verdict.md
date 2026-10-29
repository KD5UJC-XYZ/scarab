### Fixed (forging and headers)
- **No verdict on our own block no longer stops the node.** This covers a state out of range and an exception of the
  rules, besides the evaluator and the crypto providers. The block is judged as a peer's would be, reported as
  `Chain.BlockNoVerdict`, and handled as a refusal: its transactions leave the pool and the slot ends without a block.
- **A forging tick whose ledger state is out of range ends the slot without a block** (`LedgerViewUnavailable`),
  rather than stopping the node.
- **A crypto provider that fails on a peer's header still closes that connection, but no longer charges the peer.**
  Part of issue 584.
- **A held transaction the node has no verdict on while it builds its own block leaves the pool with its reason, and
  the block is built from the rest.** This is an owner-approved deviation: the reference's forging thread stops the
  node.
