# 0027. Scarab's own UPLC evaluator, an opt-in correctness backend with no Scalus in it

- **Status**: Accepted
- **Date**: 2026-11-12
- **Issue**: [#43](https://github.com/KD5UJC-XYZ/scarab/issues/43) (roadmap I2)

## Context

[ADR 0003](0003-plutus-through-scalus.md) evaluates Plutus through Scalus and corrects it in our wrapper
where it differs from the reference. The corrections are many and kept recurring (the budget arithmetic
of issue 485, `serialiseData`, `equalsData`, `constrData`, the unlifting bounds, the ECDSA edge cases,
three patches to the Scalus build). Every one was found by testing the whole stack against the reference,
and a misreading that Scalus and our wrapper share would pass ~~those tests~~ ([amended 2026-11-12](#amendment-2026-11-12-what-the-backends-share-forging-protocol-12-and-the-aim)).

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
- **~~It shares no implementation with the Scalus backend.~~** `ReferenceCosting`, `ReferenceBudget`,
  `FlatScan` and the other Scalus-typed code stay as they are and are not retyped to be shared. ~~The two
  evaluators are independent, so a disagreement between them points at a defect in one.~~ ([amended 2026-11-12](#amendment-2026-11-12-what-the-backends-share-forging-protocol-12-and-the-aim))
- **Selection by a flag**, `--plutus-vm scalus|scarab`, default `scalus`, chosen where the evaluator is
  created. The flag arrives with the integration work, not with the module.
- The machine is a functional, explicit-stack transcription of the reference's CEK
  ([ADR 0011](0011-stack-safety.md), [ADR 0002](0002-tagless-final-and-cats-mtl.md); no free monad). It is not
  a fold over the term, because programs loop and budgets depend on the reference's order of charges.
  Recursion schemes are used where the shape fits: Flat decoding, scope and size checks, printing,
  alpha-equality and term generation.
- **Scope**: plutus 1.70.0.0 (`507cab8c`, the pin of node 11.1.3), protocols 5 to 11, V1 to V3. Plutus 1.71.0.0
  (`261766ad`, the Leios testnet build) ~~is a second phase~~ ([amended 2026-11-12](#amendment-2026-11-12-what-the-backends-share-forging-protocol-12-and-the-aim)); both corpora are vendored.
- **Authority of the oracles, in order**: the upstream expected results and budgets of the conformance corpus
  (produced by the Haskell evaluator); the costing oracle (budgets from unchanged plutus 1.70.0.0); the
  boundary, Wycheproof and Flat decode corpora; for generated and fuzzed terms, the pinned Haskell evaluator
  itself (`scripts/oracle/plutus`, `deserialiseScript` and `evaluateScriptRestricting` at 1.70.0.0); and last
  differential runs against the Scalus backend, which show disagreement, not truth. The Haskell oracle links
  Debian's libsodium 1.0.18 and libsecp256k1 0.2.0, not the node's builds ([ADR 0020](0020-plutus-native-crypto-through-scalus-crypto-jni.md)),
  so a cryptographic edge case it decides is checked against the node's libraries before it is taken as the
  reference. Every builtin and machine step cites the pinned upstream file and line (ADR 0001).

## Consequences

- With the flag on, the node's phase-2 verdicts come from an evaluator ~~whose bugs are independent of
  Scalus's~~ ([amended 2026-11-12](#amendment-2026-11-12-what-the-backends-share-forging-protocol-12-and-the-aim)). A replay that gives different verdicts or execution units under the two backends is a finding
  to investigate against the reference, not to resolve in favour of either.
- Suites that test Scalus itself (the CEK hooks, the Scalus patches, the Scalus native library) run with the
  Scalus backend only. Every report states which suites did not run with `scarab`; none of them counts as
  passed for it.
- A second implementation to keep in step with each pinned plutus release, in return for an independent check.
  It is allowed to be several times slower than Scalus; the default is not expected to change, and changing
  it needs the owner's decision and its own evidence.
- Reopen if the module cannot reach the conformance corpus and the costing oracle without exclusions
  (AGENTS.md), or if it costs more to keep in step than it finds.

## Amendment (2026-11-12): what the backends share, forging, protocol 12 and the aim

An independent review of the change that introduced this record found its independence claim too broad, and
the owner decided the scope of the flag the same day ([#43 scope comment](https://github.com/KD5UJC-XYZ/scarab/issues/43#issuecomment-6828920405)).

- **What the comparison of the backends can show.** The machine, builtins, costing and Flat decoder are
  implemented separately from the Scalus backend, so a disagreement between the backends points at a defect in
  one of them. Both still share the `PlutusData` type and its CBOR decoder (`FlatScan` reads `data` constants
  through `PlutusData.decodePrefix`), and the ledger code around evaluation: script-context construction,
  cost-model lists, redeemers and the phase-1 rules (`Phase2`). A fault there is common to both backends and no
  comparison of them can show it; a misreading shared by Scalus and our wrapper passes every case the tests
  against the reference do not cover. The own evaluator lowers the risk of a fault common to both; it does not
  make the backends' faults independent.
- **With `--plutus-vm scarab`**, both phase-2 evaluation and script admission use the own evaluator. ~~The node
  refuses `--forge` at start-up until the evidence of the gates is in~~ ([amended 2026-11-13](#amendment-2026-11-13-the-own-evaluator-forges)): a disagreement with the reference while
  following stays on this node, which stalls on the block, but a forged block whose transaction carries a wrong
  validity flag is refused by every other node (`ValidationTagMismatch`, cardano-ledger `f649f975`,
  `eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxos.hs`). ~~The owner may lift the refusal then.~~ ([amended 2026-11-13](#amendment-2026-11-13-the-own-evaluator-forges))
- **No protocol 12.** The backend refuses protocol 12 and later, the Leios testnet's Dijkstra, with a typed
  `Unsupported` failure; plutus 1.71.0.0 is out of scope for now.
- **The aim, not a gate:** a mainnet sync from genesis to tip with only the own evaluator. Speed stays secondary
  but must keep such a sync practical. A rough extrapolation from late-Alonzo samples of 6 to 11 redeemers per
  block (table perf-20.t1 of [the mainnet follower measurements](../performance/mainnet-follower.md)) puts
  mainnet from Alonzo to tip in the tens of millions of evaluations; the total has not been counted.

## Amendment (2026-11-13): the own evaluator forges

The owner decided on 2026-11-13 that the node may forge with the own evaluator: `--forge` is taken with
`--plutus-vm scarab` as it is with `scalus`, and the start-up refusal of the amendment above is removed.

- **What was in at the decision:** the gates of the amendment above, recorded in
  [plutus-conformance.md](../plutus-conformance.md#scarabs-own-evaluator) and on
  [the compatibility page](../compatibility/plutus-own-evaluator.md), among them a devnet `e2e` run and a replay of
  Preview from genesis that matched the reference's ledger state at every epoch boundary. The replay of preprod
  and a devnet `pool` run in which the node forges Plutus transactions with the own evaluator are the evidence
  that follows the decision; the compatibility page records them.
- **The risk the refusal named stays.** The forger, the mempool and the rules run the one backend of the run, so
  a wrong verdict at admission puts a transaction with a wrong validity flag in a block this node forges, and
  every node that follows the reference refuses that block (`ValidationTagMismatch`, as above): the pool loses
  the slot. The node's own check of the block it forged runs the same evaluator, so it does not catch this.
- **Unchanged:** Scalus stays the default, and changing it needs a further decision. The own evaluator still
  gives no verdict at protocol 12 and later, and the node treats that as it treats any missing verdict
  ([ADR 0017, amendment of 2026-10-28](0017-fibers-end-with-values.md#amendment-2026-10-28-no-verdict-on-a-block-a-held-transaction-or-our-own-block-does-not-stop-the-node)):
  a block that needs one is not adopted, a transaction that needs one is not admitted, and a block of its own
  whose check gets none is not published, its transactions leave the mempool and the forger goes on. The
  forger forges on a Conway ledger only, so it forges no block of a later era with either evaluator.
