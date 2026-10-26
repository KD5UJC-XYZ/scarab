### Fixed (storage, #493)

- Decoder implementation failures retain their original causes instead of being treated as
  malformed snapshot data. Reconstruction failures stop restore before fallback and cannot become
  an ignored read-back refusal. Ordinary corrupt snapshots still fall back, and persisted bytes,
  schema fingerprints and format versions are unchanged.
