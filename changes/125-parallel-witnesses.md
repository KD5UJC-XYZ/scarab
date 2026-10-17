### Changed (signature pre-verification, #125)
- **`VerificationCache.warm` parallelises over the signature checks of a batch, not over its blocks.** In late
  Alonzo the follower's batches reach `warm` one block at a time (blocks of 60 to 80 KB with 50 or more transactions), so
  every witness, the operational certificate and the VRF proof of a block ran on one thread while block application
  waited on it (39 of 39 thread-dump samples of the mainnet follower at epochs 319 to 323: one busy `scarab-cpu-*` thread
  in `Verdicts.Direct.witness`). The checks of all the blocks of a batch are now independent jobs, run on the blocking
  pool in chunks of at least 8 (about 1 ms), at most four chunks per thread. The cache keeps the same keys, verdicts and
  size limits; a block is offered to the rules (`takePrepared`) when its last check is done. Transaction application is
  untouched and stays sequential. On 400 captured epoch-320 blocks, one block per batch: 62 to 139 blocks/s at 4 CPUs and
  59 to 182 at 12 (all the `warm` calls of a cache share a limit of `parallelism` running chunks) (docs/performance.md section 14). `VerificationCacheSuite` checks the chunking and a many-witness batch
  against the serial verdicts; `WarmBenchMain` (tests) measures `warm` on a capture.
