### Added (the Leios build, #54)

- Dijkstra's POOLREAP gives a re-registered pool the voting key of its future parameters, as the
  Leios build does. A pool that re-registers the key it holds keeps the key's registration epoch,
  and a new key starts at the epoch the boundary enters. Parameters without a key leave the pool
  without one. The testnet's re-registrations of keyed pools are now checked byte for byte against
  the build's own tick from the boundary on. No chain reaches this yet: Dijkstra's pool
  registration is not implemented, and a state whose future parameters carry a key is neither read
  nor written, because that changes the snapshot format.
