<!-- SPDX-License-Identifier: Apache-2.0 -->
# Bounded parallel Plutus (D2)

Part of the [compatibility matrix](../compatibility.md); the register entry is `bounded-parallel-plutus` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

complete: ordered ledger preparation, dependency batches, shared bound for block-evaluation jobs and ordered typed verdict landing; serial reference retained

## Evidence

`PreparedBlockSuite`, `TxDependenciesSuite`, `ParallelPlutusSuite` (including retained transient defects), CLI controls; final source `7ba8c7f3`: Docker check 4,819 passed/16 skipped, fuzz and scalafix green; 68,196 feature-versus-serial full-state comparisons over 5,683 captured blocks (104,388 reached scripts per pass); seven-era e2e 1,687 blocks and 66 equal projected ledger comparisons; pool 34 forged/34 adopted, no missed scheduled leaders and 7/7 equal comparisons. Both scenarios had zero validation/fatal/reconnect/starvation events. [Design](../design/parallel-plutus.md), [performance and capture binding](../performance.md)

## Explicitly unchecked or unconfirmed

Current Preview reconciliation also passes: 4,758,521 continuous original blocks, all 1,451 authentic reference projections and exact ordered 242 invalid transactions; all 68 failure windows agree in serial/four-worker modes. Captured epoch-135 summed validation time is 15.1% lower at four workers; exact scope/setup below and in performance section 33. K1 retains its historical projection exclusions and six responsiveness warnings. [#21](https://github.com/KD5UJC-XYZ/scarab/issues/21) is closed at the owner's direction after [PR #209](https://github.com/KD5UJC-XYZ/scarab/pull/209) merged; the owner will follow up on any post-merge CI failure. Optional exact failure diagnostic wording is separately tracked in [#208](https://github.com/KD5UJC-XYZ/scarab/issues/208).
