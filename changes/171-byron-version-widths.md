### Fixed (Byron protocol versions, #171)

- Bound Byron protocol-version fields to the reference's Word16/Word16/Word8 at header and proposal decoding and smart construction. Signed block and transaction regression cases cover the bounds and legal proposal transitions; snapshot bytes and schema remain unchanged.

### Added (reference verification, #171)

- Full unchanged-reference Byron registration, proposal/transaction admission and signed block-ledger
  composition fixtures at protocol-version width boundaries, with separate signature controls and
  original-byte overflow refusals. The offline replay recipe preserves package/native pins and exact
  scope; production formats and rules are unchanged.
