### Fixed (ledger decoding)

- A tag 30 rational of a bounded field (a pool's margin, a protocol parameter or its update, a voting
  threshold, a committee's quorum) is reduced in time linear in the length of its numerator and denominator.
  Below decoder version 12 their length is not bounded, and the value was reduced with `BigInteger.gcd`,
  whose time grows with the square of that length, before it was bounded: a fetched block body of 609 KB
  whose pool margin was two random bignums took tens of seconds to be refused. Euclid's algorithm now stops as soon as
  the reduced numerator or denominator is known to be 2^64 or more, which the reference refuses in every
  field; values the reference accepts, a long common factor included, get the same reduced value as before,
  and every value gets the same verdict.
- The refusal of such a value (`RationalOutOfBounds`) keeps the bit lengths of its components instead of the
  value, so its message is short; a reduced value below 2^64 that is negative or outside the field's interval
  is kept as before.
