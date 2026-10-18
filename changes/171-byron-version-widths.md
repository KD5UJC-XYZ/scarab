### Fixed (Byron protocol versions, #171)

- Bound Byron protocol-version fields to the reference's Word16/Word16/Word8 at header and proposal decoding and smart construction. Signed block and transaction regression cases cover the bounds and legal proposal transitions; snapshot bytes and schema remain unchanged.
