### Changed (node, #471)

- **The forger translates time with the Byron genesis parameters**, as the block rules and the mempool do. It used to take the Byron security parameter from the Shelley genesis. The Byron security parameter only sets the Byron era's safe zone, so the change shows only where the Byron and Shelley values differ.
