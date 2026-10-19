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
keep the existing collection order and first-failure behavior. This changes scheduling, not the
existing failure aggregation policy (the remaining reference correspondence is tracked in #176).

One node-wide semaphore bounds concurrent machines across block callers. Joined workers return values;
raw defects land as their original exceptions in the owner, retaining the fatal classification of
ADR 0017. Cancellation waits for the underlying machine before releasing its permit. Preparation
defects fall back to exact serial execution so a later speculative defect cannot overtake an earlier
script refusal. One worker, or at most one redeemer-bearing transaction, uses the serial fast path.

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
limits and regression controls are in [the as-built design](../design/parallel-plutus.md). Broader D2
Preview failure/digest reconciliation and nightly acceptance remain open in #21.
