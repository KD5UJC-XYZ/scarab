### Changed (the Leios build, #54)

- **Storage format (V10, in place):** a Dijkstra state whose pools' future parameters carry a voting
  key is now read and written as the Leios build writes it: from protocol version 12, ten items with
  the key third. V10 refused such a state before. Other states keep their bytes, and nothing
  migrates.
- The Dijkstra epoch oracle now compares every state of the testnet's keyed re-registrations,
  including those before the boundary.
