<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plutus phase 2 (V1, V2, V3)

Part of the [compatibility matrix](../compatibility.md); the register entry is `plutus-phase-2` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: Scalus 1.3.0 behind `ScriptEvaluator[F]`; native era-specific contexts, ordered preparation, cost models/integrity hashes, language/protocol/Flat gates and corrected builtin runtimes

## Evidence

All 1,009 original upstream term/counting cases pass without exclusions. Independent unchanged-Haskell costing: 55,740 primary observations across 101 released builtins, all fifteen legal profiles through PV11 and two valid parameter sets; 37,184 exact successful budgets plus 111,552 exact/one-unit-low restricting observations agree. Independent era-specific context matrix: 309 cases, 214 complete ordered requests, 135 typed collection refusals, 189 successful budgets and 25 evaluation failures; [scope and identities](../plutus-conformance.md#independent-costing-and-context-matrix-2026-10-20). Existing signed historical contexts, optional datums, 477 API boundaries and complete V3 boundary transactions remain separately covered

## Explicitly unchecked or unconfirmed

Finite grids do not prove equality for every program, transaction shape or cost vector. API-only derived inputs establish neither signatures nor full admission/collateral effects. Failure consumed units are not independently exposed by the reference API. Exact diagnostic strings remain optional [#208](https://github.com/KD5UJC-XYZ/scarab/issues/208). Corrected crypto is exercised on Linux/amd64 including portable/no-ADX smoke; other targets retain explicit unavailable validation until built and verified. Routine V1/V2 admission/effects [#44](https://github.com/KD5UJC-XYZ/scarab/issues/44) and auxiliary-data validation [#286](https://github.com/KD5UJC-XYZ/scarab/issues/286) also pass their combined signed-reference gate; [44 receipts and source identities](../../fixtures/reference/plutus-admission/README.md).
