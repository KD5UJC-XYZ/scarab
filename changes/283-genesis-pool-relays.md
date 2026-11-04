### Fixed (genesis stake pools, #283)

- Stake pools injected by the Shelley genesis now keep their configured relays and metadata
  instead of starting with none, so they reach ledger peers and the pool queries as the reference
  node registers them. Their JSON forms follow the reference `StakePoolParams` reader: all three
  relay forms, optional and `null` relay fields, Word16 ports, IPv4/IPv6 spellings, unrestricted
  JSON DNS names and URLs, and arbitrary-length hex metadata hashes. A pool entry without a
  `relays` or `metadata` key is refused, as the reference refuses it.
