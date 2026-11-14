### Fixed (ledger decoding)

- Read a redeemer's purpose as the reference decodes it, in the list and the map forms: the tag a `Word8` that names one of the era's purposes (0 to 3 in Alonzo and Babbage, 0 to 5 in Conway) and the index a `Word32`. Any other tag or index is refused when the witness set is decoded.
- Read the value of a Conway redeemers map entry as a definite-length list of two, as the reference's tuple decoder does.
- Read a Conway hard fork action's protocol version as `decodeProtVer` does, in a transaction body and in a stored ledger state: the whole version first (a major of at most 13 and a `Word32` minor), then a major of at most 12.
- Read a transaction body's network id (key 15, Alonzo onward) as a `Word8` of 0 or 1.
