### Fixed (BlockFetch decision cost, #810)
- **The BlockFetch decision no longer scans each peer's whole candidate at every round.** In Genesis mode the
  decision runs every 40 ms and searched each peer's candidate back from its tip for the intersection with our
  chain, twice per peer, so a peer far ahead of the selection was scanned in full each time. A candidate whose
  slots never decrease is now searched from the selection's newest slot. One whose order is not known (a peer
  under `--header-first off`) is scanned from its tip as before. A Byron header's slot is computed once. The
  decisions are unchanged.
