### Fixed (Plutus evaluator)
- **`equalsData` stops at the first difference.** It compares constructors and sizes before it reads any child, and a
  map's entries are no longer copied to be compared, so comparing a large map with a small value takes no time in the
  map's size, as the reference's derived equality does. Results and budgets are unchanged, as for every entry below.
- **`insertCoin` updates a value's cached sizes** in time logarithmic in the value's size instead of rebuilding the value.
- **`writeBits` returns its input for an empty index list**, and `andByteString`, `orByteString`, `xorByteString`,
  `complementByteString` and `replicateByte` fill their results without boxing each byte. `decodeUtf8` decodes through
  the JDK's fast path.
- **BLS12-381 G1 and G2 elements keep their points between operations**, as the reference keeps blst's, so `add`, `neg`,
  `scalarMul` and `equal` no longer decompress their operands and compress their results.
  `bls12_381_G2_hashToGroup` clears the cofactor by the endomorphism of RFC 9380 appendix G.3, about three times as
  fast as before.
- **`verifyEcdsaSecp256k1Signature` and `verifySchnorrSecp256k1Signature`** compute over Bouncy Castle's fixed-width
  secp256k1 arithmetic.
- **`BuiltinTimeMain`** (`tools`) times a loop of each of these builtins to a transaction's budget against a loop of
  `sha2_256`.
