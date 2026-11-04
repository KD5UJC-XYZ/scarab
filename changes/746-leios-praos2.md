### Fixed (Leios Praos2 headers)

- Mirror the public Leios testnet build's Praos2 header validation, rejection order, announcement
  timing and protocol-state evolution, with native comparisons over recorded and signed synthetic
  headers. These pure rules require a supplied ledger forecast; Dijkstra node activation remains
  separate work (#746, #73).
