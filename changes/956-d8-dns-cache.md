### Fixed (network, #956)

- Peer DNS lookups (local and public roots) now ask the resolver every time, as cardano-node does: Scarab no longer keeps dnsjava's in-process cache of DNS answers. Before, a root whose record had a TTL above 900 s was answered from that cache until the record expired, so a changed address was picked up later than the reference does, which looks the name up again after at most 900 s.
