<!-- SPDX-License-Identifier: Apache-2.0 -->
# The reference's budget arithmetic in the evaluator

Part of the [performance log](../performance.md).

## 44. The reference's budget arithmetic in the evaluator (#485)

`ScalusEvaluator.CorrectedVm` now costs every builtin with `ReferenceCosting` (argument measures as
cost streams, costing functions in `SatInt`) and spends through `ReferenceBudget` (steps counted
and charged per kind every 200 steps, a saturating remaining budget). Both spenders keep mutable
counters because Scalus's `BudgetSpender` is a mutable callback that signals an exhausted budget
with an exception, as the reference's spender is a mutable counter array and a thrown machine
error; one instance serves one evaluation on one thread. A cost stream of one element (every
argument but a `Data` value, a list of 100 elements or more and a text of more than 100 code
points) is a single value, not a collection.

The replay of `BuiltinCostingOracleSuite` (55,740 evaluations and 111,552 budget-boundary
evaluations over 1,858 scripts in 15 profiles), summed over its 30 profile tests, second run in the
same JVM (first run in parentheses), Docker JDK 21.0.11, October 25 2026, on a shared host:

<!-- register:table perf-50.t1 -->
| build | seconds |
|---|---:|
| before (PR #493 at `91b1cd4a`, Scalus's spender and costing) | 22.9 (23.6) |
| first version, every cost stream a `Vector` | 25.9 (26.2) |
| after (single-cost fast path) | 21.3 (21.3) |
<!-- /register -->

The differences between before and after are within the run-to-run variation of the shared host;
the upstream conformance corpus (1,009 cases) takes 0.5 to 0.7 s in every build.
