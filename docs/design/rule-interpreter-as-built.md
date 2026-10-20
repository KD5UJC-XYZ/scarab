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
`ledger/src/test/scala/xyz/kd5ujc/scarab/bench/CastErasureBench.scala` (the same file, byte for
byte, as the one added by the performance chapter, `#250`):
`ledger/Test/runMain xyz.kd5ujc.scarab.bench.CastErasureBench rules 10000`. It applies the same
block of 10,000 transactions through `Sts(Bbody)` with one fixed Ed25519 signing key (so both builds
receive identical inputs), three warm-up runs and five measured runs, pinned CPUs
(`--cpuset-cpus`, no CFS quota), one JVM, allocation counted per thread with
`ThreadMXBean.getCurrentThreadAllocatedBytes`. The sequence is interleaved — old, new, old, new —
so session drift cannot be mistaken for a difference between builds.

`DIGEST rules` is the SHA-256 of the complete applied ledger state (rendered outside the timed
region), not a count: identical digests mean the two builds produced the same state, including
balances, outputs and fees. It was `159017f549d74b4d4cb6a520` on all four runs.

| build | median ms (two runs) | allocated bytes (each run) |
|---|---|---|
| old (`dev` `57608c6e`) | 3159.9 / 3175.7 | 5,223,889,104 / 5,230,049,136 |
| new (`#202` `9d2fa735`) | 3073.9 / 3256.9 | 5,216,691,040 / 5,227,079,360 |

The two builds are inside each other's span on this host: the new build is 2.7% faster on one
paired run and 1.5% slower on the other, and the old build's own two runs differ by 0.5%. The
result is "no measurable change", not a speed-up.

**The issue's pinned Preview block/epoch workload is not measured here.** The 10,000-transaction
block is synthetic; it is additional evidence for the same code path, not a substitute for #202's
acceptance criterion. No Preview capture is in-tree and none was produced for this PR. The ledger
`e2e` area scenario is likewise outstanding: it failed with `follower exited with 1` before any era
comparison (the follower's `<run>/classes` directory was empty at teardown), and its checkbox below
stays unchecked.

## Gates

- `check` on rutherford (documented non-root `--user` invocation, `--cpuset-cpus 0-7`): **1077 tests,
  one failure** — `ChainStorageSuite`'s 3-minute munit timeout on that host (the same environmental
  class as #213; the suite passes on the CI runners). Every ledger, rules and consensus suite passed,
  including the small-stack and differential suites. `VolatileStoreSuite` is excluded for the same
  documented reason.
- `check` on the exact head in CI: the `scalafmt, compile and test` job (the full-suite run on
  GitHub runners, where the two long storage suites fit their budgets).
- The ledger area scenario (`python3 scripts/devnet.py --name <name> e2e`) on the reference image
  `cardano-reference-11.1.3:local`: **not passing** — the reference cluster comes up, then
  `follower exited with 1` before any era comparison (the follower's `<run>/classes` directory was
  empty at teardown), so there is no per-era verdict. This is the one gate still open, and it is a
  harness/host defect, not a verdict from either interpreter.
