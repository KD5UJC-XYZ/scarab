### Changed (node)

- A block whose own Plutus scripts make the evaluator fail is held: not adopted, not recorded as invalid, no peer blamed, and the node keeps running behind it (`blockHeld`, `stalledBehind`, `scarab_blocks_held_total`). A missing evaluator implementation (native library, builtin, runtime) still stops the node with exit code 8. See the ADR 0017 amendment.
