### Changed (the Leios build, #826)

- **Storage format (V10, in place):** a Dijkstra state whose governance proposals update a parameter
  Dijkstra adds (update keys 34 to 56) is now read and written as the Leios build writes it: a definite
  map in ascending key order. V10 refused such a state before. Other states keep their bytes, and
  nothing migrates.
- The node still does not run the rules of those parameters: it halts on a transaction that proposes
  one or a pool's vote on one, and at an epoch boundary that would ratify one.
