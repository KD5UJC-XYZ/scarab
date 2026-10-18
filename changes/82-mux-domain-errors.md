### Changed (multiplexer error values, #82)

- Mux framing, segmentation and streaming decode report closed error cases with rejected values,
  expected lengths and local limits. Network and node boundaries retain the typed causes and
  distinguish peer framing from local setup/write failures. Wire formats and recovery policy are
  unchanged; local setup/write diagnostics now name the operation.
