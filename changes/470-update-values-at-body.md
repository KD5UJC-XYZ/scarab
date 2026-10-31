### Fixed (pre-Conway update values at body decode, #470, #436)

- Every value of a Shelley-to-Babbage protocol-parameter update is read at the type of its field when
  the transaction body is decoded, as the reference reads it: an unsigned integer over its `Word16`,
  `Word32` or `Word64` width, a rational without tag 30 or outside its interval, `Prices` or
  `ExUnits` of the wrong shape, or a protocol version whose major is over the era's bound (Shelley 3,
  Allegra 4, Mary 5, Alonzo 7, Babbage 9) refuses the body with a typed `LedgerDecodeError`. Such
  values were accepted before and read only when the update was applied.
- `Prices` and `ExUnits` in a parameter update are definite lists only, in Conway as before it: the
  reference reads both as `RecD` records, which refuse an indefinite list below decoder version 12.
- An update held in a stored ledger state is read as before: a pre-Conway update when it is applied,
  and a Conway proposal's `Prices` and `ExUnits` in either list form.
