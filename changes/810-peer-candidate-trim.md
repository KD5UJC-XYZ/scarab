### Fixed (ChainSync candidate trim, #810)
- **A session's ChainSync candidate no longer grows until the node stalls.** The candidate that the
  fetch decisions and chain selection read was only capped at the retained window, 263,521 headers on
  mainnet. That many headers after a restart, its oldest block had long been ours and was never fetched
  again, so the window had no room. ChainSync stopped and the node stalled. With the header gate on (the
  default, `--header-first on`), the candidate is now trimmed to our chain's anchor with the gate's
  history, as the reference trims its candidate whenever our chain changes, so it stays near `k` plus
  the forecast range. The trim never passes what the session has published to its consumer. With
  `--header-first off` the candidate is capped at the window as before.
