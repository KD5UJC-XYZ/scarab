### Fixed (Leios configuration)

- Read the public Leios testnet build's configuration settings with its conditional Dijkstra parsing,
  Leios defaults and LedgerDB snapshot precedence. Report explicit settings whose services are not
  implemented as recognised and unused (#745); this does not activate the Dijkstra ledger.
- Reject overflowing stored exponents in the shared bounded-integer JSON reader before a wrapped
  normalized exponent can turn an out-of-range configuration or genesis value into a small integer.
