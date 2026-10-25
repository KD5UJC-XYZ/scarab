### Fixed (ledger decoding, #373)

- Refuse an indefinite-length (chunked) byte string where the reference reads a definite one, in transaction bodies, witness sets and auxiliary data of every era (decoder versions below 12). Plutus data and metadata keep reading chunked strings, outputs stored by earlier builds keep reading, and the original bytes that are hashed are unchanged.
