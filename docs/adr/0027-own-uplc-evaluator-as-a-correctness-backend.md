# 0027. Scarab's own UPLC evaluator, an opt-in correctness backend with no Scalus in it

- **Status**: Accepted
- **Date**: 2026-11-12
- **Issue**: [#43](https://github.com/KD5UJC-XYZ/scarab/issues/43) (roadmap I2)

## Context

[ADR 0003](0003-plutus-through-scalus.md) evaluates Plutus through Scalus and corrects it in our wrapper
where it differs from the reference. The corrections are many and kept recurring (the budget arithmetic
of issue 485, `serialiseData`, `equalsData`, `constrData`, the unlifting bounds, the ECDSA edge cases,
three patches to the Scalus build). Every one was found by testing the whole stack against the reference,
and a misreading that Scalus and our wrapper share would pass those tests.

The owner decided on 2026-11-12 to build a second, independent evaluator as a proof of concept for
correctness checking. Scalus stays the default and the fast path. Speed is not a goal of the new one.

## Decision

- A module `uplc` (`codec <- crypto <- uplc`; `plutus` depends on it) holds Scarab's own evaluator: the term
  model, Flat decoder, CEK machine, builtins, costing, budget and crypto glue. `PlutusData`, `ExUnits`,
  `PlutusLanguage` and `ParamNames`, which were already Scalus-free, moved into it; `plutus` re-exports them
  (`UplcAliases.scala`), so no caller changed.
- **No Scalus in `uplc`, enforced.** The sbt task `checkUplcClasspath`, part of `check`, fails when an
  artifact of the `org.scalus` organisation (this includes `scalus-crypto-jni`) is on the module's compile or
  test class path, and `NoScalusSuite` checks the class path of the test JVM itself. Cryptography comes from
  Bouncy Castle, the `crypto` module and jblst ([ADR 0025](0025-jblst.md)); hash-to-group takes Bouncy Castle's
  hash-to-curve, because jblst passes the domain separation tag as a `String` and cannot carry every byte.
- **It shares no implementation with the Scalus backend.** `ReferenceCosting`, `ReferenceBudget`,
  `FlatScan` and the other Scalus-typed code stay as they are and are not retyped to be shared. The two
  evaluators are independent, so a disagreement between them points at a defect in one.
- **Selection by a flag**, `--plutus-vm scalus|scarab`, default `scalus`, chosen where the evaluator is
  created. The flag arrives with the integration work, not with the module.
- The machine is a functional, explicit-stack transcription of the reference's CEK
  ([ADR 0011](0011-stack-safety.md), [ADR 0002](0002-tagless-final-and-cats-mtl.md); no free monad). It is not
  a fold over the term, because programs loop and budgets depend on the reference's order of charges.
  Recursion schemes are used where the shape fits: Flat decoding, scope and size checks, printing,
  alpha-equality and term generation.
- **Scope**: plutus 1.70.0.0 (`507cab8c`, the pin of node 11.1.3), protocols 5 to 11, V1 to V3. Plutus 1.71.0.0
  (`261766ad`, the Leios testnet build) is a second phase; both corpora are vendored.
- **Authority of the oracles, in order**: the upstream expected results and budgets of the conformance corpus
  (produced by the Haskell evaluator); the costing oracle (budgets from unchanged plutus 1.70.0.0); the
  boundary, Wycheproof and Flat decode corpora; for generated and fuzzed terms, the pinned Haskell evaluator
  itself (`scripts/oracle/plutus`, `deserialiseScript` and `evaluateScriptRestricting` at 1.70.0.0); and last
  differential runs against the Scalus backend, which show disagreement, not truth. The Haskell oracle links
  Debian's libsodium 1.0.18 and libsecp256k1 0.2.0, not the node's builds ([ADR 0020](0020-plutus-native-crypto-through-scalus-crypto-jni.md)),
  so a cryptographic edge case it decides is checked against the node's libraries before it is taken as the
  reference. Every builtin and machine step cites the pinned upstream file and line (ADR 0001).

## Consequences

- With the flag on, the node's phase-2 verdicts come from an evaluator whose bugs are independent of
  Scalus's. A replay that gives different verdicts or execution units under the two backends is a finding
  to investigate against the reference, not to resolve in favour of either.
- Suites that test Scalus itself (the CEK hooks, the Scalus patches, the Scalus native library) run with the
  Scalus backend only. Every report states which suites did not run with `scarab`; none of them counts as
  passed for it.
- A second implementation to keep in step with each pinned plutus release, in return for an independent check.
  It is allowed to be several times slower than Scalus; the default is not expected to change, and changing
  it needs the owner's decision and its own evidence.
- Reopen if the module cannot reach the conformance corpus and the costing oracle without exclusions
  (AGENTS.md), or if it costs more to keep in step than it finds.
