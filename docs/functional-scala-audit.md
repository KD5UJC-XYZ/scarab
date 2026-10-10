# Functional Scala audit: admission, ledger and runtime boundaries

Audit date: 2026-10-10. Baseline: `c1e3dc99da88baf676c153045da78119b03f5f64`.
Owned branch: `audit/functional-scala-20261010`; worktree: `/home/<user>/cardano-worktrees/functional-scala-audit-20261010`.

This is a source and retained-evidence audit, not a production refactor or a fresh test result. Only this Markdown document is changed. No builds, dependency installations, live/native jobs, containers or public pushes were performed. No applicable AGENTS.md or repository SKILL.md was found in the inspected repository and ancestor locations. Main's service work was read without modification and is explicitly marked WIP below.

## Implementation checklist (2026-10-10)

The original audit below records its historical baseline and WIP findings. This checklist records subsequent implementation; the original statement that only Markdown changed applies to that audit, not these later commits.

| Item | Status and precise boundary |
| --- | --- |
| Typed stop policy and evaluation events | Published in `c2a9b00`: closed termination policy and evaluation phase/outcome values with explicit wire renderers. This does not establish sustained or multi-epoch service behavior. |
| Admission callback contract | Published in `8318eb4`: package-trusted admission capability and truthful evidence-I/O contract. The owner fence remains; asynchronous persistence and a hard filesystem deadline are not implemented. |
| Domain IDs and quantities | Integrated in `8b83576`: checked opaque owner/coherent/ledger/environment IDs and uint64 generation/validation-slot types. `StatePin.checkedTyped` is additive; only `SubmissionOwner` pin assembly adopts it. Legacy constructors, raw fields, validation order/text, wire bytes and authority checks remain. Migration of remaining raw callers, transaction/envelope identities and other time units is unfinished. |
| Typed boundary errors | Integrated in `8b83576` and `e7ba7e7`: typed domain, pin and admission-view causes survive actual owner assembly until one compatibility exception boundary. Wider HTTP/network/CLI migration remains unfinished. |
| Laws | Integrated in `8b83576`: Discipline Eq laws for four identity roles and Order laws for two quantities, plus raw-denotation agreement, boundary rejection, wrong-role compile checks and legacy field/hash equivalence. This is narrow pin coverage, not repository-wide algebraic verification. |
| Dependency alignment | Integrated in `8b83576`: direct Cats core/kernel use is aligned at `2.13.0`, already requested by pinned Scalus `1.3.0`. Cats Effect/testkit `3.6.3`, Scala `3.3.8`, Scalus, cryptographic dependencies and native exclusions remain pinned. Core adds only kernel at runtime; kernel-laws `2.13.0`, discipline-munit `2.0.0` and munit-scalacheck `1.0.0` are Test dependencies. Existing MUnit remains `1.0.2`. |
| MTL/capabilities | Integrated in `8fd3500`: actual service admission, rebuild and guarded reads use explicit `Read[F]`/`Fence[F]`, `given`/`using`, typed results and pin syntax. MTL was assessed; no new direct dependency or use was needed. Cats Effect retains transitive MTL `1.3.1`. Broader orchestration remains separate work. |
| Typed runtime phases | Integrated in `915ead2`: `Bootstrap`, `Followed` and `VerifiedInclusion` connect ordered runtime helpers. This runtime remains `IO`; it is not presented as a generic `F[_]` migration. |
| Resource composition | Integrated in `4cc59cb`: the actual service publishes its result only after the selected JVM/native generation resource closes. Release failure yields failure status and no success result. |
| Checked rebuild pairing | Integrated in `0710699`: actual queued work uses a private checked `RebuildContext`, binding the full admission pin and profile. This does not replace the owner fence or pool work token. |
| HTTP failure policy | Integrated in `4057885`: 15 closed failure outcomes replace arbitrary status/code/category triples in actual HTTP paths. Existing wire bytes, redaction and effect handling remain; this is not a full HTTP/domain migration. |
| Ordered rebuild/shared rendering | Wider refactoring remains unfinished. Existing first-wins reservations, source identity checks, gate/Ref placement and cancellation masking are preserved. |

Dependency choices use the pinned upstream definitions: [Cats 2.13.0](https://github.com/typelevel/cats/blob/v2.13.0/build.sbt), [Cats Effect 3.6.3](https://github.com/typelevel/cats-effect/blob/v3.6.3/build.sbt), and [Discipline MUnit 2.0.0](https://github.com/typelevel/discipline-munit/blob/v2.0.0/build.sbt). Discipline's published integration uses munit-scalacheck `1.0.0`; there is no `1.0.2` artifact. These are compatibility/alignment choices, not a claim that every dependency is latest.

Validation: the isolated 2-CPU/2-GiB offline build passed formatting, 63 core boundary/law checks and 32 existing owner/service/evidence tests. Core, network-runtime and app eviction reports were inspected. Selected Cats core/kernel are now `2.13.0` throughout the effect modules (previously `2.11.0` in network-runtime, ledger-runtime and fetcher; app was already `2.13.0`). Core newly selects kernel `2.13.0`. Cats Effect remains `3.6.3`; MUnit resolves to `1.0.2`. Transitive cats-free remains `2.11.0` in lower modules and `2.12.0` in app; this is core/kernel alignment, not blanket convergence of every Cats artifact. The excluded `foundation.icon:blst-java` and `org.scalus:scalus-secp256k1-jni` remain absent from resolved reports; the existing compiler JLine JNI dependency is unrelated and unchanged.

Private evidence: `cardano-functional-domain-tests-20261010-second/result.json`, `resolved-after.json` and `tests.log` (SHA-256 `8cd12f2ca04d1153f5b21cf6d549648c1d48dddcb7270baee66a5c7c70829dc4`); the first evidence directory retains `resolved-before.json` and verified public Maven download hashes. An initial offline dependency-resolution failure is retained there; no tests ran in that first attempt. The focused result does not replace the integration aggregate and introduces no new live, restart, multi-epoch or full-ledger validation claim.

## Before and after: actual production use

These excerpts describe integrated source, not suggested APIs. They omit unrelated
arguments and branches; the linked production files and tests are authoritative.
All baseline findings in the numbered audit sections below remain historical.
In particular, the old lower-module Cats versions and absence of a core Cats
dependency do not describe the current build.

### Explicit capabilities and syntax

Before `8fd3500`, the service's guarded helper took the concrete owner, read it,
called `owner.withCurrent`, and recursively interpreted an `Either` for retries.
The current [service](../app/src/main/scala/lab/AdaSubmissionService.scala)
constructs one interpreter and installs both capabilities explicitly:

```scala
val capabilities = AdmissionPrograms.fromOwner(owner)
given AdmissionPrograms.Read[F] = capabilities
given AdmissionPrograms.Fence[F] = capabilities
```

Its constructor requires those capabilities with `using`. Admission and rebuild
now call `view.pin.commitIfCurrent(...)` through imported extension syntax;
snapshot, status, relay selection, expiry and shutdown use the shared guarded
program. The pin remains data: neither a pin nor `Read[F]` can supply `Fence[F]`.

[AdmissionPrograms](../app/src/main/scala/lab/AdmissionPrograms.scala) expresses
the program with `F[_]`, requiring only `Read[F]` for `current`, `Fence[F]` for
`commitIfCurrent`, and `Monad[F]` plus both capabilities for `guarded`. Its
`tailRecM` preserves exactly four attempts and returns the closed
`Guarded.Read`/`Exhausted` result; the interpreter maps the owner's existing
outcome into `Fenced.Applied`/`Stale`. It introduces no new fibers, gates or
cancellation masks. [Capability tests](../app/src/test/scala/lab/AdmissionProgramsSuite.scala)
check missing-capability compile failures, old/new retry traces, cancellation,
failure non-retry and the concrete owner's masked commit.

### Typed data between layers

Before `e7ba7e7`, owner view construction flattened validation failures to
strings between domain, pin and view construction. The actual
[owner](../app/src/main/scala/lab/SubmissionOwner.scala) now composes checked
opaque roles and `StatePin.checkedTyped` in a pure `Either` program. Failures
remain `ViewConstructionError.Domain`, `.Pin` or `.View` until the existing
effect boundary wraps them in `ViewConstructionFailure`, retaining `.error`.
The exception still extends `IllegalStateException` and keeps historical text.

[PinDomain](../core/src/main/scala/lab/submission/PinDomain.scala) distinguishes
owner, coherent-state, ledger-state and environment identities, plus generation
and validation-slot units. `StatePin.checkedTyped` takes those roles and the
closed `AdmissionProfile`. Passing an environment ID as a ledger ID is a compile
error. Public raw `StatePin` fields and legacy constructors deliberately remain
compatible; migration of every hash, quantity and caller is not complete.

### Closed HTTP failure policy

Before `4057885`, the actual HTTP renderer accepted
`failure(http: Int, code: String, category: String)`. Every classification branch
could choose an inconsistent triple. The [handler](../app/src/main/scala/lab/AdaHttpHandler.scala)
now takes a closed `FailureResponse` with 15 immutable wire mappings:

```scala
case InternalFailure extends FailureResponse(503, "InternalFailure", "unavailable")
```

Identity, ledger, Plutus phase-one and execution classification return that
type. Pool rejection and the actual unexpected-error fallback use the same
closed policy before rendering. For example, `failure(FailureResponse.InternalFailure)`
replaces the repeated raw triple. The original `handleError` stays at the same
effect boundary. Accepted, pending, state and retry/current-pin payloads are
unchanged, as are JSON key order, escaping, statuses and scope fields.

[Four boundary tests](../app/src/test/scala/lab/AdaHttpFailureSuite.scala) cover all
15 cases with exact response bytes, every nested validation branch, direct/nested
identity equivalence, pool/fallback mappings and private control/Unicode detail
redaction. Compile-negative checks reject arbitrary triples and string outcomes;
a compile-positive control confirms a valid typed call. No generic renderer,
new typeclass, dependency or resource topology was introduced.

Exact source `40578853389cc85c6253ec70bb3a5e2f704ed8b5` passed 1,978 public Scala/translator tests, the separate
55-test retained invocation, 27 public gates and 542 Python tests with two skips.
An initial test-fixture type error is retained separately; production code
compiled in that attempt. Phase log SHA256: `4d0e8766c7d82a240d136da083b2347c44c208fe90d4b5482ab6ad1107d6e12e`.

### Typed phase composition and resource completion

Before `915ead2`, one large research-runner comprehension carried unrelated
intermediate values. [The runtime](../app/src/main/scala/lab/PlutusResearchRuntime.scala)
now returns `Bootstrap`, `Followed` and `VerifiedInclusion` from ordered helpers.
The same effects and resource scopes remain; the typed records make which data
each next phase needs explicit. The clean default-stack compile and 48 focused
regressions passed after a preserved, context-dependent compiler stack overflow.

Before `4cc59cb`, repeated-service `result.json` was emitted inside the generation
resource's `use`, before its finalizer ran. The actual
[service runtime](../app/src/main/scala/lab/PlutusServiceRuntime.scala) now uses:

```scala
resource.use(body).flatMap { (value, success) =>
  boundedSave(output.resolve("result.json"), value, MaxTerminalBytes).as(success)
}
```

Default, JVM-only and native-checked modes share that path.
[Finalization tests](../app/src/test/scala/lab/PlutusServiceFinalizationSuite.scala)
prove that a result is absent during release and that release failure produces
failure status with no success result. This is an `IO` application lifecycle;
the smaller admission capability program is the effect-polymorphic component.

### Laws with behavior checks

[PinDomainLaws](../core/src/test/scala/lab/submission/PinDomainLaws.scala) runs
Discipline `EqTests` for four identity roles and `OrderTests` for generation and
validation slot. Separate properties compare raw denotations, and
[boundary tests](../core/src/test/scala/lab/submission/PinDomainSuite.scala)
check invalid hashes, the full uint64 range, wrong-role/unit compile failures
and legacy equality/hash behavior. These tests supplement owner concurrency and
service regressions; they do not prove repository-wide algebraic correctness
or JVM/native floating-point parity.

### Remaining work

- Migrate additional raw identities, units and string construction errors where
  they cross real boundaries; preserve compatibility only at explicit edges.
- Assess the ordered rebuild accumulator and duplicated renderers against their
  first-wins, cancellation and resource semantics before changing them.
- Keep wider orchestration effect requirements honest; do not add MTL, custom
  typeclasses or laws without an actual operation or custom instance to justify them.
- Complete the separate repeated terminal comparator and supervisor, then obtain
  measured restart, post-boundary transaction and multi-epoch evidence. Offline
  refactors and law tests do not establish those live outcomes.

## Follow-on: admission capability programs

`AdmissionPrograms` extracts actual service orchestration into `F[_]` programs with explicit `given`/`using` requirements. Read-only observation requires `Read[F]`; guarded execution additionally requires `Fence[F]` and `Monad[F]`. The interpreter uses only `Functor[F]` to map the owner's existing result. A pin's fluent `commitIfCurrent` operation requires a fence capability; possessing pin data or a read capability cannot manufacture that authority.

Service construction explicitly supplies both capabilities from the same owner interpreter. Admission and rebuild commits, guarded snapshots/status, relay selection, expiry and shutdown use this path. `Fenced.Applied/Stale` and `Guarded.Read/Exhausted` retain layer-specific results until the service renders its existing external result or compatibility exception. Guarded reads retain exactly four attempts and evaluate the action only inside a matching owner fence. The new programs do not acquire gates, mask cancellation, fork fibers, install evidence or move pool state themselves. Those operations and their order remain in the owner/service interpreters.

This is a concrete capability extraction, not a global effect-stack migration. No clock capability was added because the extracted programs do not read time; existing effectful clock calls remain where pool transitions need them. No new MTL dependency or direct MTL use was introduced. Wider orchestration, typed error and domain-ID migration remains unfinished.

Validation: the isolated 2-CPU/2-GiB offline follow-on build passed formatting and all 38 focused checks: six capability tests plus 32 existing owner/service/evidence regressions. Coverage includes compiler-negative capability separation, old/new retry-trace equivalence for zero through five stale observations, failure non-retry, cancellation before fencing, concrete-owner masked completion and stale action suppression. Private evidence: `cardano-admission-capability-tests-20261010-first/result.json` and `tests.log` (SHA-256 `2dc92510579a31952483259d6cd61a9d6498c71c82661e83bc3ddbd8ae59dd27`). No new live capability follows from these offline tests.

## Follow-on: typed owner view construction errors

`AdmissionView.checkedTyped` returns closed missing-input/full-pin-mismatch causes. Its existing `checked` method remains the string compatibility boundary with unchanged validation order and text. The actual `SubmissionOwner` view path now preserves `PinDomain.Error`, `StatePin.ConstructionError` and `AdmissionView.ConstructionError` in a `ViewConstructionError` sum; it no longer renders a string between each layer. `ViewConstructionFailure` renders once at the existing effect boundary while retaining the structured cause and the `IllegalStateException` compatibility superclass.

The pure `pinFor` assembly is used by the owner itself. Both existing `F.fromEither` boundaries, owner gate and cancellation mask remain in place; no lifecycle or authority operation moved. Admission profiles already use a closed enum at this boundary, so no redundant opaque profile wrapper was added. HTTP code/category strings remain at their final wire-rendering boundary. Broader CLI/runtime errors and remaining string compatibility callers are still unfinished.

Validation: formatting and all 42 focused tests passed in the isolated 2-CPU/2-GiB offline build. Four new tests cover actual owner field/reference equivalence, distinct domain/pin/view causes, historical rendering and validation order, and compiler-negative arbitrary-string substitution; the other 38 retain owner/service/evidence/capability regression coverage. Private evidence: `cardano-typed-admission-error-tests-20261010-first/result.json` and `tests.log` (SHA-256 `9333a25d5e7cdc58c12e0b7a54f942036e8159db35ab70e6f4ec3b0f53f60ada`). No live behavior or broader typed-error completion is claimed.

## Follow-on: checked rebuild context

Integrated commit `07106996174a5619e59272a4f2cf2fe2d605ba51` replaces the actual service's
`Option[(AdmissionView, AdaPool.Rebuild[StatePin])]` pending slot with
`Option[RebuildContext]`. The old tuple could represent a valid view paired with
work for a different pin or profile. The private-constructor
[context](../app/src/main/scala/lab/RebuildContext.scala) now checks complete pin
equality and profile identity once, returning closed `MissingInput`,
`PinMismatch` or `ProfileMismatch` causes. The production state-change path
constructs it before placing work in the pending slot; the worker and rebuild
method accept only that checked value.

This pairing is not publication authority. The existing owner fence and pool
token still decide whether revalidated work may commit. `sameWork` deliberately
compares the original work object's reference, preserving the rule that an old
completion cannot clear newer pending work. Queue reads, pending-slot rechecks,
permits, cancellation races, masking and evidence order are unchanged. Public
wire formats and compatibility constructors are unchanged.

The isolated 2-CPU/2-GiB build passed formatting and 43 focused tests: five new
pairing, profile, identity and compile-negative cases plus 38 existing owner,
service, evidence and capability checks. Existing rapid-generation, lost-wake
and cancellation regressions remain included. Log SHA256:
`f9cf48694cbada797357abda1f0cc651089131108d8c4803ae3c548dfaddc617`.
Independent source review matched all three changed source/test files to that
build. Ordered pool rebuilding and wider renderer/domain migration remain
separate work; this change establishes no new live result.

## Compiler-depth follow-on: research runtime phases

The aggregate build at `e7ba7e7` encountered Scala 3.3.8 `StackOverflowError` in posttyper while compiling the large `PlutusResearchRuntime.work` comprehension. A subsequent isolated default-stack incremental build against the preserved failing cache recompiled ten sources successfully before any source change. The failure therefore is not claimed to reproduce deterministically; aggregate/compiler context matters.

The runtime now uses private typed `Bootstrap`, `Followed` and `VerifiedInclusion` records, with ordered preparation, follow, inclusion verification and completion helpers. The existing effect blocks remain in order. Preparation still precedes Ref allocation; the node and HTTP resource scopes are unchanged; the relay background resource is still finalized within follow; `result.json` is still written only after HTTP and node release. No JVM stack increase, runtime limit change, policy relaxation or new live claim accompanies this structural change.

Validation: both the default-stack incremental build and a clean compile of all 114 application sources passed, followed by all 48 existing lifecycle/command/follower/owner/service/evidence/capability checks in each run. Formatting passed. No `-Xss` override was used. Private evidence: `cardano-research-runtime-phase-tests-20261010-clean/result.json` and `tests.log` (SHA-256 `c619954d09ea2012bb5f03ed9ffdc295929bd3d23d6a911d2d054468bb87c11f`); the baseline and first incremental evidence directories retain their separate outcomes. The original aggregate failure remains in `cardano-plutus-service-phase-20261010-eighth/tests.log`. The full integration aggregate remains a separate parent check.

## Decision and priorities

The code is already functional at important boundaries: pure immutable ledger transitions, private-constructor admission evidence, typed rejection sums, Resource-owned lifetimes, and a single admission/publication gate. Preserve these. The strongest improvement is to make policy and authority visible in types; replacing every match or local mutable variable would not achieve that.

| Priority | Concrete change | Why it matters | Scope |
| --- | --- | --- | --- |
| P1 | Typed follower/relay termination and explicit wire rendering | Exception text and enum `toString` currently influence classification; ordinary renaming can alter reported service success/failure | App; service WIP and committed follower |
| P1 | Resolve admission callback/evidence I/O contract mismatch | A supposedly memory-only masked commit performs filesystem I/O while holding the mutation gate | App + admission capability contract |
| P1 | Sum type for evaluation phase/outcome, private binding remains intact | Illegal combinations currently reach `require` after pool mutation and can close/poison admission | App evidence boundary |
| P2 | Domain-specific opaque IDs, quantities and checked configuration | Same-shaped hashes and units are interchangeable to the compiler | Start at core/submission; migrate outward |
| P2 | Typed boundary errors and a small error renderer | String erasure loses diagnostics; generic `Show` could expose sensitive details | Submission, app HTTP, network adapters |
| P2 | Extract capabilities and use MTL selectively | Large concrete orchestration needs test seams, not a new global effect stack | App runtime only initially |
| P3 | Pure ordered rebuild fold and shared renderers | Reduces local duplication while preserving first-wins reservations | Ledger and app |

P1 here means prioritize before broadening or depending on sustained service behavior. It does not assert a demonstrated exploit or negate the existing live evidence. None of these changes enables full ledger validation, repeated epochs, or unrestricted Plutus execution.

## 1. Stop policy must not depend on diagnostic strings

Committed [PlutusSameEpochFollow.scala](../app/src/main/scala/lab/PlutusSameEpochFollow.scala), lines 17–27 and 217–223, stores `Outcome.reason: String`, builds `failure:${e.getMessage}`, and renders a typed `EphemeralStreaming.Stop` with `toString`. These are suitable terminal display operations only if consumers never interpret them.

The observed WIP `app/src/main/scala/lab/PlutusServiceRuntime.scala` does interpret them: lines 368–380 distinguish exact exception messages `relaySessionLimit` and `serviceUnavailable`, accept both `deadline`/`Deadline` and `blockLimit`/`BlockLimit`, and lines 413–417 decide stopped versus failed from a set of three strings. Lines 115–120 also return `(Long, String)` for deadline and policy. The WIP follower adds `completionReason: String` at line 72 and uses that string to decide whether `EpochRefused` receives a special outcome at lines 243–251.

Use closed `FollowStop`, `RelayStop` and `ServiceStop` sums, with payloads for known failure categories. Keep `EphemeralStreaming.Stop` as a nested typed value when appropriate. Use a `CompletionGoal` enum for inclusion versus published-block budget. Give one exhaustive function the responsibility for converting these results into `ServiceStop`; give a separate renderer stable, explicit wire codes. The conversion from service stop to terminal category should also be exhaustive: adding a new stop should require a compiler-visible policy decision. Do not derive public codes from case names or ordinals.

Expected limit exhaustion is a returned value, for example `F[RelayStop]`. Unexpected I/O exceptions remain in the effect error channel, retaining their cause for private diagnostics. Interpret them once at the terminal boundary. If the follower's event callback API temporarily requires exception interruption, use a private typed exception carrying a domain error and catch it by type; do not throw or classify text tokens. Preserve `race` loser cancellation/finalization before terminal capture. A cancellation is neither a rejected transaction nor a successful deadline exit.

Security/correctness consequence: the present WIP string classifier can drift silently as producers change. This audit did not demonstrate that a peer can inject a success token; the finding is a real policy coupling, not a claimed remote exploit.

## 2. Evidence I/O is inside a documented memory-only authority gate

[AdmissionState.scala](../ledger-runtime/src/main/scala/lab/submission/AdmissionState.scala), lines 37–43, says `withCurrent` callbacks must be bounded memory-only work and must not perform I/O. [SubmissionOwner.scala](../app/src/main/scala/lab/SubmissionOwner.scala), lines 45–47 and 104–108, masks the callback under the semaphore and poisons the owner on callback failure.

In [AdaSubmissionService.scala](../app/src/main/scala/lab/AdaSubmissionService.scala), lines 96–110, pool publication and `observe` occur inside that callback. Rebuild does the same at lines 165–188. [PlutusEvaluationEvidence.scala](../app/src/main/scala/lab/PlutusEvaluationEvidence.scala), lines 89–115, masks a blocking file write, force and hard-link publication. [PlutusResearchNode.scala](../app/src/main/scala/lab/PlutusResearchNode.scala), lines 33–37, connects this store to the service. This is an actual contract mismatch, not hypothetical usage.

The evidence design deliberately holds eligibility behind the owner fence until persistence completes. Its five-second timeout is cooperative and cannot make a masked filesystem operation a hard deadline. A stalled filesystem can therefore stall mutation, relay selection and shutdown. Existing comments in AdaSubmissionService lines 44–46 acknowledge this; the public capability contract does not.

First make the contract truthful and testable. Narrow arbitrary `withCurrent(commit: F[A])` access to the internal service and expose named operations for compare-and-install, move, close and eligibility selection. Keep the current evidence-backed installation semantics explicit in that owner interpreter. A generic `F[A]` parameter cannot enforce memory-only behavior or non-reentrancy. A marker trait alone would not enforce it either; trusted implementations and package-private constructors remain necessary.

Do not move evidence writes into a detached fiber or put `poll` around them as a style fix. A future two-stage design would need an ineligible pending state, bounded persistence, fresh-pin recheck, eligible publication, and precise failure/recovery semantics; that is a separately reviewed behavior change. For the current design, document cooperative liveness and retain fail-closed behavior.

Existing evidence tests already cover sink failure, masked persistence cancellation and a cooperative deadline: [PlutusEvaluationEvidenceSuite.scala](../app/src/test/scala/lab/ledger/PlutusEvaluationEvidenceSuite.scala), lines 330, 347 and 409. They are strong contracts to preserve, not proof of a hard filesystem deadline.

## 3. Replace phase/outcome products with valid sums

[PlutusEvaluationEvidence.scala](../app/src/main/scala/lab/PlutusEvaluationEvidence.scala), lines 49–57, validates combinations of two strings at runtime: admission permits five outcomes and revalidation permits two. A typo in [AdaSubmissionService.scala](../app/src/main/scala/lab/AdaSubmissionService.scala), lines 102–108 or 183–186, throws inside the owner gate after state work. This can close the pool and poison the owner even though the intended change was only diagnostic naming.

A representative proposal follows. This is an uncompiled design sketch; it introduces no runtime patch. The real candidate/pin/execution identity checks and private Observation constructor remain unchanged.

```scala
// Before: caller constructs an invalid-state product.
observe(candidate, "revalidation", if retained then "retained" else "discarded")

// After: only meaningful combinations can be represented.
enum AdmissionOutcome:
  case Accepted, AlreadyPresent, PoolRejected, Retry, Unavailable

enum RevalidationOutcome:
  case Retained, Discarded

enum EvaluationEvent:
  case Admission(outcome: AdmissionOutcome)
  case Revalidation(outcome: RevalidationOutcome)

// Narrow the existing method; keep its body, mask and identity checks.
// def observe(candidate: Candidate[StatePin], event: EvaluationEvent): F[Unit]
val event = EvaluationEvent.Revalidation(
  if retained then RevalidationOutcome.Retained else RevalidationOutcome.Discarded
)
observe(candidate, event)

// One total elimination at the serialization boundary; stable public tokens.
def wire(event: EvaluationEvent): (String, String) = event match
  case EvaluationEvent.Admission(outcome) =>
    val code = outcome match
      case AdmissionOutcome.Accepted       => "accepted"
      case AdmissionOutcome.AlreadyPresent => "already-present"
      case AdmissionOutcome.PoolRejected   => "pool-rejected"
      case AdmissionOutcome.Retry          => "retry"
      case AdmissionOutcome.Unavailable    => "unavailable"
    ("admission", code)
  case EvaluationEvent.Revalidation(outcome) =>
    val code = outcome match
      case RevalidationOutcome.Retained  => "retained"
      case RevalidationOutcome.Discarded => "discarded"
    ("revalidation", code)
```

The renderer still matches: that is correct sum-type elimination. The improvement is removal of invalid combinations and scattered policy strings, not a match-count reduction. Add a compile-negative example demonstrating that an Admission cannot contain a RevalidationOutcome, and enumerate all seven encoded cases against the existing schema. Evidence bytes, filenames, hashes and retention bounds must remain unchanged.

## 4. Domain types should distinguish roles, not merely lengths

[StatePin.scala](../core/src/main/scala/lab/submission/StatePin.scala), lines 10–18 and 45–61, takes four `Bytes` identities, two `BigInt` quantities, a point and a string profile. Shape checking rejects malformed inputs but cannot detect a same-length ledger/environment/owner digest swap. The complete equality implementation at lines 20–37 is essential and should be retained semantically.

Introduce separate opaque `OwnerId`, `CoherentStateId`, `LedgerStateId`, `EnvironmentId`, `TransactionId` and `EnvelopeDigest` types, backed by immutable Bytes. A single `Hash32` alias would still allow role swaps. Validate length/null at ingestion; obtain the distinct wrappers through smart constructors and explicit decoders. Use `AdmissionProfile` inside a pin rather than its string ID, retaining exactly the existing external ID mapping. Add `Generation`, `ValidationSlot`, `MonotonicNanos`, `UnixMillis`, `EpochNo` where unit confusion crosses a method boundary. Unsigned protocol values remain checked BigInt values; replacing them with Long would truncate the uint64 domain.

Scala 3 opaque aliases need no new library. Keep their representation encapsulated in distinct companions, with explicit conversion at codecs and no blanket Conversion instances. Prefer native enums for small closed sets. Enumeratum is justified only if a real adapter needs its naming/value lookup and integrations; it does not replace payload-bearing error ADTs or profile evidence. Never use `withName` throwing lookup on untrusted input; use a checked lookup and freeze wire spellings. Do not add a newtype macro dependency merely to avoid a few opaque companions.

A shape-checked ID or StatePin remains data, not authority. Preserve owner generation checks, source object identity checks, private candidate constructors and runtime fences. In particular [ScopedAdmission.scala](../ledger/src/main/scala/lab/ledger/ScopedAdmission.scala), lines 96–104, intentionally requires the Plutus prepared source to be the same view object. An Eq instance must not erase that identity requirement.

## 5. Typed failures, selective MTL and capability boundaries

The useful existing model is [ScopedAdmission.Failure](../ledger/src/main/scala/lab/ledger/ScopedAdmission.scala), lines 11–15, and the Plutus/ledger failure sums beneath it. Keep the distinction between malformed input, unsupported profile, ledger rejection, stale provenance, script/budget failure and infrastructure failure.

Replace [StatePin.checked](../core/src/main/scala/lab/submission/StatePin.scala):54 and [AdmissionView.checked](../ledger-runtime/src/main/scala/lab/submission/AdmissionState.scala):12 string errors with small typed construction/binding errors. [ScopedAdmission.scala](../ledger/src/main/scala/lab/ledger/ScopedAdmission.scala):52–55 currently erases a fee-size error to `Unsupported(e.toString)`; preserve the underlying error in a dedicated case. In [TxSubmission2Session.scala](../network-runtime/src/main/scala/lab/network/TxSubmission2Session.scala):143–148, attach typed protocol/deadline categories rather than wrapping every string in IllegalArgumentException. Error details may remain bounded strings for diagnostics; they must not be executable policy.

[AdaHttpHandler.scala](../app/src/main/scala/lab/AdaHttpHandler.scala):85–126 is a valid total translation of nested domain errors. Extract a small `ApiFailure(status, code, category)` value and one renderer to remove repeated literal triples, while keeping the exhaustive classification near the HTTP boundary. Do not give all errors a public `Show` derived from `toString`: current redaction deliberately omits exception text and transaction/key material. Keep private diagnostic rendering separate. A `ToApiFailure[E]` typeclass is useful only if multiple adapters genuinely share the same mapping; otherwise named functions are simpler and prevent accidental global policy instances.

Recommended boundaries:

- `AdmissionState[F]` already separates owner capability from concrete runtime. Strengthen this existing seam rather than introducing another parallel service hierarchy.
- Extract `RelayRunner[F]` and `Follower[F]` from large IO orchestration; return typed terminal observations. Keep sessions/connections as Resource and give their interpreters Async or Temporal only where required.
- Use an `EvidenceStore[F]` capability for ordered persistence with its true error/cancellation contract; the existing Observer is a starting seam. Durable acknowledgment is not ordinary Writer logging.
- Keep ledger validation pure `Either[DomainError, A]`. Use sequential `traverse` for independent ordered transformations, `foldLeft` for dependent state, and `ValidatedNec` only for independent static configuration checks. Do not accumulate signature/authorization/state failures and continue security-sensitive work.
- Use `Ask[F, ServiceConfig]` for immutable validated configuration. Do not implement Ask by repeatedly reading a changing owner snapshot; that is live state with authority and freshness semantics. Use the owner algebra explicitly.
- Use `Raise[F, E]` in small generic policy functions, `Handle[F, E]` where recovery belongs. Do not require Async merely to check an equality. Do not claim a free Raise[IO, DomainError] instance: choose an interpreter such as EitherT or a deliberately reviewed typed bridge.

For example, using the already resolved cats-mtl 1.3.1 API and Scala 3 syntax:

```scala
import cats.Monad
import cats.mtl.Raise

enum PinError:
  case Mismatch(expected: StatePin, actual: StatePin)

def requireSamePin[F[_]: Monad](expected: StatePin, actual: StatePin)(
    using errors: Raise[F, PinError]
): F[Unit] =
  if expected == actual then Monad[F].pure(())
  else errors.raise[PinError, Unit](PinError.Mismatch(expected, actual))
```

Interpret a pure helper with `[A] =>> Either[PinError, A]`, or a local program with `[A] =>> cats.data.EitherT[IO, PinError, A]`. This sketch does **not** authorize replacing `owner.withCurrent` with a separate read/check/write; that would introduce a TOCTOU race. Keep the equality and commit inside the existing owner authority.

Keep Resource ownership in the base effect and use a local EitherT program inside `use` where practical. Preserve the three distinct outcomes: successful value/domain rejection, unexpected Throwable, and cancellation. Avoid a home-grown global MonadError/Async instance to smuggle domain errors into IO. Avoid Stateful over the live ledger owner: generic get/set obscures its compare-and-commit protocol. Tell is appropriate for bounded pure trace accumulation, not evidence persistence or unbounded service history.

## 6. Which branching and imperative code should remain?

| Source | Recommendation |
| --- | --- |
| [AdmissionValidation.scala](../ledger/src/main/scala/lab/ledger/AdmissionValidation.scala):7–18 | Keep the three-case exhaustive profile match. Both initial admission and rebuild already share it. An open implicit validator registry could accidentally enable unsupported profiles. |
| [SubmissionOwner.scala](../app/src/main/scala/lab/SubmissionOwner.scala):48–53, 140–153 | Keep lifecycle ADT elimination. Initializing, active, poisoned and closed have distinct authority; this is not duplicated dispatch. |
| [AdaSubmissionService.scala](../app/src/main/scala/lab/AdaSubmissionService.scala):102–119 | Extract one pool-outcome-to-service-result conversion and one typed evidence classification. Do not make a universal fold abstraction solely for two small matches. |
| [AdaPool.scala](../ledger/src/main/scala/lab/ledger/AdaPool.scala):224–267 | Replace the three local vars with a private `RebuildAcc(kept, reservations, history)` and an ordered fold if readability improves. Preserve original admission order, old timestamps, profile/pin checks, rebuild-token identity and first-wins dependency reservations. |
| [AdaPool.scala](../ledger/src/main/scala/lab/ledger/AdaPool.scala):205–221 | Keep all per-entry Either results. A fail-fast `traverse` into Either would incorrectly stop the rebuild at the first rejection. |
| [AdaIngressBudget.scala](../ledger-runtime/src/main/scala/lab/ledger/runtime/AdaIngressBudget.scala):16–30 | The AtomicBoolean enforces a once-only request token; do not replace it with a non-atomic pure-looking check. Keep resource finalizers releasing permits without undoing accepted entries. |
| [PlutusEvaluationEvidence.scala](../app/src/main/scala/lab/PlutusEvaluationEvidence.scala):102–113 | A channel write loop and try/finally inside F.blocking are appropriate Java interop; replacing them with lazy collection syntax adds little. |
| [AdaSubmissionService.scala](../app/src/main/scala/lab/AdaSubmissionService.scala):201–211 | Preserve the pending-slot recheck after either race outcome. A losing queue take may consume a wake; a prettier recursive stream can reintroduce lost wakeups. |
| [TxSubmission2Session.scala](../network-runtime/src/main/scala/lab/network/TxSubmission2Session.scala):166–182 | Existing foldLeftM correctly threads aggregate byte bounds and retained originals. Parallel traversal would alter resource exposure and accounting. |

Do not make transaction-dependent state transitions parallel merely because Parallel is available. No Monoid for candidate publication, StatePin combination or winner selection is justified. Local mutation hidden inside a pure bounded function is lower priority than global mutable state or an inaccurate effect contract.

## 7. Dependency/API verification and concrete choices

[build.sbt](../build.sbt):26 pins Scala 3.3.8; lines 30, 57–59 and 83–85 enable fatal warnings and pin Cats Effect/testkit 3.6.3 and MUnit 1.0.2. Core and ledger do not directly depend on Cats; adding syntax there would be a deliberate module dependency change, not a free refactor.

Retained sbt update reports were read without rerunning resolution. At `app/target/scala-3.3.8/update/update_cache_3/output`, selected Cats core/kernel are 2.13.0 and MTL is 1.3.1 (SHA256 `6b1598f81c5fc21c8a52dbacafc29c605a770adf13a16f2ff7a5902e22dab5c0`). Network-runtime's corresponding report selects core/kernel 2.11.0 and MTL 1.3.1 (SHA256 `f880dd10e02bea30dbe7a3e36cabad49b476ce2ed2832de4cccc0ed4a75f6235`). These are retained build evidence, not a fresh resolution guarantee. Cache availability alone was not treated as selected dependency evidence.

| Choice | Verified source and migration decision |
| --- | --- |
| Keep Cats Effect 3.6.3 | Its [tagged build](https://raw.githubusercontent.com/typelevel/cats-effect/v3.6.3/build.sbt) cross-builds Scala 3, declares Cats 2.11.0 and MTL 1.3.1. Do not infer app's final version from this alone. Keep Resource, Ref, Semaphore and cancellation semantics. |
| Start with MTL 1.3.1 | Its [Raise source](https://raw.githubusercontent.com/typelevel/cats-mtl/v1.3.1/core/src/main/scala/cats/mtl/Raise.scala) has raise/ensure and Either/EitherT instances. No `Raise.fromEither` call is assumed in the proposed helper. Add an explicit direct dependency to a module only when production code starts importing it. |
| MTL 1.6.0 is an optional later upgrade | [Tagged build](https://raw.githubusercontent.com/typelevel/cats-mtl/v1.6.0/build.sbt) uses Scala 3.3.6 and Cats 2.13.0, and defines cats-mtl-laws. [Typelevel's error-handling article](https://typelevel.org/blog/custom-error-types.html) introduces the newer syntax. It must not be pasted into this 1.3.1 project as though already available. Test eviction/API compatibility before adopting. |
| Add law tests in Test scope | Use `org.typelevel %% cats-laws` matching each module's selected Cats version, `cats-mtl-laws` matching MTL if custom MTL instances are added, and `discipline-munit` 2.0.0 as a concrete candidate. Its [tagged build](https://raw.githubusercontent.com/typelevel/discipline-munit/v2.0.0/build.sbt) cross-builds Scala 3.3.3 and uses MUnit/munit-scalacheck 1.0.0; verify alignment with this repo's 1.0.2 before merging. No dependencies were installed here. |
| Native opaque aliases and enums first | [Scala's opaque-type reference](https://docs.scala-lang.org/scala3/reference/other-new-features/opaques.html) describes representation hiding. Enumeratum's [upstream build](https://raw.githubusercontent.com/lloydmeta/enumeratum/master/build.sbt) includes Scala 3.3.8 and Cats integration projects. That confirms current Scala 3 support, not a pinned release recommendation. Defer installation until a concrete adapter needs it, then pin and compile-test the release. |

The [Cats law-testing guide](https://typelevel.org/cats/typeclasses/lawtesting.html) identifies `munit.DisciplineSuite`, `checkAll` and the separate `cats.kernel.laws.discipline` namespace for Eq/Order/Monoid. The [MTL Ask rule set](https://raw.githubusercontent.com/typelevel/cats-mtl/v1.3.1/laws/src/main/scala/cats/mtl/laws/discipline/AskTests.scala) is available at the project's current MTL version. Use upstream instances unless custom semantics are genuinely needed.

## 8. Law and regression plan

For new immutable domain wrappers, test `EqTests[T].eqv` and, only where meaningful, `OrderTests[T].order` under `munit.DisciplineSuite`, with Arbitrary/Cogen for valid bounded values. Include boundary and invalid constructor tests separately: law generators over valid IDs will not test malformed input. Add explicit Eq/hashCode agreement and complete-pin field sensitivity; an equivalence relation that ignores generation could pass Eq laws and still break authority.

Do not provide Order for opaque capability identities just because the backing bytes can be sorted. A separate deterministic serialization key may be more honest. Do not normalize raw Float evidence through an Eq instance: raw nonmyopic parity and JVM/native arithmetic remain separate correctness questions.

For MTL, use upstream Ask/Handle discipline rules only if an instance is authored. Test a finite pure interpreter, not an unsafe global Eq[IO[A]]. Raise 1.3.1 documents no external laws; do not invent a standard RaiseTests contract. For a helper, test short-circuiting: the mismatch case invokes no downstream capability. If a custom Functor is genuinely introduced, use FunctorTests for identity/composition; do not add an instance merely to have a law suite.

Behavioral tests are separate from algebra laws:

1. Typed stop matrix: each known follower/relay stop maps to exactly one terminal category and stable wire token; changing private error text cannot change policy. External cancellation remains cancellation. Deadline/cancellation close peer resources before final observation.
2. Evidence enum: all seven valid encodings match previous bytes; invalid pairs fail to compile. Sink errors retain fail-closed eligibility and no successful receipt. Simulated masked persistence cannot release the owner gate early; avoid a real stuck-filesystem test.
3. Owner linearization: reuse [SubmissionOwnerSuite.scala](../app/src/test/scala/lab/SubmissionOwnerSuite.scala):234, 271, 300, 330 and 399 for noninterleaving, waiter cancellation, masked commit, poisoning and generation exhaustion. Domain errors returned inside an effect are not Throwables; preserve intentional poison behavior at invariant failures when adopting EitherT.
4. Admission/rebuild: reuse [AdaSubmissionServiceSuite.scala](../app/src/test/scala/lab/AdaSubmissionServiceSuite.scala):171, 210, 267 and 295 for conflicts, stale validation, cancellation and rapid generations. Differentially compare old/new pure rebuild folds over bounded generated sequences, including rejected first entries, dependency conflicts, expired entries and stale rebuild tokens.
5. Ingress: retain [AdaIngressBudgetSuite.scala](../ledger-runtime/src/test/scala/lab/ledger/runtime/AdaIngressBudgetSuite.scala) request-before-body allocation, two validation jobs/six waiters, cancellation release and once-only validation tests.
6. Error privacy: preserve [PlutusAdmissionErrorSuite.scala](../app/src/test/scala/lab/PlutusAdmissionErrorSuite.scala):29 categories and redaction; arbitrary exception text and secret-like diagnostic strings must not reach HTTP JSON. Test encoder escaping independently.

Use existing cats-effect-testkit 3.6.3 TestControl with Deferred-controlled race points for deterministic in-memory scheduling. Real blocking file operations need bounded integration tests; virtual time does not model OS I/O latency. Compile-negative tests should cover swapped IDs/time units and inaccessible evidence constructors. No tests or sketches in this document were compiled or executed during this audit.

## 9. Migration phases and acceptance gates

1. **Typed presentation policy, no concurrency change.** Add stop/event sums and explicit codecs, preserve current JSON bytes/statuses and all scope disclaimers. Update focused schema/error tests and compile with fatal warnings. Coordinate with the service owner after its WIP stabilizes; do not cherry-pick speculative edits over active soak work.
2. **Truthful capabilities and errors.** Clarify the evidence-backed commit contract; narrow callback exposure; add typed construction errors and selective Raise helpers. Keep existing Resource topology, gate acquisition, masking and poison behavior. Require concurrency regressions before merging.
3. **Role types and lawful instances.** Migrate StatePin identities/profile, then time/quantity boundaries. Use explicit codecs at old API boundaries, compile-negative tests and Eq/Order laws. Avoid simultaneous protocol schema changes or blanket implicit conversions.
4. **Reduce duplication and local state.** Extract shared renderers and the ordered rebuild accumulator. Compare pure outputs and error ordering before/after. Stop if abstraction increases indirection without removing a real invalid state or duplicate policy.
5. **Optional dependency alignment/MTL upgrade.** Pin direct Cats/MTL dependencies for importing modules, align Test law artifacts, inspect evictions and run relevant suites on Scala 3.3.8. Adopt MTL 1.6 syntax or Enumeratum only for a demonstrated benefit. Keep this separate from semantics-sensitive owner work.

No phase removes one-boundary/empty-go restrictions, changes wire negotiation, treats relay acknowledgement as ledger acceptance, or changes evaluator/script/cost-model pins. A type refactor must preserve original transaction bytes and state/environment binding as well as return values.

## Evidence boundary for ongoing main work

The WIP claims above refer to observed source snapshots, not the immutable baseline or a test result:

- `app/src/main/scala/lab/PlutusServiceRuntime.scala`: SHA256 `51f02101551b597a3de3cf27b2ec44134a377598ac1be5447cfc71cb6cd4f884`.
- `app/src/main/scala/lab/PlutusSameEpochFollow.scala`: SHA256 `832bca9bf856d76a689663aa2d066a4cfce26b9b2ea727c7dfcc9fec551b6c7d`.

All other source links resolve in this audit worktree at the baseline. WIP may change while the owner develops soak prerequisites; recheck those findings against its eventual commit. This document contains distilled source evidence and references, not raw logs.
