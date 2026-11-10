### Fixed (ledger, storage)

- Preserve Leios voting keys when deriving pool stake distributions and reading their optional
  native field. Reuse the derived distribution per immutable snapshot; copied snapshots derive
  their own result. Leios following and Dijkstra activation remain incomplete (#826, #54).
