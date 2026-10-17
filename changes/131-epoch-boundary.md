### Changed (the epoch boundary of the era-indexed rules is 30% faster, #131)
- **New / old at the Preview K1 boundaries (snapshots 1400 to 1449, 112,000 accounts): 1.03 mean and 1.12 p99, from 1.47 and 1.86.**
  The gate of the design (p99 within +25% of the old rules) holds; the ticked state is byte-identical (the SHA-256 of the
  Haskell-format `NewEpochState` of all 50 boundaries is the same before and after). `docs/performance.md` section 16 has the
  method, the profiles and the numbers; on `main` after J2.9 (the old rules gone) the boundary tick is 45% faster pooled
  (mean 699 to 381 ms, p99 1,326 to 773) with the same digests. The fallback of hash maps with a sorted view was not needed.
- **What changed.** The accounts, the instant stake and the rewards, which are in one order, are walked together
  (`Sorted.Cursor`) instead of looked up one by one; sorted maps are built once from sorted input (`Sorted.fromAscending`) and
  the rewards are credited by one merge (`EraLedger.creditAscending`) instead of a path copy each; every map of a key type shares
  one `Ordering` (`SortOrder`) so `TreeMap.from` copies instead of re-inserting; `Bytes.compareTo` settles most comparisons on a
  cached four-byte prefix (no extra memory) and otherwise `Arrays.compareUnsigned`; the DRep pulser copies only the accounts
  ratification reads. `EpochBoundarySuite` checks each against the version it replaced; `BoundaryTickMain` times a boundary and
  `BoundaryDigestSuite` (opt-in, `SCARAB_K1_DB`) keeps six digests of the real states.
