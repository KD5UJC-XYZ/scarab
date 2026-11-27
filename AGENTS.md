# Instructions for agent sessions

Scarab is a Cardano-compatible node in Scala 3.9, written as tagless-final cats-effect code
with cats-mtl, and checked against the Haskell `cardano-node` as the reference. These instructions
apply to every automated session working in this repository; human contributors follow the same
rules through [CONTRIBUTING.md](CONTRIBUTING.md).

For orientation only: [docs/codemap.md](docs/codemap.md) (modules, entry points, suites),
[docs/questions.md](docs/questions.md) (where to start reading), [docs/glossary.md](docs/glossary.md)
(terms) and [docs/playbooks/](docs/playbooks/README.md) (recurring tasks). They set no rules; where
they differ from this file, CONTRIBUTING or the [design rules](docs/design-rules.md), those win.

## Before you start

- **STOP before any storage format change ([FMT-1](docs/design-rules.md#fmt-1)): ask the owner, explicitly, in the
  chat session doing the work, and record when you asked.** That covers a new version, an in-place edit of V9 or
  V10, a migration step and a regenerated snapshot fixture. A subagent stops and reports to its lead. Approval is
  only the owner's answer there ([what approval is](docs/design-rules.md#approval)); no comment, label, document or
  account is. An outside contributor's agent puts the question in its pull request instead, as that section says.

1. Run `python3 scripts/status.py --issue <N>` first (its docstring says what it prints). Its suggested level
   is a starting point; [CONTRIBUTING.md](CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) decides. If
   the issue is `status:in-progress` and you were not asked to take it over, someone else is on it.
2. Read the issue, then any [design document](docs/design/README.md) and [ADRs](docs/adr/README.md) it links;
   [docs/process.md](docs/process.md) says how work is tracked, and [docs/codemap.md](docs/codemap.md) where
   the code and its suites are.

Then:

- Work in your own git worktree from `main` for now, and rebase onto the PR target before reporting.
  `main` remains the working target until the owner activates `feat/*` -> `dev` -> `main` promotion
  ([CONTRIBUTING.md](CONTRIBUTING.md#landing-in-parallel)); after cutover only same-repository `dev` may
  target `main`, through the [reviewed promotion workflow](docs/promotion-cutover.md), while other
  targets accept any source; `release/alpha`, `release/beta`, `release/preprod` and `release/prod` also
  use protected PRs. Do not treat a shared commit's green check as source-branch authority; do not claim
  protections are enabled or change repository settings.
- Feedback from the owner arrives as inline commit comments and issues labelled `owner-review`; triage
  it as [CONTRIBUTING.md](CONTRIBUTING.md#owner-review-feedback) says, and a general rule goes into
  [docs/design-rules.md](docs/design-rules.md) together with a sweep of the tree.

## Build and run: Docker, or the Nix shell

- Before setting up an environment, read [CONTRIBUTING.md's build instructions](CONTRIBUTING.md#build-docker-or-the-nix-shell)
  and the [reference image instructions](docs/reference-image.md). Reuse [scripts/sbtw](scripts/sbtw),
  [docker/jdk.Dockerfile](docker/jdk.Dockerfile) and [scripts/prepare_reference_image.py](scripts/prepare_reference_image.py).
  Run `bash scripts/preflight --doctor` and read its report to identify missing prerequisites; its exit status
  is always zero, even when it reports warnings. Prepare only what is missing, reuse the documented dependency
  cache, and verify with the documented build commands. Check reference-image requirements separately: the
  doctor reports Docker, the JDK and the prepared dependency repository, not the Haskell reference images.
  Do not create replacement setup scripts or wrappers without identifying a concrete gap in the existing tools.
  Keep session-specific proxy and certificate adjustments outside the repository, with TLS verification enabled.
- Never run `java` or `sbt` on the host outside the Nix shell. Build and test in the JDK image
  (`scarab-jdk:local`, built from `docker/jdk.Dockerfile`) or in the Nix shell ([docs/nix.md](docs/nix.md)), as
  [CONTRIBUTING.md](CONTRIBUTING.md#build-docker-or-the-nix-shell) shows; `scripts/preflight --check` and the
  scenarios need Docker either way.
- For many small edits use a persistent sbt server container **of your own**, with a private sbt global
  directory (`-e SBT_GLOBAL_BASE=/tmp/sbt-global`); `scripts/sbtw` keeps the server's sockets in the
  container's own temporary directory
  ([CONTRIBUTING.md](CONTRIBUTING.md#fast-iteration-a-persistent-sbt-server-optional) says why).
- Use your own devnet name: `python3 scripts/devnet.py --name <yours> <scenario>`, then
  `python3 scripts/devnet.py --name <yours> down`. Run data goes to `${SCARAB_DATA_DIR:-/tmp/scarab}`,
  never into the checkout or a home directory. `/tmp` is commonly emptied at boot and aged out after 30
  days, so data that must outlive a run goes in a dated `.workspace/data/` directory, and drafts awaiting
  the owner go in `.workspace/notes/`, never only in `/tmp` or a session scratchpad. A document records
  what a reader needs and says that raw run artifacts are not published; it never names a directory on
  your machine as where evidence lives (`scripts/check_local_paths.py` refuses that; its docstring has
  the detail).
- Never kill processes by pattern (`pkill -f`): it can match your own shell. Kill by PID, or remove
  your own container.
- Remove the containers you started when you finish. Label every container that outlives one command
  with `container_labels` from `scripts/cleanup.py` (kind and owner), so its owner is a query.

## How code is written here

Three requirements of the owner underlie the rules ([decisions register](docs/decisions.md#code)):
- write functional, type-safe code that leans on the compiler to enforce correctness as far as it can;
- keep using `Ask`, `Stateful`, Reader and the other capabilities of cats-mtl, and lean on the compiler with
  syntax classes, implicit derivation, givens and `using` clauses: elegant Scala, with type parameters wherever
  they fit;
- make code correct by construction, not correct by the order of its operations.

The rules are in [docs/design-rules.md](docs/design-rules.md), one home each: its ID, scope (main, tools,
tests), exceptions and how a kept site is marked, enforcement and reason. Read the groups your change touches.

- [Effects and capabilities](docs/design-rules.md#eff): [EFF-1](docs/design-rules.md#eff-1) tagless final with
  cats-mtl capabilities; [EFF-2](docs/design-rules.md#eff-2) `IO` only in `Main`, the node's shipped commands,
  `tools` and tests; [EFF-3](docs/design-rules.md#eff-3) pure rules; [EFF-4](docs/design-rules.md#eff-4) no
  hidden global state; [EFF-5](docs/design-rules.md#eff-5) no `unsafeRun*` but a `Dispatcher` bridge;
  [EFF-6](docs/design-rules.md#eff-6) time only from `Clock[F]`.
- [Errors](docs/design-rules.md#err): [ERR-1](docs/design-rules.md#err-1) closed domain error types;
  [ERR-2](docs/design-rules.md#err-2) typed causes survive; [ERR-3](docs/design-rules.md#err-3) no
  `Either[String, …]` or free-text wrapper; [ERR-4](docs/design-rules.md#err-4) no `throw` for domain errors;
  [ERR-5](docs/design-rules.md#err-5) no partial accessors.
- [Strings are values](docs/design-rules.md#str): [STR-1](docs/design-rules.md#str-1) a String is output or
  parser input; [STR-2](docs/design-rules.md#str-2) parsers and their tables;
  [STR-3](docs/design-rules.md#str-3) no decision on a String's contents.
- [Functional style](docs/design-rules.md#fun): [FUN-1](docs/design-rules.md#fun-1) functional code by
  default; [FUN-2](docs/design-rules.md#fun-2) the narrow imperative exceptions;
  [FUN-3](docs/design-rules.md#fun-3) a kept site is marked and contained.
- [Fibers](docs/design-rules.md#fib): [FIB-1](docs/design-rules.md#fib-1) a fiber never raises through a
  capability it did not create; [FIB-2](docs/design-rules.md#fib-2) a fiber that outlives the call ends with a
  value; [FIB-3](docs/design-rules.md#fib-3) a fiber joined at once may use its owner's capabilities.
- [Tests](docs/design-rules.md#tst): [TST-1](docs/design-rules.md#tst-1) what binds tests;
  [TST-2](docs/design-rules.md#tst-2) no sleeps; [TST-3](docs/design-rules.md#tst-3) `FailsOnUncaught`;
  [TST-4](docs/design-rules.md#tst-4) assertions on typed errors; [TST-5](docs/design-rules.md#tst-5)
  concurrency tests evaluate their waits first; [TST-6](docs/design-rules.md#tst-6) laws and properties.
- [Conformance](docs/design-rules.md#cnf): [CNF-1](docs/design-rules.md#cnf-1) upstream cases run whole;
  [CNF-2](docs/design-rules.md#cnf-2) incomplete is not a pass; [CNF-3](docs/design-rules.md#cnf-3) diagnostics
  and refusals as the reference presents them.
- [Hand-run programs](docs/design-rules.md#tool): [TOOL-1](docs/design-rules.md#tool-1) they live in `tools`;
  [TOOL-2](docs/design-rules.md#tool-2) `tools` follows the rules of tests.
- [Types: correct by construction](docs/design-rules.md#typ): [TYP-1](docs/design-rules.md#typ-1) illegal
  states unrepresentable; [TYP-2](docs/design-rules.md#typ-2) parse, don't validate;
  [TYP-3](docs/design-rules.md#typ-3) opaque types; [TYP-4](docs/design-rules.md#typ-4) ADTs, not flags;
  [TYP-5](docs/design-rules.md#typ-5) non-empty collections; [TYP-6](docs/design-rules.md#typ-6) exhaustive
  matches; [TYP-7](docs/design-rules.md#typ-7) type-level sizes; [TYP-8](docs/design-rules.md#typ-8) lean on
  the compiler; [TYP-9](docs/design-rules.md#typ-9) Free only where a program must be inspected.
- [Stack safety](docs/design-rules.md#stk): [STK-1](docs/design-rules.md#stk-1) recursion over outside data is
  stack safe by construction; [STK-2](docs/design-rules.md#stk-2) recursion schemes over outside data;
  [STK-3](docs/design-rules.md#stk-3) tested on a small stack.
- [Arithmetic](docs/design-rules.md#ari): [ARI-1](docs/design-rules.md#ari-1) exact arithmetic;
  [ARI-2](docs/design-rules.md#ari-2) overflow, underflow and narrowing by ADR 0001's procedure.
- [Hashed bytes](docs/design-rules.md#byt): [BYT-1](docs/design-rules.md#byt-1) hash and sign exactly the
  bytes the reference hashes and signs.
- [The reference](docs/design-rules.md#ref): [REF-1](docs/design-rules.md#ref-1) to
  [REF-4](docs/design-rules.md#ref-4) interoperability, reference defects, which reference governs, and the
  four classes (Mirror, Improvement, Deviation, Gap); [REF-5](docs/design-rules.md#ref-5) **agents may not
  deviate from the reference on their own accord**; [REF-6](docs/design-rules.md#ref-6) mirror its structure by
  default, cite it at its pin, and differ internally only with differential evidence or as an Optimisation the
  owner approved (internal, for speed, memory or disk use, nothing observable changing);
  [REF-7](docs/design-rules.md#ref-7) how a pull request records each class; [REF-8](docs/design-rules.md#ref-8)
  settled Deviations.
- [Persisted formats and fixtures](docs/design-rules.md#fmt): [FMT-1](docs/design-rules.md#fmt-1) persisted formats
  are frozen until Scarab leaves pre-release: nothing changes without the owner's approval in the chat session doing
  the work, and an approved change is a pull request of its own; [FMT-2](docs/design-rules.md#fmt-2) native snapshot
  fixtures use the current format; [FMT-3](docs/design-rules.md#fmt-3) no secrets, and fixtures with
  provenance and licence.
- [Cryptography](docs/design-rules.md#cry): [CRY-1](docs/design-rules.md#cry-1) variable-time crypto stays
  contained.
- [Comments, naming and source files](docs/design-rules.md#cmt): [CMT-1](docs/design-rules.md#cmt-1) like the
  surrounding code; [CMT-2](docs/design-rules.md#cmt-2) no comments in parameter lists;
  [CMT-3](docs/design-rules.md#cmt-3) comments that explain are kept; [CMT-4](docs/design-rules.md#cmt-4) SPDX
  header; [CMT-5](docs/design-rules.md#cmt-5) scalafmt and scalafix.
- [Documents](docs/design-rules.md#doc): [DOC-1](docs/design-rules.md#doc-1) an accepted ADR is amended, not
  rewritten; [DOC-2](docs/design-rules.md#doc-2) one home per policy or fact;
  [DOC-3](docs/design-rules.md#doc-3) no volatile facts in static documents; [DOC-4](docs/design-rules.md#doc-4)
  instructions, not quotations of the owner.

## Before you hand back

- Name the level of your change and run its gates, no more
  ([CONTRIBUTING.md](CONTRIBUTING.md#gates-and-ceremony-by-level-of-change)): for level 1 and above, `check`
  (formatting, `-Werror`, every module's tests, scalafix); for level 2 and above, also one scenario per area touched.
  A documentation-only change (level 0) needs relative-link checks and non-sbt preflight, and a design document
  or ADR also has its reference claims checked against the pinned source
  ([CONTRIBUTING.md](CONTRIBUTING.md#levels)). Soak, repeats and the long network replays are nightly
  CI's, not yours, unless your stage's design names them as its jump gate.
- A storage format change follows [FMT-1](docs/design-rules.md#fmt-1): the owner's approval asked for and given
  first, in the chat session doing the work, and a pull request of its own; `scripts/check_storage_format.py`
  fails on any change to the persisted-format surface.
- Record evidence where the [definition of done](docs/process.md#definition-of-done) says, and track
  every gap you leave as an issue or roadmap item; a P2 gap goes into its parent issue's checklist
  (`python3 scripts/file_issue.py --parent N`, [process](docs/process.md#lifecycle-of-an-item)).
- Snapshot fixtures and test builders use the current native format, and assertions read typed native
  state ([FMT-2](docs/design-rules.md#fmt-2)); regenerating a fixture is a storage format change (FMT-1).
- Small, logical commits as CONTRIBUTING describes. A changelog entry is a fragment in `changes/`,
  never an edit of `CHANGELOG.md`. Do not push to `main` unless you were asked to.
- Before reporting a change ready, and again after every rebase or merge of its base (with the one
  exception in [CONTRIBUTING.md](CONTRIBUTING.md#rebase-validation)),
  run `scripts/preflight` for documentation-only changes (level 0), or `scripts/preflight --check` for
  level 1 and above, and report its result. Plain preflight runs the non-sbt checks; `--check` also runs
  the Scala build and tests. Do not add `--check` for a documentation-only change. A mixed change follows
  the highest applicable level; a PR is not ready while its required checks fail.
- After a rebase, re-check every factual claim your change makes against the new base, not only that
  it builds.
- Get the [independent review](CONTRIBUTING.md#independent-review) before a pull request leaves draft.
- Run `python3 scripts/cleanup.py --owner <each devnet, container owner and run name you made> --apply`
  (its docstring says what it removes and what it keeps).
- Report: branch and commits, what changed, gate results, deviations from the plan and why, and what
  the next stage needs to know.

## What does not belong in the repository

Signing keys or any secret; run data and logs; private working notes (keep them outside the checkout
or in an ignored directory), among them the spec, plan, task list or validation log a workflow tool
writes, such as a `specs/` directory. Distil what a later reader needs into the documents listed in
[docs/process.md](docs/process.md#one-place-per-question), which says where each kind goes.
