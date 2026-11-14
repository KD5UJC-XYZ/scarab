### Added (Plutus evaluator choice, #43)
- **`--plutus-vm scalus|scarab`** chooses the Plutus evaluator of the block rules, the mempool and the forger
  (ADR 0027). `scalus`, the default, is the evaluator
  the node always ran. `scarab` is Scarab's own evaluator (the `uplc` module, with no Scalus in it): an opt-in correctness
  backend, not built for speed, for both phase-2 evaluation and script admission. With `scarab` the node also forges, and
  gives no verdict on a script at protocol 12 and later. The start logs the new event `plutusVm` (`Node.PlutusVm`) with the choice.
