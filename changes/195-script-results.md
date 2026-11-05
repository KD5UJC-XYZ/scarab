### Fixed (I4)

- Evaluate and retain every Plutus script result in a transaction, including scripts after a proven
  failure, in reference collection order. Serial and bounded parallel block validation now preserve
  all typed failures, trace output and actual backend units, and count every reached script.
- Include the original script context, declared budget and cost model in the reference-shaped
  node-to-client reconstruction payload for every validity-tag script failure. This historical result
  did not establish complete Haskell diagnostic presentation. The 2026-11-04 explicit-error fix
  compares every retained aggregation predicate as whole bytes; complete remaining diagnostic parity
  is required under #796.
