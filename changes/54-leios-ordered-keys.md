### Fixed (Leios fetch and vote keys, #54)

- The hashes an endorser block lists are counted for its duplicate check in a map ordered by
  their bytes, as the reference's `Map.fromListWith` counts them, not hashed: a producer could
  list hashes of one `hashCode` and make the check of a body quadratic in its size.
- The mempool's hits for a body, the fetch logic's state by endorser-block hash and the vote
  state's tallies by announcing block are ordered maps by the hashes' bytes, as the reference's.
