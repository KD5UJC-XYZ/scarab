<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plutus scripts collected and evaluated ahead of the selection worker (#1499)

Part of the [compatibility matrix](../compatibility.md); the register entry is `scripts-ahead` in [compatibility.json](../data/compatibility.json).

## Status as recorded

Implemented as an Optimisation (REF-6), approved on 2026-11-27 for [#1499](https://github.com/KD5UJC-XYZ/scarab/issues/1499) item 4. The speculator collects and evaluates the Plutus scripts of the blocks waiting in the add-block queue, on threads of its own. The rules of the eras from Alonzo to Conway take a kept collection only where it is their own collection: equal inputs and the same answers of the clock. The evaluator takes a kept result only for an equal request. Verdicts, budgets, traces, refusals, events and observations do not change; only when a script is collected and evaluated does. Design: [parallel Plutus](../design/parallel-plutus.md#scripts-collected-and-evaluated-ahead-of-the-worker).

## Evidence

- **Equivalence.** `ScriptsAheadSuite` applies recorded preprod script transactions (`PreparedBlockFixture`) with what was kept and without it, serially and through four workers, and compares state, refusals and every phase-2 observation. Schedules:
  - all found;
  - an input made by an earlier queued block;
  - an input changed, as after a rollback;
  - parameters changed at an epoch boundary;
  - another era history;
  - failing scripts with either validity flag;
  - collections taken without results.
- **Conway.** `ScriptsAheadKeySuite` collects a scripted Conway block with the speculator's own collector and applies it through Conway's era run. The key's every part is changed alone, with a property over any set of changes. `ContextInputsSuite` covers the scripts used, compared strictly. `ScriptsAheadSameSuite` holds the comparison of requests to `==`.
- **Mutation checks.** Each part of the key was dropped from the comparison in turn, and each change fails a test.
- **Scenarios.** The relay, restart and e2e devnet scenarios pass. With the node at four processors, the e2e scenario's rules took the speculator's collections.

## Explicitly unchecked or unconfirmed

Not measured on mainnet by this change's author:
- the hit rate;
- the cost of a hit on mainnet contexts (the full key was measured on recorded Preview and preprod transactions; mainnet transactions were compared alone);
- why results were abandoned on the probe (`scarab_plutus_ahead_reached_total` says where each block's speculation was).

Not tested: a recorded scripted window speculated and applied through `MirrorRules` (#1499 item). Not speculated: Dijkstra blocks of the Leios build. The thread count is unchanged: a quarter of the processors, at most two, and none below four, so the devnet's two-processor node does not speculate.
