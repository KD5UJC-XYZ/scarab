### Fixed (security parameter, #1027)

- With unequal Byron and Shelley genesis values, the node takes Byron's security parameter for
  chain selection, rollback depth, the default snapshot cadence, header retention and the
  Haskell and Mithril import chunk size, also on chains that start in Shelley, as the release
  reference does; the Shelley-based protocol, ledger and era-history windows keep Shelley's.
