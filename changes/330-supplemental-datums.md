### Fixed (UTXOW datum check, #330)

- A witness-set datum that no spent Plutus input requires and that no output (or, from Babbage, collateral return or reference input) carries by hash is refused with `NotAllowedSupplementalDatums (unallowed, acceptable)`, as the reference's `missingRequiredDatums` does; the constructor was encoded but never produced. The datum failures are now checked in the reference order (unspendable input, missing datums, supplemental datums).
