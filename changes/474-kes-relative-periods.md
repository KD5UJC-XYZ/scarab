### Fixed (KES verification, #474)

- Preserve the reference's KES verification for large relative periods on custom configurations,
  including final-leaf signatures, without accidental 32-bit wrapping. Certificate windows and
  signing-key evolution limits are unchanged.
