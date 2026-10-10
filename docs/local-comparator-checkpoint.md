# Local comparator checkpoint

Date: 2026-10-10. Base: `b8de8f8ac3ce807bdeb1ebe45b73a42dae76999c`.
Branch: `pico/repeated-epoch-soak-local`.

## Scope

Draft repeated terminal comparator, bounded CBOR reader, checked governance completion,
runtime export hook, test CLI and strict supervisor result verification. New pure
boundaries return typed failures. Subsequent local changes add a restricted
[exact active reward projection](repeated-active-reward-comparison.md), preserving
the active phase, remaining cursor, accumulated/recent rewards and raw likelihood
words, with independently checked source-global bindings. Native/live acceptance
is still pending; historical active-phase refusals remain failed evidence.
The full-soak launcher remains unconditionally disabled. A separate bounded
single-service capture runner now supports an explicitly declared terminal epoch
(zero or one) to establish prerequisite native endpoint agreement.

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
- Initial sbt bootstrap was cancelled by the execution review service. A verified
  portable compiler/dependency closure subsequently enabled offline local compilation.
  All 127 app production and 175 app test sources compiled with Scala 3.3.8 and
  `-Werror`. The first test execution found a future-parameter projection defect:
  post-boundary `PotentialNone` was rejected as though only `NoUpdate` were valid.
  Both constructors are now preserved and compared distinctly. After recompilation,
  all 81 targeted tests passed (48 new/mode cases and 33 finite regressions).
- Final formatting used offline scalafmt 3.8.4 with the repository configuration:
  11 changed Scala files checked, eight formatted, zero differences on a second
  check, and identical pre/post-format Scala ASTs. Fresh post-format compilation
  and the same 81 tests passed; all 302 app source hashes remained stable during
  compilation. Earlier failing and pre-format logs were retained separately.
- The new capture runner passed independent static review and 164 focused controller
  tests. The final Python run had 598 tests: 596 passed, one skipped,
  and the same AF_UNIX environment error. No failure was relabeled as success.
- Optional retained-source exporter coverage has not run. Native endpoint agreement
  and the 120/600-second live cohorts have not run.
- The subsequent combined timing correction and active-reward projection compiled
  freshly offline across ledger, ledger-runtime and app main/test sources with
  Scala 3.3.8 and `-Werror`. All 221 tests across 27 targeted suites passed with no
  failures, skips or assumptions; final affected Scala source hashes matched the
  frozen source manifest. This includes generated active-field mutations and
  timing regressions, not an independent native endpoint or live result. Launch
  guards remain unchanged.
- GitHub push is unavailable in this environment: no authenticated Git helper or gh
  login; push dry-run failed requesting a username with terminal prompts disabled.

This is a locally tested research checkpoint, not a release or live conformance result.
Automatic bundle transfers to dev-host and compiled-artifact transfers back to the cloud
have been demonstrated with matching hashes. dev-host performs E2E preparation/testing
and may push the exact tested branch after validation; source fixes remain local.

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

1. Complete the full public check; targeted tests do not substitute for that aggregate.
2. Run optional retained-source exporter coverage with the pinned bootstrap input.
3. Establish independent exact-point native component agreement.
4. Review guard removal separately, then execute the bounded 120-second smoke and
   600-second target on dev-host. Require measured overlap, restart identity evidence,
   post-boundary admission/inclusion, exact terminal acquisitions and owned cleanup.
5. Publish only with the actual verification status attached. Preserve failed receipts.
