# Local comparator checkpoint

Date: 2026-10-10. Base: `b8de8f8ac3ce807bdeb1ebe45b73a42dae76999c`.
Branch: `pico/repeated-epoch-soak-local`.

## Scope

Draft repeated terminal comparator, bounded CBOR reader, checked governance completion,
runtime export hook, test CLI and strict supervisor result verification. New pure
boundaries return typed failures. Active reward pulsing is explicitly unsupported.
The live launcher remains unconditionally disabled.

## Verification

- Independent static review completed; identified metadata, Scala keyword, native float
  semantics and retained-span memory-bound issues were corrected.
- Public Python commands in README ran locally: 585 tests, 583 passed, one skipped,
  one error. The error was `PermissionError: [Errno 1] Operation not permitted`
  constructing an AF_UNIX socket in
  `test_private_cluster_fenced_durable.MarkerInclusionTests.test_real_unix_socket_owner_guard_without_awk`.
  This environment limitation was not converted to a passing result.
- Separately, 151 focused Plutus supervisor tests and four prerequisite tests passed.
- `git diff --check` passed.
- Scala compilation, scalafmt and the new Scala tests have **not run successfully**.
  The sbt launcher downloaded with its pinned SHA verified, but bootstrap execution
  was cancelled by the execution review service. No compiler result was obtained.
- Optional retained-source exporter coverage has not run. Native endpoint agreement
  and the 120/600-second live cohorts have not run.
- GitHub push is unavailable in this environment: no authenticated Git helper or gh
  login; push dry-run failed requesting a username with terminal prompts disabled.

This is an uncompiled source checkpoint, not a release or conformance result.

## Native serialization provenance

- [Shelley 1.19.0.1 CHaP archive](https://chap.intersectmbo.org/package/cardano-ledger-shelley-1.19.0.1.tar.gz), SHA-256
  `cc9d5b415742bc188fb933fae305743a2c1ee6f3ad7fa06fcfa1ac5bd95b59ce`.
- [RewardUpdate.hs](https://github.com/IntersectMBO/cardano-ledger/blob/4c81e909df7555b6a58a000db8f8f4f89c7acdc4/eras/shelley/impl/src/Cardano/Ledger/Shelley/RewardUpdate.hs)
  and [PoolRank.hs](https://github.com/IntersectMBO/cardano-ledger/blob/4c81e909df7555b6a58a000db8f8f4f89c7acdc4/eras/shelley/impl/src/Cardano/Ledger/Shelley/PoolRank.hs)
  byte-matched the pinned archive.
- [Binary 1.9.1.0 archive](https://chap.intersectmbo.org/package/cardano-ledger-binary-1.9.1.0.tar.gz), SHA-256
  `05d59229edffc94b8f61273117456601532e23d8656f3076775bbf38e600bb8a`.

The complete reward update stores reserve and fee deltas with inverted signs.
Likelihood uses Float32; native decoding accepts Float16/Float32 and rejects Float64.
PerformanceEstimate is a distinct Double. Raw-word comparison here is stricter than
Haskell likelihood equality after additive-offset normalization.

## Required next checks

1. Run `bash scripts/sbtw scalafmtAll`, focused app suites, and the full public check.
2. Run optional retained-source exporter coverage with the pinned bootstrap input.
3. Establish independent exact-point native component agreement.
4. Review guard removal separately, then execute the bounded 120-second smoke and
   600-second target on dev-host. Require measured overlap, restart identity evidence,
   post-boundary admission/inclusion, exact terminal acquisitions and owned cleanup.
5. Publish only with the actual verification status attached. Preserve failed receipts.
