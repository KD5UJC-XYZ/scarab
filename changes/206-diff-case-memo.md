### Fixed (difference derivation, #206)

- Derived products and enums retain each field and case's `Diff` instance, made once on first use in the derivation. Comparisons no longer rebuild those instances, labels and closures; enum dispatch selects the known case ordinal directly. Differences, first-difference order, paths and field names are unchanged.
