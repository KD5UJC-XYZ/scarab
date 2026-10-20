### Fixed (Plutus data decoding)

- Enforce the reference's 64-byte bound on each original CBOR byte-string chunk, including positive and negative big-integer payloads. Preserve acceptance of longer values split into bounded chunks; native CLI parser/hash probes and decoder fuzz seeds cover the boundary.
