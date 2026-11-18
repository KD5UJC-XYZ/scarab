### Added (the Leios build, #826)

- Under `--reference-build leios-b004fe0c`, a client that negotiates node-to-client version 23 is
  read as the Leios build reads it: `GetStakePoolState` (40) and `GetLeiosCommittee` (41) are
  answered in every Shelley-based era, and tags 4, 5 and 21 (`GetProposedPParamsUpdates`,
  `GetStakeDistribution`, `GetPoolDistr`) and `GetLedgerPeerSnapshot` without its peer kind are
  refused, ending the connection, as that build's decoder refuses them. Versions 16 to 22 stay
  offered under that build and are answered as under the 11.1 build, an approved deviation (the
  build offers 23 only). Nothing changes under the 11.1 build.
