### Fixed (Leios fetch, #54)

- A transactions request names the pool of jobs it was picked from, and its reply ingests and retires
  only that pool's jobs. Before, a reply that came back after its endorser block was pruned and fetched
  again matched the new pool's jobs by number. When the mempool held other transactions the second
  time, the reply wrote transactions already taken from the mempool and retired jobs it had not
  fetched, and the rest of the closure was never fetched. A stale reply still releases the peer's jobs
  in flight and its bytes.
