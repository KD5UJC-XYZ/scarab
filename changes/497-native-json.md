### Added (#497)

- Add the reference's strict raw JSON Value parser, preserving original-token validation,
  first-wins duplicates, native object order and Scientific representation. It returns
  `JsonValue`, Scarab's copy of aeson's `Value`: numbers keep the reference's `Scientific`
  coefficient and exponent as parsed and compare after normalization, as `Scientific` does.
  Genesis loader integration and field-level decoding remain separate required work.
