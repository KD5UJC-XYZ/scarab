### Fixed (devnet)
- **The devnet's reference nodes start on a kernel without `/proc/self/io`.** The WSL kernel 6.18.54.1 (WSL 3.0.3)
  is built without per-task I/O accounting, and cardano-node's resource-statistics thread stopped every Haskell
  node of a devnet at start-up. Where the reference container has no `/proc/self/io`, and only there, the
  generated configuration sets `TraceOptionResourceFrequency` to 0, which starts no such thread.
