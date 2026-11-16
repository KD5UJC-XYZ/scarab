<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plutus phase 2 (V1, V2, V3)

Part of the [compatibility matrix](../compatibility.md); the register entry is `plutus-phase-2` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row; the status was brought up to date on 2026-11-15 for the removal of the Scalus backend.

## Status as recorded

implemented: Scarab's own evaluator (`uplc`) behind `ScriptEvaluator[F]`, the node's only one since 2026-11-15 ([ADR 0027, amendment of 2026-11-15](../adr/0027-own-uplc-evaluator-as-a-correctness-backend.md#amendment-2026-11-15-the-own-evaluator-is-the-only-evaluator), [its page](plutus-own-evaluator.md)); native era-specific contexts, ordered preparation, cost models/integrity hashes, language/protocol/Flat gates; the cryptographic builtins on the JVM and, for BLS12-381, on jblst's libblst, checked at start-up ([ADR 0025](../adr/0025-jblst.md)). Until 2026-11-15 the node's default evaluator was the Scalus backend, with corrected builtin runtimes and its cryptographic builtins on `libscalus_crypto` ([ADR 0020](../adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md))

## Evidence

Recorded on the Scalus backend; the own evaluator's record of the same corpora is on [its page](plutus-own-evaluator.md). All 1,009 original upstream term/counting cases pass without exclusions, and the 906 of them with a Flat form pass from their `.flat` files too, on the evaluation context and CEK machine production builds, with the two settings of the reference executable that the ledger does not use: counting instead of restricting budgets, and `case` on `Data` allowed (the two `constant-case/data` cases refuse it under the ledger's setting; [profile](../plutus-conformance.md)). Independent unchanged-Haskell costing: 55,740 primary observations across 101 released builtins, all fifteen legal profiles through PV11 and two valid parameter sets; 37,184 exact successful budgets plus 111,552 exact/one-unit-low restricting observations agree. Independent era-specific context matrix: 309 cases, 214 complete ordered requests, 135 typed collection refusals, 189 successful budgets and 25 evaluation failures; [scope and identities](../plutus-conformance.md#independent-costing-and-context-matrix-2026-10-20). The same unchanged observer collects the contexts of the 45 recorded Preview transactions, the 4 recorded preprod ones and a vote of five voters: 259 requests and 2 refusals that the node's equal ([recorded contexts](../plutus-conformance.md#recorded-transactions-contexts-43-2026-11-16)). Existing signed historical contexts, optional datums, 477 API boundaries and complete V3 boundary transactions remain separately covered

Native scalar text is separately covered by 26 exact observations of the pinned `Data.Text` constructor,
strict UTF-8 decoder, `conText` parser and byte-length measure. The dependency retains normalized
scalar values and their UTF-8 length; the textual conformance ingress preserves supplementary and
separate-surrogate escapes. [Corpus and scope](../../fixtures/reference/plutus-text/README.md). This
bounded representation/parser evidence does not establish native CEK settlement or lazy costing;
the budget arithmetic is covered separately ([IN-84](../implementer-notes.md#in-84-scalus-the-budget-arithmetic-under-negative-or-extreme-cost-model-parameters)).

## Explicitly unchecked or unconfirmed

Finite grids do not prove equality for every program, transaction shape or cost vector. API-only derived inputs establish neither signatures nor full admission/collateral effects. Failure consumed units are not independently exposed by the reference API. Complete diagnostic parity is required and incomplete under [#796](https://github.com/KD5UJC-XYZ/scarab/issues/796); see the [ADR 0001 amendment](../adr/0001-haskell-node-as-reference.md#amendment-2026-11-04-exact-diagnostic-and-refusal-parity). The native library of the BLS12-381 builtins is jblst's libblst; what is and is not checked of it is on [platforms and CPUs](platforms-and-cpus.md). Routine V1/V2 admission/effects [#44](https://github.com/KD5UJC-XYZ/scarab/issues/44) and auxiliary-data validation [#286](https://github.com/KD5UJC-XYZ/scarab/issues/286) also pass their combined signed-reference gate; [44 receipts and source identities](../../fixtures/reference/plutus-admission/README.md).
