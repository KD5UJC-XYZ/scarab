### Fixed (security)
- **`equalsData` compares the entries of a map in order, as the reference does.** The builtin no longer treats the entries
  of a `Data` map as a set: order and repeated entries both count, nested maps and constructors are compared the same way,
  and deep data is compared on the heap. Verdicts and exact budgets come from the executed plutus 1.70.0.0 reference
  (`fixtures/reference/plutus-equals-data`).
