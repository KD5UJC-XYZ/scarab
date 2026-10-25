# The rule interpreter, as built (#202)

**Status:** As built; acceptance evidence below.

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

## The judgment (#412)

The reference's small-steps interpreter (cardano-ledger `f649f975`,
`libs/small-steps/src/Control/State/Transition/Extended.hs:668-730`) never aborts a rule on a
predicate failure: it records the failure and continues, runs every sub-rule (`trans`), and skips a
step only under `whenFailureFree`/`ifFailureFree`, which read a flag that is set once anything was
recorded anywhere in the judgment. The machine now carries that judgment:

- `RuleT[Rec, Fail, S, A]` records predicate failures of type `Rec` and raises `Fail`. A rule that
  records nothing runs in `RuleT.Of[Fail, S]`, which is `RuleT[Nothing, Fail, S, *]`, as before.
- Three program nodes: `Record(failures)` (`Tell`), `Failing()` (the flag) and `Nest(program)` (the
  boundary of a sub-rule). The machine state gains the current rule's recorded `Trace` (`Chain.nil`
  until something fails, so a valid transaction allocates none) and the failing flag.
- A `Frame.Unnest(parent)` closes a nest: the failures recorded inside become one `Trace.Node` of
  the parent's, after what the parent had recorded; a nest that recorded nothing adds nothing.
  `Frame.Handler` saves and restores the trace and the flag together with the state.
- The capability is `Judge[F, Fail]`, a cats-mtl `Tell[F, Chain[Fail]]` with `failing`, `nest` and
  `contramap` (`wrapFailed`). `Sts.whenFailureFree`, `Sts.whenFailureFreeDefault` and
  `Sts.ifFailureFree` read the flag.
- `RuleT.Policy`: `Accumulate` records and continues; `FirstFailure` ends the judgment at the first
  recorded failure, as a raise does. A program that records nothing runs identically under both.
- `Judgment.flatten` puts the recorded failures in the reference's order. The reference prepends a
  predicate's failures to the rule's list (`Extended.hs:708`) and folds a sub-rule's list in one
  element at a time (`Extended.hs:720-722`), which reverses it, so the order alternates with the
  depth of the nest from the applied rule. The flatten walks an explicit stack of pending blocks
  with their direction (ADR 0011): linear in the failures, constant in the JVM stack.
- `Judgment.run(rule, policy)` applies a rule and returns a defect, the refusal's predicate failures
  (`NonEmptyChain`), or the state. `Refusal[Fail]` splits a raised failure into the two; for the
  ledger, `LedgerFailure.Rejected` holds the predicate failures and the `LedgerDefect` cases
  (`Undecodable`, `Unavailable`, `Vocab`) are defects.

`JudgmentSuite` checks the order against a literal port of `Extended.hs:703-722` on random rule
trees, the certificate recursion of CERTS and DELEGS (`gamma :|> c`) for one to eight certificates
in the Babbage and Conway nesting, the flag, the two policies, `Monad` and `Tell` laws, and judgments
of 100,000 failures, nests and certificates on a 256 KiB stack.

The rules still raise their predicate failures, and every caller runs them under `FirstFailure`,
so verdicts and refusals are unchanged. Rules record through `Judge` and `Sts.trans` becomes a
nest when the rules are moved over to recording.

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
paired run and 2.6% slower on the other, and the old build's own two runs differ by 0.5%. These
historical samples do not establish a speed-up or a confidence interval.

The synthetic workload is supplementary. The representative Preview block/epoch comparison,
its input and source identities, full sample summary, diagnostic warmup investigation and limits
are recorded in [performance.md](../performance/cast-erasure-interpreter.md#typed-rule-interpreter-202).

## Verification

`StsSuite` explicitly runs `MonadTests` alongside the MTL laws; it and `TickRulesSuite`/`BbodySuite` retain the original semantics, including
rollback and scoped globals on success and failure. The production interpreter is unchanged
since the reviewed implementation. No persisted format or rule change is part of this work.

The devnet launch investigation confirmed that the old driver could return `/run/classes/*`
entries after skipping every missing source directory. Reproducing that configuration in Docker
exits 1 with `ClassNotFoundException: xyz.kd5ujc.scarab.node.Main`. The driver now validates all
module directories before replacing an earlier freeze, refuses empty classes or classpaths from
another build mount, and diagnoses the required `node/runtimeClasspathFile` build. Four regression
tests fail against the old driver; all 227 Python tests pass with the repair. This explains a
reproducible failure mechanism, not the historical host failure whose stderr was not preserved.

The classpath for verification was generated in this worktree mounted at `/work`, with the shared
dependency cache mounted at `/work/.cache`. The frozen module directories and dependency mounts
were inspected before launch. The complete Docker `check` at `83c76b45` passed: 5,140 passed,
16 optional-data skips, no failures, all 14 modules, formatting and scalafix; neither long storage
suite was excluded. Final candidate evidence is recorded in the
[compatibility log](../compatibility.md#evidence-log).

The ledger scenario at `83c76b45` passed on 2026-10-20: 1,654 applications through Conway
epoch 17, all 66 Shelley–Conway comparisons matched, no validation/fatal errors. Direct Byron
ledger queries remain unavailable in the reference CLI; its UTxO is checked through Shelley.
The final representative comparison is below the +10% bar for every block and the epoch tick;
all 21 complete-result fingerprints match across six JVMs. Additional tests in `b4aabcd4` make
BBODY success/refusal, Tick failure, and nested scoped-Globals/rollback coverage explicit.
The scenario and benchmark gaps identified by review are discharged; final-head check is reported
on PR #248. That PR targets `dev`; #202 remains open until the change lands on `main` with green CI.
