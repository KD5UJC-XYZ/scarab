<!-- SPDX-License-Identifier: Apache-2.0 -->
# Design rules

**Status: Current.** The rules Scarab's code follows: the single home of each rule, which
[AGENTS.md](../AGENTS.md) and [CONTRIBUTING.md](../CONTRIBUTING.md) link and the guards cite. Why a
rule exists is in its ADR or in the owner's words. How work is tracked is in [process.md](process.md),
and the gates of a change in [CONTRIBUTING.md](../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change).

The owner's requirements, in their words: *"functional type safe code that leans on the compiler to
enforce correctness as much as possible"*; *"retain the use of Ask/Stateful/Reader and so on from
MTL. Lean on the compiler with syntax classes, implicit derivation, givens, using, etc. … elegant
scala code … I love the use of type parameters when I can use them"*; and *"correct by construction,
not correct by sequence of operations"*.

## How to read a rule

Each rule has an ID (`ERR-3`) and an anchor of the same name in lower case (`#err-3`) that does not
change when its heading does. An ID is never renumbered or reused: a retired rule keeps its ID and its
heading, its text struck through (`~~…~~`), with a link to what replaced it. A rule states:

- **the rule**, precisely enough that a reviewer can say yes or no;
- **scope**, one or more of:
  - *main*: `src/main` of every module except `tools`, including the commands the node ships in package
    `xyz.kd5ujc.scarab.node.tools`;
  - *tools*: the sbt module `tools`, the programs run by hand ([TOOL-1](#tool-1));
  - *tests*: every other Scala source, the suites and their helpers;
- **exceptions**, and how a kept site is marked. Unless a rule says otherwise, a kept site that a wart
  refuses carries `@SuppressWarnings(Array("org.wartremover.warts.<Wart>"))` on its smallest
  definition, with a comment giving the reason, and other kept sites carry the comment alone;
- **enforcement**: the compiler option, the wart, the guard script, or *review only*. *Review only; a
  guard is planned* means no tool checks the rule yet: a reviewer does;
- **why**: the ADR, the owner's decision or the owner's words.

A new general rule, and a change to one, comes here, with a sweep of the tree for other occurrences
([owner review feedback](../CONTRIBUTING.md#owner-review-feedback)).

## What enforces the rules today

| Mechanism | Where it is set | Scope | What it refuses |
|---|---|---|---|
| Scala compiler | `build.sbt`, `ThisBuild / scalacOptions` | main, tools, tests | every warning, through `-Werror`; the warnings on are `-deprecation`, `-feature`, `-unchecked` and `-Wunused:imports`, `privates`, `locals`, `explicits`, `patvars`, plus Scala 3's own (a match that is not exhaustive among them). Generated sources are silent |
| `-Wsafe-init` | `build.sbt`, `scarab(...)` | main | a field read before it is initialised |
| WartRemover | `build.sbt`, `disallowedWarts` | main; not `tools`, not generated code | the 15 warts listed under [FUN-1](#fun-1) |
| `@tailrec` | at the definition | all | a call to itself that is not in tail position |
| scalafmt | `.scalafmt.conf`, `scalafmtCheckAll` in `check` | all | a file not formatted |
| scalafix | `.scalafix.conf`, `scalafixAll --check` in `check` | all | imports out of their groups (`OrganizeImports` only; no semantic rule) |
| Python guards | `scripts/check_*.py`, run by `scripts/preflight` and the CI `scripts` job | as each rule says | as each rule says; each script's docstring has its detail |
| Crypto guards | `.github/workflows/security.yml`: semgrep with `.semgrep/scarab-crypto.yaml`, and `scripts/check_crypto_guard.py` | crypto code | [CRY-1](#cry-1) |
| Secret scan | gitleaks with `.gitleaks.toml`, in CI and `scripts/preflight` | full history | committed secrets ([FMT-3](#fmt-3)) |
| SPDX step | `.github/workflows/ci.yml`, "SPDX header on every script and workflow" | `.py`, `.yml` and `sbtw` under `scripts/` and `.github/workflows/` | a file without the header ([CMT-4](#cmt-4)) |
| PR policy | `scripts/pr_policy.py`, on every pull request event | pull request body | a missing level, a level below its floor, or a missing or malformed "Deviations from the reference" or "Gaps" section ([REF-7](#ref-7)) |

<a id="eff"></a>
## Effects and capabilities

<a id="eff-1"></a>
### EFF-1. Tagless final, with capabilities through cats-mtl

Code that performs effects is written against algebras over `F[_]`. It takes its environment, state,
typed errors and accumulation as cats-mtl capabilities (`Ask`/`Local`, `Stateful`, `Raise`/`Handle`,
`Tell`) in `using` clauses, not as concrete types. Interpreters are chosen in `Main`.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md); the owner's words above.

<a id="eff-2"></a>
### EFF-2. `IO` only at the edge

`cats.effect.IO`, `IOApp` and `cats.effect.unsafe` appear only in `Main`, in the commands of package
`xyz.kd5ujc.scarab.node.tools`, in `tools` and in tests. Everything else is polymorphic in `F`.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only; a guard is planned.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md) and its amendment of 2026-10-25 (the log is
  not an exception).

<a id="eff-3"></a>
### EFF-3. Pure rules

Ledger, consensus, mempool and chain-selection rules are pure functions over values. Effects wrap them,
not the other way around.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md).

<a id="eff-4"></a>
### EFF-4. No hidden global state

No state lives outside the values and the effect interpreters that own it. A memoising cache is an
instance created in a `Resource` (`VerificationCache`) and handed to the pure rules through a read
interface (`Verdicts`).

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md): effects must be swappable for tests and
  simulation.

<a id="eff-5"></a>
### EFF-5. No `unsafeRun*`

No `unsafeRunSync`, `unsafeRunAndForget`, `unsafeToFuture` or other `unsafeRun*` call, wherever it can be
avoided. A suite returns its effect to the test framework, or runs it under `TestControl`.

- **Scope:** main, tools, tests.
- **Exceptions:** the one bridge for a third-party callback that must run an effect from outside `F`, and
  may block for it (a JVM signal handler, a library's log hook), is a `Dispatcher` owned by a `Resource`.
  The site carries a comment that gives the callback's contract.
- **Enforcement:** review only; a guard is planned.
- **Why:** the owner's ruling of 2026-11-14.

<a id="eff-6"></a>
### EFF-6. Time comes from `Clock[F]`

Code reads the time only through `Clock[F]` (`realTime`, `monotonic`, `timed`). It never calls
`System.currentTimeMillis`, `System.nanoTime`, `Instant.now` or any other reading of the system clock.

- **Scope:** main, tools, tests.
- **Exceptions:** the timed region of a measurement tool ([FUN-2](#fun-2) (e)), where `Clock[F]` would add
  allocation to what is timed. The site carries a comment that says so.
- **Enforcement:** review only; a guard is planned.
- **Why:** the owner's ruling of 2026-11-14; [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md): time is
  an effect, swappable for tests (`TestControl`).

<a id="err"></a>
## Errors

<a id="err-1"></a>
### ERR-1. Closed, domain-specific error types

Every failure is a case of a closed (sealed or enum), domain- or service-specific error type. Pure code
returns `Either[DomainError, A]`. Effectful code raises through `Raise[F, E]` and handles through a
lexically scoped `Handle.allow` (`PeerError`, `ChainError`, `ValidationError`, `StorageError`, `ForgeError`,
`ConfigError`).

- **Scope:** main, pure functions included.
- **Exceptions:** tests and tools ([TST-1](#tst-1)).
- **Enforcement:** review only.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md);
  [domain and service errors](design/domain-errors.md).

<a id="err-2"></a>
### ERR-2. Typed causes survive every layer

A layer that wraps an error keeps its typed cause. Text is rendered from an error only for logs, CLI
output or wire presentation, never to carry the error to another layer.

- **Scope:** main.
- **Exceptions:** tests and tools ([TST-1](#tst-1)).
- **Enforcement:** review only.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md).

<a id="err-3"></a>
### ERR-3. No stringly typed errors

No `Either[String, …]`, declared or inferred. No generic `Failure(message: String)` wrapper, or other
case whose content is free text, stands in for domain cases: such a wrapper is not a migration to domain
error cases.

- **Scope:** main.
- **Exceptions:** tests and tools ([TST-1](#tst-1)), except that the guard scans every tracked Scala
  file, so it refuses a declared `Either[String, …]` there too.
- **Enforcement:** `scripts/check_error_channels.py` refuses a declared `Either[String, …]`. An inferred
  `Left("…")` and a free-text case are review only. The free-form strings left in error types are tracked
  in [#386](https://github.com/KD5UJC-XYZ/scarab/issues/386).
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md).

<a id="err-4"></a>
### ERR-4. No exceptions for domain errors

A domain error is never thrown (`throw`, or a call that throws, such as `sys.error` or `require`), and
never raised as an exception (`raiseError` of a `Throwable`). An exception that is not ours (IO, a
library) is caught at the boundary only. There it becomes a typed case: `StorageError` or `PeerError`
where it is that layer's failure, and otherwise a case that says it is a defect. It is never "the peer is
unavailable".

- **Scope:** main.
- **Exceptions:** tests and tools ([TST-1](#tst-1)); a callback whose contract signals failure by
  exception ([FUN-2](#fun-2) (c)).
- **Enforcement:** `Wart.Throw`. `raiseError`, `sys.error` and `require` are review only; a guard is
  planned.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md);
  [ADR 0017](adr/0017-fibers-end-with-values.md).

<a id="err-5"></a>
### ERR-5. No partial accessors

No `.get`, `.toOption.get`, `.head`, `.last`, `Map` or indexed `apply`, or other partial accessor, on a
value that is not provably present. Use the total form instead: `fold`, `getOrElse`, a typed error or a
non-empty type ([TYP-5](#typ-5)).

- **Scope:** main.
- **Exceptions:** tests and tools ([TST-1](#tst-1)); a value whose presence a comment at the site proves,
  marked as a kept site.
- **Enforcement:** `Wart.OptionPartial`, `Wart.TryPartial`, `Wart.EitherProjectionPartial` and
  `Wart.IterableOps`. `Map` and indexed `apply` are review only.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md).

<a id="str"></a>
## Strings are values

<a id="str-1"></a>
### STR-1. A String is output or parser input

A `String` is produced for output (a log line, CLI output, wire text, a rendered error), or read once by a
parser at a boundary ([STR-2](#str-2)). Between the two, text is a typed value.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only; a guard is planned. Leftovers are tracked under
  [#386](https://github.com/KD5UJC-XYZ/scarab/issues/386).
- **Why:** the owner's ruling of 2026-11-14; [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md): no
  string-based control flow.

<a id="str-2"></a>
### STR-2. Parsers, and the tables they need

A parser is code whose input is text from outside the process (CLI arguments, the environment,
configuration, genesis or topology files, HTTP, text fields on the wire) and whose result is a typed value
or a typed error ([TYP-2](#typ-2)). A table a parser needs (for example the Plutus cost-model parameter
names) lives inside the parser. Its names become typed parameters when they are decoded, and nothing looks
them up by name later.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only, except one part: `scripts/check_text_policy.py` refuses, in `ledger` main, a
  read of a CBOR text field (`Value.Text`) outside its shared reader (`CborRead.text`) and the sites the
  guard allowlists with a reason.
- **Why:** the owner's ruling of 2026-11-14.

<a id="str-3"></a>
### STR-3. No decision depends on a String's contents

Outside a parser, no decision depends on the contents of a String:
- no `==` against a String;
- no `match` on a String literal;
- no `startsWith`, `contains`, `matches` or regular expression;
- no `Map[String, _]` or `Set[String]` in domain state;
- no identifier built by concatenation.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only; a guard is planned.
- **Why:** the owner's ruling of 2026-11-14; [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md).

<a id="fun"></a>
## Functional style and the narrow imperative exceptions

<a id="fun-1"></a>
### FUN-1. Functional code by default

Code is functional. It has no `var`, `while`, `return`, `null`, cast or type test, mutable collection,
`try`/`catch`/`finally`, `???`, `Thread.sleep` or global execution context. Effects use `Resource`,
`bracket` and `attempt`. Java's mutable and atomic types, `synchronized` and `@volatile` are mutable state
too, and fall under the same rule.

- **Scope:** main.
- **Exceptions:** only those of [FUN-2](#fun-2), marked as [FUN-3](#fun-3) says. Neither the warts nor the
  `try` guard reach `tools` or tests.
- **Enforcement:** WartRemover refuses, in the main sources of every module but `tools`, with generated
  code excluded: `Wart.Var`, `Wart.While`, `Wart.Throw`, `Wart.Return`, `Wart.Null`, `Wart.AsInstanceOf`,
  `Wart.IsInstanceOf`, `Wart.MutableDataStructures`, `Wart.OptionPartial`, `Wart.TryPartial`,
  `Wart.EitherProjectionPartial`, `Wart.IterableOps`, `Wart.TripleQuestionMark`, `Wart.ThreadSleep` and
  `Wart.GlobalExecutionContext` (`disallowedWarts` in `build.sbt`). `scripts/check_imperative.py` refuses a
  new `try`, `catch` or `finally` in the same sources, against `docs/data/imperative-baseline.json`, which
  only shrinks. Java's mutable types, `synchronized` and `@volatile` are review only.
- **Why:** the owner's words above; [#650](https://github.com/KD5UJC-XYZ/scarab/issues/650).

<a id="fun-2"></a>
### FUN-2. The narrow imperative exceptions

Imperative, side-effecting code is kept only for:
- (a) a performance-critical path, with profiling or benchmark evidence recorded in
  [performance.md](performance.md) and a written reason for that specific path;
- (b) a third-party interface that requires it;
- (c) a callback whose contract signals failure by exception or keeps its state by mutation (Scalus's
  `BudgetSpender` and builtin functions), in the smallest definition that implements it;
- (d) secret key material that must be erased in place, so that no copy outlives its use (a KES signing key
  evolves by zeroing the periods it leaves, `Sum6KesSigning.SigningKey`);
- (e) the timed region of a measurement tool, where a wrapper would add allocation to what is timed
  (`TimingProbeMain`).

- **Scope:** main.
- **Exceptions:** this list is the whole of them.
- **Enforcement:** review only: a reviewer checks that a kept site names one of (a) to (e).
- **Why:** the owner's words above; [performance.md](performance.md) holds the measurements.

<a id="fun-3"></a>
### FUN-3. A kept site is marked and contained

A kept imperative site carries `@SuppressWarnings(Array("org.wartremover.warts.<Wart>"))` on its smallest
definition, with a comment that names its measurement, the library contract, or why it is erased in place
or timed. Its mutation stays local, behind a pure interface or an effect interpreter. An exception changes
the implementation style only: typed errors ([ERR](#err)), capability scope ([FIB](#fib)) and hashed bytes
([BYT-1](#byt-1)) still apply.

- **Scope:** main.
- **Exceptions:** none. `scripts/check_imperative.py` refuses a new `try`, `catch` or `finally` whatever its
  reason.
- **Enforcement:** the warts refuse an unmarked site. The comment and the containment are review only.
- **Why:** [#650](https://github.com/KD5UJC-XYZ/scarab/issues/650).

<a id="fib"></a>
## Fibers

<a id="fib-1"></a>
### FIB-1. A fiber never raises through a capability it did not create

A `Raise` from `Handle.allow` is valid in the fibers of its scope only. Raised in any other fiber, it is
an exception that nobody catches.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only. In tests, [TST-3](#tst-3) turns such an exception into a failure.
- **Why:** [ADR 0017](adr/0017-fibers-end-with-values.md).

<a id="fib-2"></a>
### FIB-2. A fiber that outlives the call ends with a value

A fiber that outlives the call (`background`, `supervise`, a kept `start`) runs its body in a scope of its
own, and returns `Either` at its boundary (`Fibers.scoped`, `Fault.fiber`). Its owner raises the value again
in its own fiber (`Fibers.land`, `Fault.land`). A channel or client takes the caller's `Raise` as a `using`
clause instead of holding one. [ADR 0017](adr/0017-fibers-end-with-values.md) has the worked example.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0017](adr/0017-fibers-end-with-values.md).

<a id="fib-3"></a>
### FIB-3. A fiber joined at once may use its owner's capabilities

A fiber that its owner joins at once (`race`, `parSequence`, `Compute.within`) may use the owner's
capabilities.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0017](adr/0017-fibers-end-with-values.md).

An exception that is not ours is wrapped where it enters, as [ERR-4](#err-4) says.

<a id="tst"></a>
## Tests

<a id="tst-1"></a>
### TST-1. What binds tests

Tests are exempt from the error-type, no-throw and no-partial-accessor rules ([ERR-1](#err-1) to
[ERR-5](#err-5)), because the suite contains the failure. Tests still follow the other rules that name
them in their scope. In particular:
- no sleeps ([TST-2](#tst-2));
- `FailsOnUncaught` on suites that start fibers ([TST-3](#tst-3));
- no `unsafeRun*` ([EFF-5](#eff-5)) and no system clock ([EFF-6](#eff-6));
- assertions on typed errors, not on message text ([TST-4](#tst-4)).

`tools` follows the rules of tests, except in its timed regions ([TOOL-2](#tool-2)).

- **Scope:** tests, tools.
- **Exceptions:** as stated.
- **Enforcement:** the warts and `-Wsafe-init` do not apply to tests or `tools` (`build.sbt`).
- **Why:** the owner's ruling of 2026-11-14.

<a id="tst-2"></a>
### TST-2. Tests do not sleep

No sleep, delay, timer or yield orders fibers, waits for a state or paces a poll loop. A test waits on a
`Deferred`, a latch, a change signal or a report of the code under test. It may instead run in `Settled`
(network test tree; its Scaladoc says what `settle` waits for). Time is driven with `TestControl` (`tick`,
`advanceAndTick`) or `Settled.timed`. A poll that the guard does not see (`ref.get.iterateUntil(p)`) is
forbidden all the same.

- **Scope:** tests, tools.
- **Exceptions:** none. A new sleep is refused even for a bounded negative observation.
- **Enforcement:** `scripts/check_test_sleeps.py` refuses a new sleep, delay, timer, yield or sleep-poll
  helper in a Scala source outside `src/main` (its docstring lists the calls), against
  `docs/data/test-sleep-baseline.json`, which only shrinks: the change that removes a site commits the
  smaller baseline. It cannot tell `TestControl`'s clock from the wall clock, so it refuses a new
  virtual-time sleep too. It does not read `tools/src/main`, where the rule is review only.
- **Why:** a sleep bets on scheduling and hides a race behind the same observable
  ([#870](https://github.com/KD5UJC-XYZ/scarab/issues/870)).

<a id="tst-3"></a>
### TST-3. Suites that start fibers extend `FailsOnUncaught`

A suite that starts a fiber extends `FailsOnUncaught`, so an exception that no one catches fails the
suite.

- **Scope:** tests.
- **Exceptions:** none.
- **Enforcement:** review only; a guard is planned.
- **Why:** [ADR 0017](adr/0017-fibers-end-with-values.md).

<a id="tst-4"></a>
### TST-4. Assertions on typed errors, not on message text

A test that expects a failure asserts its typed error case, or that case's fields. It does not assert on
rendered text (`getMessage`, `toString`, a substring of either).

- **Scope:** tests, tools.
- **Exceptions:** a test of the rendering itself (a log line, CLI output, wire text).
- **Enforcement:** review only; a guard is planned.
- **Why:** the owner's ruling of 2026-11-14; [ERR-2](#err-2).

<a id="tst-5"></a>
### TST-5. Concurrency tests evaluate their waits first

A concurrency test evaluates a blocking latch wait or join before it passes the result to an MUnit
assertion. Clue capture holds the suite monitor while it evaluates an assertion, so a worker's assertion
can deadlock with a parent's asserted wait. Latches are released, and owned workers finished, in
guaranteed cleanup. Positive synchronization uses an observable condition or a controlled schedule.

- **Scope:** tests.
- **Exceptions:** none.
- **Enforcement:** review only. `scripts/flaky_tests.py` files an issue for a test that failed and then
  passed on the same commit.
- **Why:** MUnit's clue capture, as stated; [TST-2](#tst-2).

<a id="tst-6"></a>
### TST-6. Laws and properties

A new algebra or stateful structure gets cats-laws, monocle-law or ScalaCheck properties (round trips,
model equivalence, rollback), not only examples.

- **Scope:** tests.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md).

<a id="cnf"></a>
## Conformance

<a id="cnf-1"></a>
### CNF-1. Upstream cases run whole

Original upstream cases run without Scarab-specific expected-failure lists, masked fields or numeric or
rounding tolerances. Historical non-conformance, and ledger or transaction reachability, do not justify
omitting a case: they are reasons to fix the implementation, or to match the upstream harness profile.

- **Scope:** tests.
- **Exceptions:** a specific JVM limitation, with a minimal reproducer, evidence of the platform limitation,
  the exact affected cases and an issue with a removal condition.
- **Enforcement:** `scripts/check_test_coverage.py` validates fixture identities, provenance and each
  declared comparison scope. That no case is skipped is review only.
- **Why:** [ADR 0001](adr/0001-haskell-node-as-reference.md).

<a id="cnf-2"></a>
### CNF-2. Incomplete is not a pass

A missing implementation or a missing fixture makes its gate explicitly incomplete; it never counts as a
conformance pass. Environmental availability skips and intentionally partial projections are reported
with their scope, separately from conformance results. Neither establishes agreement for the cases or
fields not tested.

- **Scope:** tests, and every report of a conformance result.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0001](adr/0001-haskell-node-as-reference.md).

<a id="tool"></a>
## Hand-run programs

<a id="tool-1"></a>
### TOOL-1. A program run by hand lives in `tools`

A program run by hand (a benchmark, a probe, the writer of a fixture or golden) lives in the `tools`
module (`tools/runMain <class>`), in the package of the code it exercises, never in a test tree. `tools`
may use every module's test kits. Data the suites read stays in the test tree, and `tools` holds only the
entry point. A command the node ships (package `xyz.kd5ujc.scarab.node.tools`) is not a program run by
hand.

- **Scope:** main, tools, tests.
- **Exceptions:** none.
- **Enforcement:** review only; a guard is planned.
- **Why:** programs run by hand are never tests and never shipped (`build.sbt`, `tools`).

<a id="tool-2"></a>
### TOOL-2. `tools` follows the rules of tests

`tools` follows the rules of tests ([TST-1](#tst-1)), except in a timed region ([FUN-2](#fun-2) (e)), which
may read the system clock and mutate.

- **Scope:** tools.
- **Exceptions:** as stated.
- **Enforcement:** `build.sbt` drops `-Wsafe-init` and the wart errors for `tools`. The rest is review only.
- **Why:** the owner's ruling of 2026-11-14; `build.sbt`.

<a id="typ"></a>
## Types: correct by construction

The owner's creed: *"correct by construction, not correct by sequence of operations"*. Types carry as much
of the correctness as the compiler can check.

<a id="typ-1"></a>
### TYP-1. Illegal states are unrepresentable

A value that can be constructed is valid. An invariant is carried by a type (an ADT, an opaque type, a
type parameter), not by the order in which code calls its checks.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** the owner's creed; [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md).

<a id="typ-2"></a>
### TYP-2. Parse, don't validate

A check of input returns the proof type (an opaque, refined or ADT value), not a `Boolean` or the input
unchanged.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** the owner's creed.

<a id="typ-3"></a>
### TYP-3. Opaque types with smart constructors

A domain quantity or identifier is an opaque type or a wrapper with a smart constructor, not a bare
primitive. A constructor that can fail returns a typed error.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** the owner's creed; [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md).

<a id="typ-4"></a>
### TYP-4. ADTs, not Boolean or String flags

A choice between modes or outcomes is an enum or a sealed ADT, not a `Boolean` or a String.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** the owner's creed; [STR-3](#str-3).

<a id="typ-5"></a>
### TYP-5. Non-empty collections where emptiness is invalid

A collection that must not be empty has a non-empty type (`NonEmptyList`, `NonEmptyVector`, `NonEmptyMap`
and the like).

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** the owner's creed.

<a id="typ-6"></a>
### TYP-6. Exhaustive matches

A match on a sealed or enum scrutinee names every case, with no wildcard `case _`, so that a new case
fails to compile until each match handles it.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** the compiler's exhaustivity warning, an error under `-Werror`, refuses a missing case.
  The wildcard is review only; a guard is planned.
- **Why:** the owner's creed.

<a id="typ-7"></a>
### TYP-7. Type-level sizes where lengths are fixed

A byte string or vector of fixed length carries its length in its type (Scala 3 literal types and
`scala.compiletime.ops`), so that a value of the wrong length does not compile.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** the owner's creed.

<a id="typ-8"></a>
### TYP-8. Lean on the compiler

Use opaque types, type classes with extension syntax, instances in companions, `derives` where it works,
givens and `using` clauses, and Monocle optics for nested state. Use type parameters for roles and eras
(`KeyHash[StakePool]`, `LedgerState[Conway]`).

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** the owner's words above; [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md),
  [ADR 0007](adr/0007-era-indexed-state.md).

<a id="typ-9"></a>
### TYP-9. Free only where a program must be inspected

A program is written as a Free (or other initial) encoding only where it must be inspected, or interpreted
in more than one way. Effect boundaries stay tagless final ([EFF-1](#eff-1)).

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** the owner's ruling of 2026-11-14.

<a id="stk"></a>
## Stack safety

<a id="stk-1"></a>
### STK-1. Recursion over outside data is stack safe by construction

A recursion whose depth or width comes from outside data (blocks, peers, files, queries) takes one of
these forms: a library fold or traversal, `@tailrec`, `tailRecM` over a typed state, or an explicit stack
machine. It is never a recursion inside an `Either` or `Option` for-comprehension or `flatMap`, a
`foldRight` on a strict collection, or a mutual recursion without one of those forms.

- **Scope:** main.
- **Exceptions:** a depth that is a constant of the format, or of our own schema, with its bound stated
  ([ADR 0011](adr/0011-stack-safety.md)).
- **Enforcement:** `@tailrec` makes the compiler refuse a call that is not in tail position. The rest is
  review only.
- **Why:** [ADR 0011](adr/0011-stack-safety.md).

<a id="stk-2"></a>
### STK-2. Recursion schemes where the walk is a tree

Where the depth comes from outside data, a tree walk is a recursion scheme (a stack-safe fold over the
structure's base functor) in preference to recursion written by hand. On a hot path it is adopted only
after a benchmark shows it is no slower. The CEK machine of the Plutus evaluator stays an explicit
machine.

- **Scope:** main.
- **Exceptions:** the CEK machine, as stated.
- **Enforcement:** review only.
- **Why:** the owner's ruling of 2026-11-14; [ADR 0011](adr/0011-stack-safety.md).

<a id="stk-3"></a>
### STK-3. Tested on a small stack

Every path covered by [STK-1](#stk-1) has a test on a thread with a small stack
(`PreprodDeepTx.onSmallStack`), and, where a real transaction caused it, the real transaction.

- **Scope:** tests.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0011](adr/0011-stack-safety.md).

<a id="ari"></a>
## Arithmetic

<a id="ari-1"></a>
### ARI-1. Exact arithmetic

Ledger and consensus calculations use exact arithmetic (`Rational`, integers), not floating point, except
where the reference itself computes in floating point. There Scarab mirrors it, citing the pinned
reference line at the site.

- **Scope:** main.
- **Exceptions:** the reference's own floating point, as stated.
- **Enforcement:** review only; a guard is planned.
- **Why:** [ADR 0001](adr/0001-haskell-node-as-reference.md); results must equal the reference's
  ([REF-1](#ref-1), I1).

<a id="ari-2"></a>
### ARI-2. Overflow, underflow and narrowing

Use checked or exact arithmetic. For overflow, underflow and narrowing, follow
[ADR 0001's arithmetic procedure](adr/0001-haskell-node-as-reference.md#amendment-2026-11-09-checked-arithmetic-and-compatibility)
before choosing the boundary behaviour. A reference bug is not blanket permission to change it: the
difference is classified under [REF-4](#ref-4), as an Improvement under defect (c) only with I1 to I4
holding.

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0001](adr/0001-haskell-node-as-reference.md), amendment of 2026-11-09.

<a id="byt"></a>
## Hashed bytes

<a id="byt-1"></a>
### BYT-1. Hash and sign exactly the bytes the reference hashes and signs

By default these are the bytes as received, kept beside the decoded value. Where the reference hashes or
signs a re-encoding, Scarab re-encodes as it does, citing the pinned upstream line at that site. An
exception to the default is established only by reading the reference at its pinned version.
[Hashed and signed bytes](design/hashed-bytes.md) lists the known re-encodings.

- **Scope:** main.
- **Exceptions:** the re-encodings the reference makes, as stated.
- **Enforcement:** review only. A type that separates received bytes from re-encodings is tracked in
  [#971](https://github.com/KD5UJC-XYZ/scarab/issues/971).
- **Why:** [ADR 0013](adr/0013-cbor-documents-are-an-index-over-the-input.md);
  [ADR 0001](adr/0001-haskell-node-as-reference.md).

<a id="ref"></a>
## The reference

The Haskell node is the reference ([ADR 0001](adr/0001-haskell-node-as-reference.md)). REF-1 to REF-4 are
the owner's approved wording, verbatim
([#474, 2026-11-14](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6880015076)). It restates
the standing rule on reference bugs
([#474](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849)) with exact boundaries.
ADR 0001 is not amended by it.

<a id="ref-1"></a>
**Interoperability** means all four hold:
- **I1 verdicts:** every block, header and transaction gets the reference's validity verdict, and the
  resulting ledger state.
- **I2 what we send:** everything Scarab sends to a reference node decodes there with the same meaning.
  Where the reference's output is fixed, the bytes are identical.
- **I3 what we accept:** we accept everything an honest reference peer, client or operator sends, and
  refuse only what the specification forbids and no honest reference sends.
- **I4 what inputs mean:** configuration, topology, genesis, CLI arguments and queries mean what they
  mean to the reference, and queries get its answers.

<a id="ref-2"></a>
**A reference defect** is one of these, at the pinned version, shown by reading the pinned source or by
running the reference (and saying which):
- (a) it crashes, hangs or calls `error` on input it accepts;
- (b) it contradicts its own specification, cited;
- (c) arithmetic silently wraps, overflows or narrows (ADR 0001's procedure);
- (d) the outcome depends on a race between its own threads;
- (e) upstream has since fixed it, with the fix commit cited.

<a id="ref-3"></a>
**Which reference governs:** the pinned release. Where Scarab carries two reference builds, the selected
build governs whatever differs between them.

<a id="ref-4"></a>
**Every difference is exactly one of four classes:**
- **Mirror:** the same as the reference.
- **Improvement:** a defect from the list, with I1–I4 holding. Standing approval (#474).
- **Deviation:** any other difference: a policy choice, a performance trade, an extension. Needs the
  owner's approval first.
- **Gap:** an unintended difference. Gets an issue and a fix.

**The default:** anything not shown, with evidence, to be a Mirror or an Improvement is a Deviation.
Stop and ask.

For REF-1 to REF-4:
- **Scope:** main: the behaviour Scarab shows to peers, clients and operators.
- **Exceptions:** none.
- **Enforcement:** `scripts/pr_policy.py` requires the "Deviations from the reference" section
  ([REF-7](#ref-7)). Classification is review only.
- **Why:** [ADR 0001](adr/0001-haskell-node-as-reference.md);
  [the owner's approval](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6880015076).

<a id="ref-5"></a>
### REF-5. No Deviation on an agent's own accord

**Agents may not deviate from the reference on their own accord.** A Deviation, even for a measured
performance benefit or another strong reason, needs the owner's explicit approval first, requested with
clearly defined trade-offs and reasoning. Only after that approval is it implemented, with a written reason
that records the approval. Existing code, earlier PRs, review notes and design documents are not approval.
If exact fidelity seems impossible, stop and ask the owner. Decisions recorded by the owner's `ottobot-ai`
account are the owner's.

- **Scope:** main.
- **Exceptions:** an Improvement, which has standing approval once its defect is shown with the evidence
  [REF-2](#ref-2) asks for and I1 to I4 hold.
- **Enforcement:** review only; `scripts/pr_policy.py` requires each Deviation row to link its approval.
- **Why:** [ADR 0001](adr/0001-haskell-node-as-reference.md), amendment of 2026-10-25;
  [decisions register](decisions.md#reference-fidelity).

<a id="ref-6"></a>
### REF-6. Mirror the reference, and cite it at its pin

Mirror the reference's behaviour, types and formats. Internal types follow the Haskell types (names,
fields, nesting, era indexing). Each mirrored item cites the upstream file and line at the pinned version,
a revision registered in `docs/data/reference-pins.json`. An internal algorithm or representation may
differ only where every observable result is identical
([decisions register](decisions.md#reference-fidelity), 2026-10-27).

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** in documents, `scripts/check_docs.py` refuses a cited upstream version or revision that
  no registered pin covers (`UNREGISTERED_PIN`). In code, citations are review only; a guard is planned.
- **Why:** [ADR 0001](adr/0001-haskell-node-as-reference.md).

<a id="ref-7"></a>
### REF-7. How a pull request records each class

A pull request of level 1 or higher records every difference it adds, changes or removes in its
"Deviations from the reference" table: four cells per row (behaviour; the reference file and line at its
pin; what Scarab does; approval), and the class goes in the approval cell, with its link.

| Class | What the pull request records |
|---|---|
| Mirror | no row |
| Improvement | a row whose approval cell names the defect and links the standing approval, for example `Improvement (e): [#474](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6880015076)`. The reference cell cites the evidence: the pinned line, or the run that showed it, and for (e) the fix commit |
| Deviation | a row whose approval cell links the owner's approval, for example `Deviation: [decision](link)`, and a row in the [decisions register](decisions.md) |
| Gap | no row: a bullet under "Gaps" that starts with its issue (`- #N: …`) |

- **Scope:** pull requests of level 1 and above.
- **Exceptions:** a bot's pull request and a promotion of `dev`, which `scripts/pr_policy.py` does not check.
- **Enforcement:** `scripts/pr_policy.py` refuses a row that does not have four nonempty cells, or whose
  approval cell has no link. The class itself is review only.
- **Why:** [CONTRIBUTING.md](../CONTRIBUTING.md#commits-and-pull-requests); [REF-4](#ref-4).

<a id="ref-8"></a>
### REF-8. Settled Deviations

Path names and their encoding are settled, as a Deviation, by the owner's rule in the
[decisions register](decisions.md#networking-and-local-clients)
([#953](https://github.com/KD5UJC-XYZ/scarab/issues/953)). Do not chase the reference's behaviour at other
bytes or locales; only the owner reopens the rule. It is not a precedent: any other Deviation still needs
the owner's approval first ([REF-5](#ref-5)). The other approved Deviations are rows of the
[decisions register](decisions.md).

- **Scope:** main.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [#953](https://github.com/KD5UJC-XYZ/scarab/issues/953#issuecomment-6874230519).

<a id="fmt"></a>
## Persisted formats and fixtures

<a id="fmt-1"></a>
### FMT-1. A persisted format changes only with a version bump and a migration

A persisted format does not change without a version bump, a migration and a test that reads the previous
version. The change is level 3 and follows the [storage migration procedure](storage-migrations.md).

- **Scope:** main, tests.
- **Exceptions:** none.
- **Enforcement:** the snapshot goldens in `node/src/test/.../storage/CodecSuite.scala`
  ([what they pin](storage-migrations.md#register-the-change-before-changing-the-writer)), and
  `scripts/pr_policy.py`, which raises the level floor to 3 when `PersistedFormat.scala` changes.
- **Why:** [ADR 0006](adr/0006-versioned-formats-migrate-on-open.md).

<a id="fmt-2"></a>
### FMT-2. Native snapshot fixtures use the current format

Maintained native snapshot payload fixtures, and ordinary snapshot test builders, use the current native
format (`FileKind.SnapshotFile.current`; its fixtures are in `fixtures/snapshot-v<N>`). The files of
earlier versions are the previous-version inputs. Assertions read typed native state directly: a
flat-state completion adapter is not the fixture's expected state. Conversion provenance is pinned, and
original private captures are preserved outside the checkout. Generic toy-format migration framework tests
keep testing version transitions independently of the native fixtures. Converting a fixture does not, on
its own, retire a production reader or authorise an operational writer upgrade.

- **Scope:** tests.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** [ADR 0006](adr/0006-versioned-formats-migrate-on-open.md); [FMT-1](#fmt-1).

<a id="fmt-3"></a>
### FMT-3. No secrets; fixtures with provenance and licence

Never commit `.skey` or `.key` files, KES, VRF or cold keys, or node sockets. Fixtures are public chain
data or throwaway keys, with their provenance and licence recorded in the fixture directory's README and
in `NOTICE`.

- **Scope:** the whole repository.
- **Exceptions:** none.
- **Enforcement:** the gitleaks secret scan of the full history (CI and `scripts/preflight`;
  `.gitleaks.toml`); `scripts/check_test_coverage.py` validates fixture identities and provenance.
- **Why:** [SECURITY.md](../SECURITY.md#keys-and-secrets).

<a id="cry"></a>
## Cryptography

<a id="cry-1"></a>
### CRY-1. Variable-time crypto stays contained

The variable-time ECVRF math (`Draft03Math`, `Ed25519Point`) takes public inputs only, and is referenced
only from package `xyz.kd5ujc.scarab.crypto.vrf`. Crypto code follows the rules of
`.semgrep/scarab-crypto.yaml` (its messages give each rule).

- **Scope:** main, tests.
- **Exceptions:** none.
- **Enforcement:** `scripts/check_crypto_guard.py` (containment) and semgrep, failing on a `WARNING`
  finding, both in `.github/workflows/security.yml`; that secret scalars never reach the variable-time math
  is review only (the guard's docstring).
- **Why:** [#219](https://github.com/KD5UJC-XYZ/scarab/issues/219).

<a id="cmt"></a>
## Comments, naming and source files

<a id="cmt-1"></a>
### CMT-1. Comments and names like the surrounding code

Comment density and naming follow the surrounding code.

- **Scope:** main, tools, tests.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** no ADR; a repository convention.

<a id="cmt-2"></a>
### CMT-2. No comments in parameter lists

Parameter lists carry no comments, including constructor fields and call arguments. A parameter's
contract goes in the declaration's Scaladoc (`@param` where useful), and a call is explained before the
expression.

- **Scope:** main, tools, tests.
- **Exceptions:** none.
- **Enforcement:** review only; a guard is planned.
- **Why:** no ADR; a repository convention: Scaladoc is where a reader looks for a parameter's contract.

<a id="cmt-3"></a>
### CMT-3. Comments that explain are kept, and cite what is maintained

Comments that explain intent, invariants, reference correspondence or a non-obvious trade-off are kept:
check their reason before rewriting or removing one. A comment refers to maintained symbols, ADRs and
pinned upstream sources, not to private notes, implementation-stage labels or unpinned line numbers.

- **Scope:** main, tools, tests.
- **Exceptions:** none.
- **Enforcement:** review only.
- **Why:** no ADR; a reader cannot check a private note or an unpinned line ([REF-6](#ref-6)).

<a id="cmt-4"></a>
### CMT-4. SPDX header on new files

Every new source file starts with the SPDX header (`// SPDX-License-Identifier: Apache-2.0` in Scala, the
comment form of its language elsewhere).

- **Scope:** main, tools, tests, scripts and workflows.
- **Exceptions:** none.
- **Enforcement:** the CI step "SPDX header on every script and workflow" checks `.py`, `.yml` and `sbtw`
  under `scripts/` and `.github/workflows/`. Other files are review only; a guard is planned.
- **Why:** the licence (Apache-2.0).

<a id="cmt-5"></a>
### CMT-5. Formatted by scalafmt, imports organised by scalafix

Scala is formatted with scalafmt (`scalafmtAll`), and imports are organised by scalafix (`scalafixAll`;
run `scalafmtAll` after it).

- **Scope:** main, tools, tests.
- **Exceptions:** none.
- **Enforcement:** `check` runs `scalafmtCheckAll` and ends with `scalafixAll --check`.
- **Why:** one format, so diffs show only changes.

<a id="doc"></a>
## Documents

<a id="doc-1"></a>
### DOC-1. An accepted ADR's decision is amended, not rewritten

An accepted ADR's decision text is not rewritten. A stale example or symbol name may be corrected in
place. Any other correction, and a supersession, goes in a dated "Amendment (YYYY-MM-DD)" section that
links what replaced it. The superseded words in the decision are struck through (`~~…~~`) with a link to
that amendment, so that a reader sees, where they read it, that the text is stale.

- **Scope:** `docs/adr/`.
- **Exceptions:** none.
- **Enforcement:** review only. `scripts/check_docs.py` refuses an ADR that the index does not link
  (`ADR_UNINDEXED`).
- **Why:** [the ADR index](adr/README.md): a decision is not edited away.

<a id="doc-2"></a>
### DOC-2. One home per policy or fact

Each policy or fact has one authoritative home: code rules in this document, gates in
[CONTRIBUTING.md](../CONTRIBUTING.md), decisions in ADRs and the [decisions register](decisions.md), and
process in [process.md](process.md). Other documents link to it rather than restating it.

- **Scope:** every document.
- **Exceptions:** none.
- **Enforcement:** review only. `scripts/check_docs.py` refuses a broken link or anchor.
- **Why:** [one place per question](process.md#one-place-per-question).

<a id="doc-3"></a>
### DOC-3. No volatile facts in static documents

Issue state, test counts and "current" versions come from GitHub, the registries
(`docs/data/reference-pins.json`, `PersistedFormat.scala`) or dated evidence rows, never from a static
document's text.

- **Scope:** every document.
- **Exceptions:** a dated evidence row, which records what was true when it was written.
- **Enforcement:** `scripts/check_docs.py` refuses a watched version or snapshot magic called current that
  is not the current one (`STALE_CURRENT`). The rest is review only; a guard is planned.
- **Why:** a static document cannot stay current.
