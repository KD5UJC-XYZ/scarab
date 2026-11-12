### Fixed (SIGHUP, #919)
- **A reload forges with the VRF key it read**, as the reference's new block forging does; before, the node kept
  the VRF key it started with. A slot whose leader check used the previous key is given up unsigned.
- **SIGHUP no longer ends the node while it opens and replays its chain, or on a node without `--forge` and
  `--topology`.** From the opening of the stores the node has the reference's initial handler: it reads the
  topology again (the network starts from it) and logs `blockForgingUpdate` `NotEffective`; once it runs, every
  node has the full handler.
