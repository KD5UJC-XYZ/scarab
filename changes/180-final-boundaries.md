### Fixed (G7-F1/F3/F5, #180)

- Give rule, store and serving operations their caller's error scope, retaining typed failures across
  worker and stream boundaries.
- Stop forging on storage failures, evaluator unavailability and defects. A KES persistence failure
  retains its event and metric, prevents further signing and requires restart after repair.
- Keep bounded attribution for cached blocks after their source disconnects, so an invalid block
  distrusts its source rather than the innocent session that validates it.
