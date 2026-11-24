# 0018. Bounded Plutus workers after ordered ledger preparation

- **Status**: Accepted
- **Date**: 2026-10-18
- **Issue**: [#21](https://github.com/KD5UJC-XYZ/scarab/issues/21)

## Context

The ledger applies transactions in block order. Plutus receives immutable requests, but those
requests must use the outputs and protocol state visible at each transaction. Independent evaluation
can overlap once those contexts exist. The owner authorized this implementation after a captured
preprod study; this replaces the earlier D2 scope restriction to sequential cross-transaction Plutus
execution. Transaction rules, certificates, governance and state publication stay ordered.

## Decision

Run the existing ledger rules once, in order, recording phase-2 requests through a sealed preparation
capability. Deferral supplies no evaluation verdict. Only an opaque block plan owns the resulting
tentative state, and its resolver checks every reached verdict and validity flag before yielding an
applied result. Conditional effects during preparation use the advertised validity flag, exactly as
the serial rules do after checking that flag. No subsequent rule reads phase-2 counters to decide
ledger effects. The serial entry points remain the executable reference.

Evaluate transaction jobs in bounded dependency batches, then land results and observations in
original order. Ordinary transactions transmit dependencies. Include normal, reference and collateral
reads, read-before-consume edges and conditional collateral returns. Scripts inside a transaction
keep collection order. I4 #195 subsequently established the released reference's accumulating result
policy: every script now executes, and all failures, budgets and
logs are retained ([as-built result contract](../design/script-results.md)). This refines evaluation
inside jobs; the scheduling and ordered landing contract is unchanged.

One node-wide semaphore bounds concurrent machines across block callers. Joined workers return values;
raw defects land as their original exceptions in the owner, retaining the fatal classification of
ADR 0017. Cancellation waits for the underlying machine before releasing its permit. Preparation retains its original defect alongside any earlier recorded jobs. Ordered landing
checks those jobs first, so an earlier script refusal wins, and otherwise raises that original defect
without retrying or hiding it. One worker, or at most one redeemer-bearing transaction, uses the serial fast path.

## Consequences

The preparation recorder is one instance-local builder bounded to one block, hidden behind the pure
plan factory. This is the performance-critical exception to imperative implementation: threading a
second full ledger pass through the runtime would repeat certificate, governance, stake and UTxO work
on the profiled serial bottleneck. The design avoids that repeated traversal and fake script verdicts;
benchmarks include preparation, collection, scheduling and ordered landing, not just VM time. No
background fibers or construction-time error capabilities are introduced. Ephemeral plans do not
change persisted formats.

Default concurrency is the smaller of four and available processors. Operators can select an exact
serial comparison with `--plutus-workers 1`. The detailed invariant, measurements, reference links,
limits and regression controls are in [the as-built design](../design/parallel-plutus.md).

**Acceptance addendum (2026-10-20).** The captured Preview failure/digest reconciliation is
complete under [#21](https://github.com/KD5UJC-XYZ/scarab/issues/21), closed after PR #209.
The [recorded acceptance](../design/parallel-plutus.md#preview-reconciliation-gates)
retains projection exclusions and responsiveness warnings; nightly CI remains the continuing
safety net.

**Amendment (2026-11-22): the era block runs.** An era block run is a body program under the same
contract, run by the same node-wide scheduler and bound ([#85](https://github.com/KD5UJC-XYZ/scarab/issues/85),
Conway's first). Its preparation runs the era rules once, in order: UTXOS hands the scripts it
collected for each transaction to the run's deferral capability, which records them in the sealed
recorder and supplies no verdict, and the rules continue as the transaction's flag announces. The
dependency batches read the same footprints as the flat rules' (normal, reference and collateral
reads, consumes and creates by the flag). Results and observations land in transaction order, and
while every reached verdict is its flag's, the preparation's result, with counters told from the
landed verdicts where each job was deferred, is the serial run's. A job's raw defect lands as
itself after the jobs before it. The era rules accumulate every failure (`ValidateAll`) and a
recorded mismatch changes what follows it (`whenFailureFree`, the state a no-verdict is named in),
so at the first verdict that is not its flag's, or no verdict, the result is the serial run itself,
every script answered in order by the landed results rather than evaluated again. A raw defect the
preparation ended in after that point is then the serial rules' to reach again: that pass runs them,
and a defect it meets is raised from it, as the serial run raises it (after a mismatch it still
does, since the rules go on; after a script with no verdict it never does). That extra ordered
pass happens only for a block the node refuses or withholds. Dijkstra's run evaluates its
scripts where UTXOS collects them until it adopts the same program. The serial era runs remain the
executable reference; the [as-built design](../design/parallel-plutus.md#era-block-runs) has the
detail.

**Amendment (2026-11-23): a rule defect after a recorded failure.** Conway's era block run refuses a
block on the failures its rules recorded before a defect of this implementation stopped the run,
with the defect kept beside them, and leaves no verdict only where nothing was recorded before it
([#85](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7104253607)). The contract above
is unchanged: the preparation's result, that refusal included, is the serial run's while every
reached verdict is its flag's, and the serial run itself otherwise: one that records a mismatch records
it before the defect, and one that reaches a script with no verdict ends there, before the defect. The [as-built design](../design/parallel-plutus.md#era-block-runs) has the detail.

**Amendment (2026-11-24): Dijkstra's era block run adopts the program.** The Leios build's Dijkstra
blocks run as a body program under the same contract, scheduler and bound
([#85](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7067709618)), replacing the
2026-11-22 amendment's sentence that Dijkstra's run evaluates where UTXOS collects its scripts. A job is
a batch: its top level's and its sub-transactions' scripts, which its top level's UTXOS collects and
evaluates together and checks against the top level's flag. Each script's context is built in the ordered
preparation from the UTxO set before its batch, as the serial rules build it. While every reached verdict
is its flag's, the preparation is the serial run, so each result was evaluated against the state the
serial run evaluates it against. At the first verdict that is not its flag's, or no verdict, the result
is the serial run itself. There a result evaluated ahead answers a script only when its request is the
very one the serial run asks, the script's whole context included; any other script is evaluated in
order. The dependency batches read every level's footprint: reads, consumes and creates, by the top
level's flag. They order the evaluation and never decide a verdict. Header checks and what follows
BBODY stay in the block's ordered finish, after the program's result, in `applyHelper`'s order. The
naming of a script with no verdict and the phase-2 counters are the serial run's at every worker count.
The [as-built design](../design/parallel-plutus.md#era-block-runs) has the detail.
