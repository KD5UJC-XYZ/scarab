### Added (offline ledger snapshot export, #140)

- `export-ledger-snapshot --db <dir> --out <ledger dir> [--slot N]` preserves the exact verified V5 Haskell state, streams its read-only checkpoint to MemPack tables and atomically publishes a fresh reference-layout directory. Gaps, Byron, mismatched checkpoints, absent snapshots and output collisions are refused; sources are not migrated or opened for writing.
- Reference loading/replay checks cover six private epoch boundaries, preprod epoch 320 and fixed-capture catch-up to the point an existing node reported 100% synced. Continuous later following remains separate; mirror-gap export refusals and the private pending reward-pulser representation difference remain explicit.
