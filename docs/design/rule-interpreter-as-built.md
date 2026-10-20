# The rule interpreter, as built (#202)

The pure `RuleT[Fail, S, A]` interpreter behind every `Sts` ledger rule. This note is the as-built
description the ticket asks for: the shape of the machine, why it is typed the way it is, and the
evidence that the ledger verdicts do not change.

## What was wrong

`RuleT` kept its continuation stack in a `List[Frame[Fail, S]]`, where `Frame.Next` stored its
intermediate result as `Any`, recovered with `asInstanceOf`. `Prog.Bind` is typed at construction,
so no wrong verdict was demonstrated — the erasure was a hole in the type discipline, not a bug:
the interpreter could not be checked, only trusted.

## The machine

The stack is now a type-aligned sequence over the program's result type:

```scala
private[rules] enum Frames[Fail, S, X, A]:
  case Done[Fail, S, A]() extends Frames[Fail, S, A, A]
  case Cons[Fail, S, X, Y, A](head: Frame[Fail, S, X, Y], tail: Frames[Fail, S, Y, A])
    extends Frames[Fail, S, X, A]
```

`Done` says "a value of the program's own type is the answer"; `Cons` says "the rest of the program
expects exactly the type this frame will produce". Each frame case carries its own typing:

- `Next[X, Y]` — the continuation of a `Bind`: it consumes an `X` (the left side's result) and
  produces a `Y`; the rest of the stack is typed from `Y`.
- `Handler[A]` — the recovery branch of a `Handle`: it receives the failure and produces an `A`.
- `Restore[A]` — scoped `Globals` restoration: it receives the operation's result and reinstates
  the globals the scope entered with.

The loop is one polymorphic, self-tail-recursive method `go[X](prog)(frames: Frames[Fail, S, X, A])`,
so a call to `go` at any step type-checks against the frames' `X` type at that step. There is no
cast, no `@unchecked`, and no `Any` value channel anywhere in the file; the compiler enforces that
each continuation receives its predecessor's exact result type — across `Bind`, `Handle` recovery
and scoped globals.

## What is preserved

- **Laws**: `Monad`, `Local`, `Stateful`, `Handle` — the suites are unchanged and green.
- **Failure rollback**: state changes before a failure are rolled back to the scope's entry state.
- **`Globals` restoration**: scoped globals are reinstated on both the success and failure paths.
- **Stack safety**: the 300,000-step case and a block of 10,000 transactions run on a 256 KiB
  thread stack.
- **Verdicts**: the differential suite compares real Tick and BBODY results with the
  `Kleisli`/`StateT`/`Either` reference stack, including failures; results agree exactly.

## Measurement

Old (the cast-based machine) and new (this machine) are measured by one harness,
`ledger/src/test/scala/xyz/kd5ujc/scarab/bench/CastErasureBench.scala`
(`ledger/Test/runMain xyz.kd5ujc.scarab.bench.CastErasureBench rules 10000`): the same block of
10,000 transactions applied through `Sts(Bbody)`, three warm-up runs and five measured runs,
pinned CPUs (`--cpuset-cpus`, no CFS quota), one JVM, allocation counted per thread with
`ThreadMXBean.getCurrentThreadAllocatedBytes`. The `DIGEST rules` line of both builds must be
identical (the applied state's count); the table is filled from the rutherford run.

| build | median ms | min ms | max ms | allocated bytes (median) |
|---|---|---|---|---|
| old (dev) | _to fill_ | | | |
| new (#202) | _to fill_ | | | |

## Gates

- `check` on rutherford (documented non-root `--user` invocation, `--cpuset-cpus 0-7`): **1077 tests,
  one failure** — `ChainStorageSuite`'s 3-minute munit timeout on that host (the same environmental
  class as #213; the suite passes on the CI runners). Every ledger, rules and consensus suite passed,
  including the small-stack and differential suites. `VolatileStoreSuite` is excluded for the same
  documented reason.
- `check` on the exact head in CI: the `scalafmt, compile and test` job (the full-suite run on
  GitHub runners, where the two long storage suites fit their budgets).
- The ledger area scenario (`python3 scripts/devnet.py --name <name> e2e`) on the reference image
  `cardano-reference-11.1.3:local`.
