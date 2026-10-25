### Changed (flat script decoding)

- The flat decoder of Plutus scripts (`FlatScan.decode`) is now written without mutation: pure readers of the bit position
  and a `@tailrec` walk over immutable frames, in place of a walker with a mutable position, `ArrayDeque` stacks and
  `boundary`/`break`. Results are unchanged: the same program and scan facts, or the same failure, for every fixture script,
  every prefix of one and seeded single-bit changes of each (`FlatScanImperativeSuite`, against the previous walker kept in
  the tests). On the Plutus scripts of the recorded Preview and preprod transactions it takes about 4% longer and allocates
  about 1.4 times as much; `docs/performance.md` ("Flat script decoding") has the measurements.
