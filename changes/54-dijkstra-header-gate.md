### Added (Dijkstra headers in the header gate, #54)

- Under `--reference-build leios-b004fe0c` the header gate validates a peer's Dijkstra headers as
  that build's ChainSync client does: the ledger view forecast from the anchor (Conway's across the
  boundary, or Dijkstra's own), the chain-dependent state translated into Praos2's and ticked, then
  the envelope checks and Praos2's rules, the leader check on the forecast stake. A refusal carries
  Praos2's typed failure (`HeaderError.Praos2`). As the hard-fork combinator forecasts, the view
  reaches one era past the anchor's at most: a Dijkstra header whose slot that view places in
  another era is refused as of the wrong era, never checked by another era's rules; from an
  anchor still in Byron that view is Byron's until a known Shelley boundary and the initial Shelley
  one after it. The forecast across the boundary translates only what the view reads (the
  parameters and the snapshots), not the UTxO. A defect of
  the node's own state or configuration on such a header (a Dijkstra view that cannot be made, a
  Praos2 parameter that cannot hold) ends the peer's session uncharged
  (`PeerError.HeaderUndecided`). With the node's own setup of the testnet the gate validates its
  headers from the chain's Dijkstra genesis on; `follow` still refuses the Leios build, and under
  the release builds, whose maximum major version is 11, a Dijkstra header is refused as of the
  wrong era.
