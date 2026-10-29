### Fixed (peer selection)
- **An exception while a local root's DNS name is looked up no longer stops the node.** As the reference's local root
  provider does (`TraceLocalRootError`, ouroboros-network `2fb69829` `RootPeersDNS/LocalRootPeers.hs` 146-168), it is
  logged (`localRoots`, `action` `error`, with `domainAddress` and `reason`), the answers of the other names are kept,
  and the lookups start again at once. An exception elsewhere in the local roots' refresh still stops the node
  (`LocalDnsRefreshDefect`). Part of issue 567 (H8).
