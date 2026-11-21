### Added (the Leios build, #54)

- Encoders for a Dijkstra block as the Leios build forges it: the 12-element Leios header body (the
  bytes the KES key signs), the header, each transaction in its block form, the block body and the
  block of tag 8. Every Dijkstra header and block in the fixtures, from the public Leios testnet and
  the Dijkstra devnet, encodes again to its recorded bytes. Nothing forges with them yet.
