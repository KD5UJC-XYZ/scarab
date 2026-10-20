### Added (Preview acceptance evidence, #21)

- Pin the 242 historical invalid Preview transaction identities and export authenticated replay
  windows from preserved block bytes, native V5 snapshots and their matching UTxO checkpoints.
- Add strict epoch-comparison options that require complete reference coverage, alongside the
  existing progress mode, and record the recovered historical K1 projection results and limits.
- Reuse one recorded-block runner for preprod and Preview serial/parallel comparisons, with
  ordered typed results and explicit input bindings. Reuse production startup replay for the
  offline K1 comparison, with private database metadata and read-only archived block bodies.
- Record completed Preview acceptance: 4,758,521 continuous blocks, all 1,451 reference
  projections and the exact ordered 242 invalid transactions; all 68 authenticated failure
  windows agree in serial/four-worker modes. Preserve the projection limits, measured timing
  scopes and six responsiveness warnings for later comparisons over the same range.
