### Fixed (cost models in parameter updates, #332)

- Refuse at decode the cost-model map of a protocol-parameter update that the reference refuses.
  Before Conway (decoder version below 9) only PlutusV1 with 166 and PlutusV2 with 175 parameters
  are read; a Conway parameter-change proposal takes a `Word8` language with any list length and
  refuses a repeated language. Parameters outside `Int64` are refused by both. Stored parameters,
  snapshots and genesis files are read as before.
  The pre-Conway rule is checked against 98 retained transactions measured with the unchanged released
  Alonzo and Babbage decoders (`fixtures/reference/cost-models`).
