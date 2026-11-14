### Fixed (configuration, #1004)

- `PBftSignatureThreshold` is read as cardano-node 11.1.3 reads its `Maybe Double`: a JSON number, or the
  strings `"+inf"` and `"-inf"`, with null the same as absent (0.22). A numeric string such as `"0.22"` is
  now refused, as the reference refuses it. A number out of range becomes an infinity or a signed zero, and
  an exponent beyond a 64-bit integer wraps, as in the reference.
