### Changed (rule interpreter, #202)

- `RuleT` runs on a finite-control, indexed continuation machine: the frame stack is a type-aligned `Frames` sequence (`Next[X, Y]`, `Handler[A]`, `Restore[A]`), so every continuation receives the exact result type of the program before it. The four `asInstanceOf` sites and the `@unchecked` `Bind` pattern are gone, with no `Any`-typed value channel; laws, rollback and `Globals` restoration are unchanged.

- Refuse incomplete devnet class freezes before launching a follower, and retain an opt-in benchmark over pinned Preview block and epoch inputs.
