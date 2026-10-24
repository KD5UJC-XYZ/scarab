<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: incremental rewards (D15, #135)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-incremental-rewards` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: one typed immutable reference-sized pulser, normal start/pulse/complete, forced completion, imported pending-state continuation and V5 restart

## Evidence

`RewardPulserSuite`, native `NativeRewardSemanticsSuite` ports, `RewardOracleSuite`: fifteen unchanged Haskell API cases and 180 encoded-state comparisons; old opaque V5 writer/read/restart/rollback regression; positive pending V5 fuzz seed and malformed imported-state refusal regressions. [Design](../design/reward-pulser.md), [measurements](../performance.md#30-incremental-reward-implementation-135)

## Explicitly unchecked or unconfirmed

New non-myopic likelihoods are implemented under #268; original local and imported API comparisons include every encoded field. Live state now preserves provenance and clears recoverable gaps only at proven replacement boundaries; missing historical observations and operational rollout remain #129. The owner accepted the 15.99 ms normal-completion median on 2026-10-19, superseding the original proposed 5 ms API target. Sampled current-state heap does not establish full volatile-history memory or soak acceptance.
