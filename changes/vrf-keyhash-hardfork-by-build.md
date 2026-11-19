### Fixed (Conway HARDFORK: the pools' VRF key hashes at a change of version at major 11, by reference build)
- **The `11.1` build counts the pools' VRF key hashes at a Conway hard fork as cardano-node 11.1.3 does.** HARDFORK
  runs at every change of protocol version whose new major version is 11, a new minor version included. Scarab
  recomputed the counts from nothing in every build, as 11.2.1 does, while 11.1.3 adds one count for each registered
  pool and each waiting re-registration to the map it has (cardano-ledger f649f975,
  `Conway/Rules/HardFork.hs` 107-125). Under `11.1` the counts are now added: after a change of minor version at 11,
  a pool that retires leaves its key hash counted once, and a pool registration with that key is refused with
  `VRFKeyHashAlreadyRegistered`, as 11.1.3 refuses it. The `11.2` and Leios builds still recompute the counts from
  nothing, counting a waiting re-registration only where its key hash differs from its pool's (cardano-ledger
  29a7d656 `State/CertState.hs` 525-535, 9df9f98c 528-538). A waiting re-registration's VRF key hash is the state's
  typed hash (#85), so none of another length reaches the count.
  `ConwayGovernanceSuite` takes both builds through a change of version from 11.0 to 11.1, the retirement and
  reaping of a pool, and the registration of its key by the same pool and by a new one.
- **Under `11.1` a ledger state below protocol version 11 that holds pools' VRF key hashes is refused where it is
  restored or imported** (what is refused and what to do: `docs/running/snapshots.md`, "A ledger state that no chain
  of the build reaches").
  The retired flat conversion derived that map from 10, and the snapshots it wrote are still admitted and migrated;
  since the 11.1 HARDFORK adds to the map it has, such a state would count each pool twice on entering 11. A restored
  snapshot of such a state stops the start, and `import-ledger-snapshot`, the Mithril bootstrap and `import-scls`
  refuse it before anything is published, with `StorageError.UnreachableLedgerState`, which names the snapshot or
  file. `StateAdmissionSuite`, `LedgerSnapshotImportSuite` and `ConwayGovernanceSuite` check it.
