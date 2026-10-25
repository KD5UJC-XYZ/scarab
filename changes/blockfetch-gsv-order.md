### Fixed (block-fetch peer ordering)
- **Ordering block-fetch candidates by GSV can no longer stop the node.** The node could exit with a fatal defect,
  `IllegalArgumentException: Comparison method violates its general contract!`, while ordering block-fetch
  candidates. The reference comparator `comparePeerGSV` treats peers whose geographic delays lie within 5% as equal
  up to a salted tie breaker; that relation is not transitive, and the JVM's TimSort may reject it once 32 or more
  peers are sorted. `BlockFetchPolicy.rank` and preferred-peer admission in `BlockFetchPolicy.decide` now sort with
  `HaskellSort.sortBy`, a stack-safe implementation of GHC 9.10.3 `Data.List.sortBy`, so for a given candidate
  sequence Scarab orders peers exactly as the reference does, whatever their GSV values, and never throws. The same
  sort (moved from `CandidateSelection`) orders chain candidates.
- **The GSV comparator floors as `DiffTime` does.** The 5% band and the 20% incumbent advantage are picosecond
  `DiffTime` products in the reference, so they are now ⌊max/20⌋ and ⌊4g/5⌋; previously an exact-rational band
  treated a difference of exactly ⌊max/20⌋ as a tie.
