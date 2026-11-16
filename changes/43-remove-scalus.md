### Removed (Plutus, #43)
- **The Scalus backend and `--plutus-vm`.** Scarab's own evaluator (the `uplc` module) is the node's only Plutus
  evaluator, for phase 2 and script admission in the block rules, the mempool and the forger (ADR 0027, amendment of
  2026-11-15). A command line that still names `--plutus-vm` is refused before the node starts, with an
  error saying the flag was removed and should be dropped (exit status 2).
- The start-up events `plutusVm` (`Node.PlutusVm`) and `plutusCrypto` (`Node.PlutusCrypto`). A logging setting that
  names either namespace is refused at start, as one naming any namespace that does not exist is.
- The start-up check of `libscalus_crypto`, which is no longer shipped; the check of jblst's libraries stays.
- The patched Scalus build step and its inputs.
