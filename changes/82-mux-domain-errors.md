### Changed (multiplexer error values, #82)

- Mux framing, segmentation and streaming decode report closed error cases with rejected values,
  expected lengths and local limits. Network and node boundaries retain the typed causes and
  distinguish peer framing from local setup/write failures. Wire formats and recovery policy are
  unchanged; local setup/write diagnostics now name the operation.
- Bounded segmentation reports its actual 65535-byte ceiling for zero or negative message limits,
  preserving which failure takes precedence when multiple local settings are invalid.
