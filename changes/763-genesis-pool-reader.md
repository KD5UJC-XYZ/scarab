### Fixed (genesis stake pools, #763)

- The rest of a Shelley-genesis stake pool is read as the reference `StakePoolParams` reader
  reads it: the old names `publicKey` and `rewardAccount`, script reward accounts and both
  spellings of each credential key, 28-byte pool and owner hashes and a 32-byte VRF key hash,
  Coin values in any aeson `Integer` spelling (signed, `1e3`, `100e-2`, exponents up to 1024),
  margins as decimals or `{"numerator", "denominator"}` objects, and relay ports in any `Word16`
  spelling. A genesis integer may now carry a written exponent below -1024 when its value is
  whole, as aeson reads it.
- A relay DNS name longer than 128 bytes, which genesis accepts, is now kept by the ledger peers,
  the ledger-peer snapshot query and the SCLS export instead of making them skip or refuse the
  pool. The SCLS import still reads names at the wire's 128-byte bound.
