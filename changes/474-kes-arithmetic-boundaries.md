### Fixed (KES validation, #474)

- Refuse TPraos/Praos KES windows whose end overflows Word64, matching the reference's rejection.
  Ordinary KES windows and signatures are unchanged.
