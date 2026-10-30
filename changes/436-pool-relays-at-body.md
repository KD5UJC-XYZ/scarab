### Fixed (pool relays at body decode, #436)

- A pool registration's relays are decoded with the transaction body, as the reference decodes them:
  a port above 65535, an IPv4 or IPv6 address of the wrong length or as a chunked byte string, a
  chunked DNS name, or a DNS name longer than 64 bytes (Shelley to Babbage) or 128 bytes (Conway)
  refuses the body with `PoolRelay` naming the relay. Such relays were accepted before and read only
  for the ledger peers.
