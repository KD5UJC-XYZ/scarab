### Changed (Leios votes and fetch as rc-2026w47, #1365)

- A node takes Leios votes only for the newest 8 blocks of its chain (`NotVotable` otherwise, before the
  signature check, neither counted nor relayed, the peer kept), as ouroboros-consensus #2534 does in
  rc-2026w47. This replaces the retired-block record of #1357, which mirrored no upstream code.
- The Leios fetch decision loop runs every 20 ms instead of every 500 ms (ouroboros-consensus #2531).
