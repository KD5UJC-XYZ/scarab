### Changed (node, #471)

- **The forger and the mempool translate time through one capability, `TxClock`, placed once at start-up by the node's era history parameters.** The Byron era's parameters come from the Byron genesis, as the block rules already took them. The forger used to take the Byron security parameter from the Shelley genesis. That parameter only sets the Byron era's safe zone, so the change shows only where the Byron and Shelley values differ. The forger no longer knows the era history's parameters at all.
