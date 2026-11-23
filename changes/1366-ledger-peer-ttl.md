### Changed (network, #1366)

- An answer of ledger peers, ordinary or big ledger, is good for 5 s, as the reference's is (`ttl = 5`,
  ouroboros-network `LedgerPeers.hs` 315): when an answer adds peers and the peer governor still needs more,
  it asks again after 5 s. Before, it waited for the DNS TTL of the drawn relays' lookups, 60 to 900 s. An
  answer that adds none still backs off exponentially, as before and as the reference does. The public roots
  of the topology, and the fallback to them, are unchanged.
