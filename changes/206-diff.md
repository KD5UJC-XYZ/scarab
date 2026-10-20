### Changed (difference derivation, #206)

- `Diff` derivation no longer erases field and case instances to `Diff[Any]` and no longer reads products through casts: each field and case is compared at its own type, values are narrowed by `ClassTag` extractor patterns (a checked runtime test, no source cast), and the `eq` fast path uses pattern tests. `DState[A]` and `Mirrored[A]` keep written-out givens with a `ClassTag` context bound; summoning their Diff for a generic `A` now requires the witness.
- Behaviour is unchanged: differences, paths and field names are the ones the property tests pin.
