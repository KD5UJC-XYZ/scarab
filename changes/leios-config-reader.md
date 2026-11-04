### Fixed (Leios configuration)

- Read the public Leios testnet build's configuration settings with its conditional Dijkstra parsing,
  Leios defaults and LedgerDB snapshot precedence. Report explicit settings whose services are not
  implemented as recognised and unused (#745); this does not activate the Dijkstra ledger.
