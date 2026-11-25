### Fixed (ChainSync candidate trim, #810)
- **A session's ChainSync candidate no longer grows until the node stalls.** The candidate that the
  fetch decisions and chain selection read was only capped at the retained window: in Genesis mode
  263,521 headers on mainnet, in Praos mode 8,192. Once that many headers had arrived since a restart,
  the window's oldest block had long been ours and was never fetched again, so the window had no room.
  ChainSync stopped, and a Genesis sync from origin stalled. Wherever the node's header gate runs (always
  in Genesis mode, and in Praos mode with `--header-first on`), the candidate is now trimmed to our
  chain's anchor along with the gate's history, as the reference trims its candidate whenever our chain
  changes. It stays near `k` plus the forecast range. The trim never passes what the session has
  published to its consumer. In Praos mode with `--header-first off` the candidate is capped at the
  window as before.
- After a trim, a Genesis graft to an older checkpoint is published as a rollback to the graft's base
  and its headers again, where it was a rollback to the newest shared header: the consumer ends at the
  same tip.
