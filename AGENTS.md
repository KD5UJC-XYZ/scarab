# Instructions for agent sessions

Scarab is a Cardano-compatible node in Scala 3.3 (LTS), written as tagless-final cats-effect code
with cats-mtl, and checked against the Haskell `cardano-node` as the reference. These instructions
apply to every automated session working in this repository; human contributors follow the same
rules through [CONTRIBUTING.md](CONTRIBUTING.md).

For orientation only: [docs/codemap.md](docs/codemap.md) (modules, entry points, suites),
[docs/questions.md](docs/questions.md) (where to start reading), [docs/glossary.md](docs/glossary.md)
(terms) and [docs/playbooks/](docs/playbooks/README.md) (recurring tasks). They set no rules; where
they differ from this file or CONTRIBUTING, this file and CONTRIBUTING win.

## Before you start

1. Read [docs/process.md](docs/process.md) (how work is tracked) and find the modules and suites the
   work touches in [docs/codemap.md](docs/codemap.md).
2. Run `python3 scripts/status.py` (`--area <area>` for one area) and check the issue's state. If it is
   `status:in-progress` and you were not asked to take it over, someone else is on it.
3. Find the issue's roadmap ID in [docs/roadmap.md](docs/roadmap.md), if it has one. A follow-up issue often
   has none: then read the issue it follows up (linked in its body) and that issue's roadmap item.
4. Read the issue, then any [design document](docs/design/README.md) and [ADRs](docs/adr/README.md) it
   links, and the [decisions](docs/decisions.md) that cite it.
5. Pick the [playbook](docs/playbooks/README.md) for the task, if one exists.
6. Name the level of the change and its gates
   ([CONTRIBUTING.md](CONTRIBUTING.md#gates-and-ceremony-by-level-of-change)).

Then:

- Work in your own git worktree from `main` for now, and rebase onto the PR target before reporting.
  The owner will activate `feat/*` -> `dev` -> `main` promotion under
  [the release design](docs/design/releases.md): after cutover only same-repository `dev` may
  target `main`; other targets accept any source. `release/alpha`, `release/beta`,
  `release/preprod` and `release/prod` also use protected PRs. Until then `main` remains the
  working target. At cutover use the [reviewed promotion workflow](docs/promotion-cutover.md)
  for `dev` -> `main`; do not treat a shared commit's green check as source-branch authority.
  #161 tracks enforcement;
  do not claim protections are enabled or change repository settings.
- Feedback from the owner arrives as inline commit comments and issues labelled `owner-review`; triage
  it as [CONTRIBUTING.md](CONTRIBUTING.md#owner-review-feedback) says, and a general rule goes into this
  file together with a sweep of the tree.

## Build and run: Docker only

- Never run `java` or `sbt` on the host. Build and test in the JDK image (`scarab-jdk:local`, built
  from `docker/jdk.Dockerfile`), as [CONTRIBUTING.md](CONTRIBUTING.md#build-docker-only) shows.
- For many small edits use a persistent sbt server container **of your own**, and give it a private
  server socket with `-e SBT_GLOBAL_BASE=/tmp/sbt-global`. Containers that share the dependency cache
  otherwise share one socket path, and a client can reach another session's server.
- Use your own devnet name: `python3 scripts/devnet.py --name <yours> <scenario>`, then
  `python3 scripts/devnet.py --name <yours> down`. Run data goes to `${SCARAB_DATA_DIR:-/tmp/scarab}`,
  never into the checkout or a home directory.
- Never kill processes by pattern (`pkill -f`): it can match your own shell. Kill by PID, or remove
  your own container.
- Remove the containers you started when you finish.

## How code is written here

The owner's requirements, in their words: *"functional type safe code that leans on the compiler to
enforce correctness as much as possible"*; *"retain the use of Ask/Stateful/Reader and so on from
MTL. Lean on the compiler with syntax classes, implicit derivation, givens, using, etc. … elegant
scala code … I love the use of type parameters when I can use them"*.

- Tagless final at effect boundaries; capabilities through cats-mtl (`Ask`, `Local`, `Stateful`,
  `Raise`, `Handle`, `Tell`). `IO` only in `Main` and tools.
- Closed, domain/service-specific error ADTs, including pure functions and test helpers. Use
  `Either[DomainError, A]` for pure failures and cats-mtl capabilities at effect boundaries. Preserve
  typed causes across layers; render text only for logs, CLI output or wire presentation. A generic
  `Failure(message: String)` wrapper is not a migration to domain error cases. No `Either[String, …]`,
  no string-based control flow, no `throw` for domain errors, no `.get` on values that are not
  provably present. The remaining migration and CI guard are tracked by M11-E in
  [the roadmap](docs/roadmap.md) under [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82).
- Functional code is the default. Imperative, side-effecting implementations are allowed only in
  performance-critical code, with profiling or benchmark evidence and a written reason for the
  specific path (record measurements in [performance.md](docs/performance.md)). Keep mutation local
  behind a pure interface or an effect interpreter. This exception changes implementation style
  only: typed errors, capability scope and original-byte guarantees still apply.
- A fiber never raises through a capability it did not create ([ADR 0017](docs/adr/0017-fibers-end-with-values.md)).
  A `Raise` from `Handle.allow` is valid in the fibers of its scope only; raised in any other fiber it
  is an exception that nobody catches. A fiber that outlives the call (`background`, `supervise`, a kept
  `start`) runs its body in a scope of its own and returns `Either` at its boundary (`Fibers.scoped`,
  `Fault.fiber`), its owner raises the value again in its own fiber (`Fibers.land`, `Fault.land`), and a
  channel or client takes the caller's `Raise` as a `using` clause instead of holding one. Example:
  `Fibers.scoped[F, NetworkError, Unit](defect)(client.ping).flatMap(_.fold(mux.abort, _ => F.unit))`.
  A fiber that its owner joins at once (`race`, `parSequence`, `Compute.within`) may use the owner's
  capabilities. An exception that is not ours is wrapped at the boundary into a typed case that says
  it is a defect; it is never "the peer is unavailable". Suites that start fibers extend `FailsOnUncaught`.
- In concurrency tests, evaluate blocking latch waits and joins before passing their results to
  MUnit assertions: clue capture holds the suite monitor while evaluating an assertion, so a worker
  assertion can deadlock with a parent's asserted wait. Release latches and finish owned workers in
  guaranteed cleanup. Positive synchronization uses an observable condition or controlled schedule;
  bounded negative observations without a product-side hook remain explicitly tracked in #251.
- Opaque types, type classes with extension syntax, instances in companions, `derives` where it
  works, Monocle optics for nested state. Type parameters for roles and eras (`KeyHash[StakePool]`,
  `LedgerState[Conway]`).
- The Haskell node is the reference ([ADR 0001](docs/adr/0001-haskell-node-as-reference.md)): mirror
  its behaviour, types and formats exactly, and cite the upstream file and line at the pinned
  version. **Agents may not deviate from the reference on their own accord.** A deviation, even for a
  measured performance benefit or another strong reason, needs the owner's explicit approval first,
  requested with clearly defined trade-offs and reasoning. Only after that approval is it
  implemented, with a written reason that records the approval. Existing code, earlier PRs, review
  notes and design documents are not approval. If exact fidelity seems impossible, stop and ask the
  owner.
- Pure rules; exact `Rational` arithmetic; anything hashed or signed is hashed over its original bytes.
- Stack safety ([ADR 0011](docs/adr/0011-stack-safety.md)): recursion whose depth or width comes from outside
  data (blocks, peers, files, queries) is a library fold/traversal, `@tailrec`, `tailRecM` over a typed
  state, or an explicit stack machine; never recursion inside an `Either`/`Option` for-comprehension.
  Test it on a small-stack thread.
- Comment density and naming like the surrounding code; SPDX header on new files.
- Keep parameter lists free of comments, including constructor fields and call arguments. Put
  parameter contracts in the declaration's Scaladoc (`@param` where useful), and explain a call
  before the expression. Elsewhere, preserve comments that explain intent, invariants, reference
  correspondence or a non-obvious tradeoff; check their reason before rewriting or removing them.
  Refer to maintained symbols, ADRs and pinned upstream sources rather than private notes,
  implementation-stage labels or unpinned line numbers.
- An accepted ADR's decision text is not rewritten. A stale example or symbol name may be corrected
  in place; any other correction, and a supersession, goes in a dated "Amendment (YYYY-MM-DD)"
  section that links what replaced it, and the superseded words in the decision are struck through
  (`~~…~~`) with a link to that amendment, so a reader sees where they read it that the text is stale.
- Each policy or fact has one authoritative home (gates in CONTRIBUTING, decisions in ADRs, process
  in [docs/process.md](docs/process.md)); other documents link to it rather than restating it.
- No volatile facts in static documents: issue state, test counts and "current" versions come from
  GitHub, the registries (`docs/data/reference-pins.json`, `PersistedFormat.scala`) or dated
  evidence rows.

## Conformance expectations

- Run original upstream cases without Scarab-specific expected-failure lists, field masks or
  numeric tolerances. Historical differences and transaction reachability do not justify an
  exclusion. A JVM-specific exception needs a minimal reproducer, evidence of the platform
  limitation, exact affected cases and an issue with a removal condition.
- A missing implementation or fixture is an explicit incomplete gate, never a conformance pass.
  Environmental availability skips and intentionally partial projections must be reported with
  their scope; neither establishes agreement for untested cases or fields.

## Before you hand back

- Name the level of your change and run its gates, no more
  ([CONTRIBUTING.md](CONTRIBUTING.md#gates-and-ceremony-by-level-of-change)): at least `check`
  (formatting, `-Werror`, every module's tests, scalafix), plus one scenario per area touched.
  A documentation-only change (level 0) needs only that its relative links resolve.
  Soak, repeats and the long network replays are nightly CI's, not yours, unless your stage's design
  names them as its jump gate.
- A persisted format must not change without a version bump, a migration and a test that reads the
  previous version ([ADR 0006](docs/adr/0006-versioned-formats-migrate-on-open.md)). The snapshot
  fingerprint golden in `node/src/test/.../storage/CodecSuite.scala` guards this.
- Record evidence where the [definition of done](docs/process.md#definition-of-done) says, and track
  every gap you leave as an issue or roadmap item.
- Maintained native snapshot fixtures and ordinary snapshot test builders use the current native
  format (V7, `fixtures/snapshot-v7`; the V5 files are the previous-version inputs), and
  assertions read typed native state directly instead of completing flat-state adapters. Keep pinned conversion provenance and preserve original private archives outside the
  checkout. A future persisted-format change still needs its previous-version read test and
  migration; generic toy-format migration framework tests remain independent of native fixtures.
- Small, logical commits as CONTRIBUTING describes. A changelog entry is a fragment in `changes/`,
  never an edit of `CHANGELOG.md`. Do not push to `main` unless you were asked to.
- Before reporting a change ready, and again after every rebase or merge of its base, run
  `scripts/preflight --check` and report its result. It runs the repository-wide CI checks locally
  (the full-history secret scan, workflow lint, the Python guards and `check`); a PR is not ready while
  it fails.
- After a rebase, re-check every factual claim your change makes against the new base, not only that
  it builds.
- A change to an ADR, or to agent-facing documents that state facts about the code, gets an
  independent review against the code before it is reported ready.
- Report: branch and commits, what changed, gate results, deviations from the plan and why, and what
  the next stage needs to know.

## What does not belong in the repository

Signing keys or any secret; run data and logs; private working notes (keep them outside the checkout
or in an ignored directory). Distil what a later reader needs into the documents listed in
[docs/process.md](docs/process.md#one-place-per-question).
