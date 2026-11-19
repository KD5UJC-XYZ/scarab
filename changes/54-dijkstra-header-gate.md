### Added (Dijkstra headers in the header gate, #54)

- Under `--reference-build leios-b004fe0c` the header gate validates a peer's Dijkstra headers as
  that build's ChainSync client does: the ledger view forecast from the anchor (Conway's across the
  boundary, or Dijkstra's own), the chain-dependent state translated into Praos2's and ticked, then
  the envelope checks and Praos2's rules, the leader check on the forecast stake. A refusal carries
  Praos2's typed failure (`HeaderError.Praos2`). The node still does not enter Dijkstra: with its
  maximum major version of 11 a Dijkstra header is refused as of the wrong era in every build, and
  `follow` refuses the Leios build.
