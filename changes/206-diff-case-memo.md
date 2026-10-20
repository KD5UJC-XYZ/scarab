### Fixed (difference derivation, #206)

- Derived products and enums retain each field and case's `Diff` instance, made once on first use in the derivation. Comparisons no longer rebuild those instances, labels and closures; field order and rendering retain the original cast-based behavior.
- Enum dispatch selects the mirror's known ordinal before narrowing the value. This prevents a singleton case's erased runtime class tag from shadowing a later data case and incorrectly reporting equal values with different fields.
