### Added (the Leios build, #54)

- Dijkstra's epoch boundary: EPOCH, SNAP and POOLREAP as the Leios build runs them. SNAP runs
  after the enactment, on the parameters now current, and seats the Leios committee of
  `ppLeiosCommitteeSize` with the key age the KES constants give; POOLREAP releases one reference
  to each VRF key hash a pool stops using. Checked byte for byte against the build's own tick on
  the two captured Dijkstra states and on states derived from them. The ledger's globals now carry
  the Shelley genesis's KES constants, read as the reference reads them (`Word64`).
