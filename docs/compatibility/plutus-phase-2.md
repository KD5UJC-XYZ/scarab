<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plutus phase 2 (V1, V2, V3)

Part of the [compatibility matrix](../compatibility.md); the register entry is `plutus-phase-2` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: Scalus 1.4.0 behind `ScriptEvaluator[F]`; native era-specific contexts, ordered preparation, cost models/integrity hashes, language/protocol/Flat gates and corrected builtin runtimes; the cryptographic builtins on the pinned `libscalus_crypto` of scalus-crypto-jni, checked at start-up ([ADR 0020](../adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md))

## Evidence

All 1,009 original upstream term/counting cases pass without exclusions, on the evaluation context and CEK machine production builds, with the two settings of the reference executable that the ledger does not use: counting instead of restricting budgets, and `case` on `Data` allowed (the two `constant-case/data` cases refuse it under the ledger's setting; [profile](../plutus-conformance.md)). Independent unchanged-Haskell costing: 55,740 primary observations across 101 released builtins, all fifteen legal profiles through PV11 and two valid parameter sets; 37,184 exact successful budgets plus 111,552 exact/one-unit-low restricting observations agree. Independent era-specific context matrix: 309 cases, 214 complete ordered requests, 135 typed collection refusals, 189 successful budgets and 25 evaluation failures; [scope and identities](../plutus-conformance.md#independent-costing-and-context-matrix-2026-10-20). Existing signed historical contexts, optional datums, 477 API boundaries and complete V3 boundary transactions remain separately covered

Native scalar text is separately covered by 26 exact observations of the pinned `Data.Text` constructor,
strict UTF-8 decoder, `conText` parser and byte-length measure. The dependency retains normalized
scalar values and their UTF-8 length; the textual conformance ingress preserves supplementary and
separate-surrogate escapes. [Corpus and scope](../../fixtures/reference/plutus-text/README.md). This
bounded representation/parser evidence does not establish native CEK settlement or lazy costing;
[#485](https://github.com/KD5UJC-XYZ/scarab/issues/485) remains separate.

## Explicitly unchecked or unconfirmed

Finite grids do not prove equality for every program, transaction shape or cost vector. API-only derived inputs establish neither signatures nor full admission/collateral effects. Failure consumed units are not independently exposed by the reference API. Exact diagnostic strings remain optional [#208](https://github.com/KD5UJC-XYZ/scarab/issues/208). The native crypto is exercised on Linux/amd64; its ADX/BMI2 code is shown gated by disassembly (`check_native_isa.py`), but no run of `libscalus_crypto` on a CPU without ADX is recorded yet. Linux/aarch64, macOS and Windows load their pinned upstream library, not run here; none is byte-reproduced, and without the library the builtins it backs are typed unavailable and the node refuses to start. Routine V1/V2 admission/effects [#44](https://github.com/KD5UJC-XYZ/scarab/issues/44) and auxiliary-data validation [#286](https://github.com/KD5UJC-XYZ/scarab/issues/286) also pass their combined signed-reference gate; [44 receipts and source identities](../../fixtures/reference/plutus-admission/README.md).
