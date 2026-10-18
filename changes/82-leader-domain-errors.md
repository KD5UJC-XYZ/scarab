### Changed (leader validation errors, #82)

- Praos leader-threshold validation returns closed error cases with the invalid values. Praos,
  TPraos, header-first and block validation, Leios header validation, forging and genesis setup
  retain typed causes instead of converting them to strings. Election arithmetic, accepted blocks
  and stored formats are unchanged; a missing or nonpositive comparison range is an explicit error.
