### Fixed (peer selection, #810)

- A local root's DNS name that answers the same addresses in another order, as the round-robin names of
  the mainnet backbone relays do, no longer publishes a new roots generation: the resolved groups are
  compared as the reference's provider and governor compare them, with their members as sets.
- A roots reload keeps the public-root and big-ledger requests' backoffs, retry times and requests in
  flight, and an answer that arrives after a reload is applied to the state as it is then, as the
  reference's governor does; a reload no longer asks for roots again at once.
- A roots reload keeps the public and bootstrap roots the governor knows, but for the new local roots,
  and reads the configured ones at the next request, once the ledger has no peers to give. A reload that
  configures other bootstrap peers (turns them on or off, or names other relays) in Praos mode does what
  the reference's `monitorBootstrapPeersFlag` does: it forgets the bootstrap peers that are neither
  established nor being connected and sets the judgement to `YoungEnough`, so the clean state starts
  again when the ledger is too old, and the new local roots wait for the judgement.
