### Changed (network, #1366)

- A request for ledger peers, ordinary or big ledger, that leaves the peer governor below its target is
  asked again after 5 s, as the reference asks it (`ttl = 5`, ouroboros-network `LedgerPeers.hs` 315).
  Before, the node waited for the DNS TTL of the drawn relays' lookups, 60 to 900 s. The public roots
  of the topology, and the fallback to them, are unchanged.
