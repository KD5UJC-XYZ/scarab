### Changed (Leios testnet fixtures and header reading after the restart, #743)

- The Leios testnet fixtures are those of the network as restarted on 2026-10-18: its published
  configuration and genesis files, its first 1,100 headers, notable announcing and certifying
  headers, and empty, certifying and announcing blocks, captured read-only on 2026-11-03. The
  fixtures of the network before the restart, whose chain no longer exists, are removed, and the
  genesis oracles that read its genesis files were run again on the current ones.
- `LeiosHeaderChain` and `LeiosCheckMain` read each header by its era as the Leios testnet's build
  does (`LeiosHeader.byEra`): era index 7 is the Leios header, an earlier index that era's Praos or
  TPraos header. They no longer read a Conway-indexed header as a Leios header.
