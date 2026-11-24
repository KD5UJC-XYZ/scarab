### Changed (era types, #85)

- The forger selects a Conway block's transactions with Conway's era rules, in place of the flat rules. It takes
  the pool's snapshot of the block's slot, applied again on the ledger of that slot as the selected build's consensus
  applies it (11.1's `reapplyTx`, 11.2's and the Leios build's `reapplyValidatedTx`), with the clock the block rules
  judge the block with. It then takes the longest prefix that fits the block, as the reference does.
- A Conway block's body is the held transactions' parts as they were received, each with its verdict's flag. The
  node no longer reads them again.
- The whole pool is applied again before the block is cut, as in the reference. A held transaction the rules have
  no verdict on leaves the pool even when it is past the block's cut.
- Parameters in force that do not read into Conway's end the slot without a block (`forgeFailed`). The node goes on.
