# Contributing

Thanks for looking. This is research software (see [SECURITY.md](SECURITY.md)); the bar for changes
is that they are checked against the reference node or real chain data wherever the behaviour is
consensus-relevant. By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).
Work items are tracked in [docs/roadmap.md](docs/roadmap.md) by stable ID (A1, B3, ...); reference
the ID in issues and pull requests. How an item moves from idea to done, and the definition of done, are
in [docs/process.md](docs/process.md); design decisions are recorded as [ADRs](docs/adr/README.md).
Automated agent sessions also follow [AGENTS.md](AGENTS.md).

## Build: Docker only

No host JDK or sbt is needed. `scripts/sbtw` runs a sha256-pinned sbt launcher and needs a JDK, so
run it in the JDK image:

```sh
docker build -f docker/jdk.Dockerfile -t scarab-jdk:local .
scarab_git_metadata="$(git rev-parse --path-format=absolute --git-common-dir)"
scarab_cache="$(python3 -I -c 'import sys; sys.path.insert(0, "scripts")
from devnet.util import cache_dir; print(cache_dir())')"
docker run --rm -v "$PWD":/work -v "$scarab_cache":/work/.cache \
  -v "$scarab_git_metadata:$scarab_git_metadata:ro" \
  -w /work --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  scarab-jdk:local bash scripts/sbtw check
```

`check` (an alias in `build.sbt`, built from its `modules` list; CI runs the same alias) runs `scalafmtCheckAll`, compiles all test sources, runs the
tests of all modules (`crypto`'s `BlstSuite` among them: the pinned jblst library, start-up checks, the export list of the JNI glue), the fuzz suite among them, and ends with `scalafixAll --check`; the compiler runs with `-Werror` and the unused-code warnings (imports, privates, locals, explicit parameters, pattern variables), and WartRemover refuses the disallowed patterns in the main sources of every module (`build.sbt`, issue 650); a module may only depend on the modules before it in the graph of [docs/architecture.md](docs/architecture/modules.md#modules), and sbt refuses a cycle. Dependencies and the sbt launcher are
cached under `.cache/` (gitignored). `bash scripts/sbtw node/runtimeClasspathFile` writes
`node/target/runtime-classpath.txt`, which the scenarios use.

A linked worktree's `.git` file points outside the mounted checkout. The read-only common Git
metadata mount above keeps its `worktrees/` metadata and shared refs visible at their original
absolute paths; use it for every Docker sbt invocation, including a persistent server. The build
reads HEAD without needing a Git executable in the JDK image. Missing Git identity is refused,
not labeled `unknown`; an immutable export instead supplies the verified identity described below.

The classpath file names jars as `/work/.cache/...`, so sbt and the scenarios must mount the same
dependency cache there. The command above asks `scripts/devnet.py`'s own choice (`cache_dir` in
`scripts/devnet/util.py`, run from the checkout's root): `SCARAB_CACHE_DIR` if set (made absolute),
else the checkout's own `.cache` if it holds anything, else (in a linked git worktree, whose `.cache`
is only an empty mount point) the populated `.cache` of the main checkout. Never copy jars into a
worktree.

### Reproducible runtime artifacts

`node/assembly` packages a standalone node fat JAR runnable with `java -jar`.
The first-class sbt task `reproducibleCheck`, invoked through the host wrapper, builds an immutable
source export twice in isolated output trees inside one JDK image and compares complete bytes.
See [the artifact contract and verification commands](docs/reproducible-builds.md).

### Fast iteration: a persistent sbt server (optional)

Starting sbt costs 40 to 60 seconds of JVM start and compile. For many small edits keep one
container alive and talk to its sbt server:

1. Start a long-lived container from `scarab-jdk:local` with the checkout, cache, read-only Git
   metadata and `HOME` mounts/settings above (for example `docker run -d --name scarab-sbt ... tail -f /dev/null`).
2. Run sbt once inside it with the server enabled (a copy of `scripts/sbtw` without
   `-Dsbt.server.autostart=false`, kept outside the repository).
3. Send commands with `docker exec scarab-sbt <that launcher> --client "ledger/testOnly xyz.kd5ujc.scarab.ledger.*"`.
4. Give the server a socket of its own: start the container with `-e SBT_GLOBAL_BASE=/tmp/sbt-global`
   (`scripts/sbtw` passes it as `sbt.global.base`). Containers that share the dependency cache share one
   socket path otherwise, and a client reaches whichever server started first, possibly another session's.
   Remove your container when you are done.

Two traps: a running JVM loads classes lazily from `target/`, so never recompile under a live
devnet run (`devnet.py` copies the classes it runs from for this reason), and do not `pkill -f`
with a pattern that matches your own shell command line.

### Build tooling

- **Versions live in `project/Dependencies.scala`**: `V` (versions), `Libraries` (every module id)
  and an empty `CompilerPlugin` (better-monadic-for and kind-projector do not apply on Scala 3).
  `build.sbt` only picks from `Libraries`; change a version there, then `reload; update`. The Scala
  version and the compiler options stay in `build.sbt`. Plugins are pinned in `project/plugins.sbt`.
- **Binary formats: scodec for layouts that are read once, our codec for CBOR, hand-rolled code on
  the per-block paths.** The fixed-layout files of a Haskell ImmutableDB (primary and secondary
  index) are `scodec.Codec`s in the companions of their types (`org.scodec::scodec-core`, version in
  `Dependencies.V`, compatible with the `scodec-bits` that fs2 brings; shared pieces and the typed
  `Binary.Failure` in `storage/Binary.scala`). A failed field is a typed `Err` that the parser maps
  to the closed `ChunkFault`, never text. CBOR keeps its own codec: it preserves the original bytes
  of what it decodes, is stack-safe at 10k+ nesting, and the CDDL generator targets it. The file
  store's index entry, the volatile log record, the UTxO key and the mux SDU header stay hand-rolled
  because they run per block, per lookup or per SDU and scodec was slower there. The harness is
  `FormatBenchSuite` (`SCARAB_BENCH=1 SCARAB_K1_DB=<haskell db> storage/testOnly *FormatBenchSuite`;
  the scodec form of each is kept beside it, checked byte for byte): median ns per record,
  hand-rolled / scodec, Linux x86-64, JDK 21, warmed, three interleaved rounds: file store entry
  decode 36 / 560, encode 37 / 415; volatile record encode 150 / 1980 (300 B), 14,900 / 22,600
  (64 KiB), decode 297 / 1170 (300 B), 20,500 / 18,000 (64 KiB); mux header decode 16 / 160;
  UTxO key encode 13 / 310, decode 10 / 89; Haskell secondary entry 24 / 380 and primary offset
  18 / 61 (read once per chunk at import: 4.76 M entries of the Preview database in 1.8 s against
  0.11 s, small next to hashing the 15 GB). Re-measure before moving a format.
- **scalafix** (`.scalafix.conf`, OrganizeImports; semanticdb is on): `scalafixAll --check` reports
  what would change, `scalafixAll` applies it; run `scalafmtAll` afterwards. `check` ends with it, so CI runs it too.
- **Coverage** is off by default: `coverage; test; coverageReport` (reports under
  `target/scala-3.9.0/scoverage-report`), then `coverageOff`.
- **`dependencyUpdates`** lists newer versions of the dependencies and plugins (it only reads; the
  Scalus artifacts move together). **`dependencyTree`** (also `node/dependencyTree`) prints the
  resolved graph, to see where a transitive version comes from.
- **sbt-buildinfo** generates `xyz.kd5ujc.scarab.node.BuildInfo` (version, Scala and sbt versions,
  Git commit, build mode, release channel and selected tag). `project/GitHead.scala` reads visible
  Git metadata without a Git executable; the version configuration refuses a missing identity.
  Workspace versions are explicitly unreleased. The [immutable export wrapper](docs/reproducible-builds.md#version-identity)
  verifies and freezes release identity before building without Git history. The `started` log line
  carries the compiled version and commit as `build`.
- **sbt-tpolecat** is on the classpath but disabled per project: its option set would replace
  the one in `build.sbt`. `show node/scalacOptions` before and after a build change shows whether
  the effective options moved (`-Xsemanticdb` and its target are added for scalafix).
- **Orientation**: [docs/codemap.md](docs/codemap.md) is generated from `docs/data/codemap.json` by
  `python3 scripts/render_codemap.py`; a package, entry point or suite that moves needs the entry updated.
  `python3 scripts/status.py [--area A] [--priority P1]` prints a one-line digest of the open issues
  (needs `gh`; `--from file.json` works offline); its output is never committed.
  [docs/questions.md](docs/questions.md), [docs/glossary.md](docs/glossary.md) and
  [docs/playbooks/](docs/playbooks/README.md) point to where things are and how recurring tasks
  run; they set no rules of their own.

## Style rules

- **Tagless final at effect boundaries.** Capabilities are algebras over `F[_]`; interpreters are
  chosen in `Main`. `IO` appears only in `Main` and tools. No hidden global state:
  the memoising caches are instances created in a `Resource` (`VerificationCache`) and handed to
  the pure rules through a read interface (`Verdicts`); tools live under `xyz.kd5ujc.scarab.node.tools`.
- **Closed, domain/service-specific error ADTs.** Pure failures use `Either[DomainError, A]`;
  effect boundaries use cats-mtl `Raise[F, E]` and lexically scoped `Handle.allow` (`PeerError`,
  `ChainError`, `ValidationError`, `StorageError`, `ForgeError`, `ConfigError`). Preserve typed causes
  across layers and render them only for logs, CLI output or wire presentation. This applies to test
  helpers too: no `Either[String, …]`, string-based control flow or generic `Failure(message: String)`
  wrapper standing in for domain cases. Never use `raiseError`/`throw` for a domain error; IO
  exceptions are mapped to `StorageError`/`PeerError` at the boundary only. No `.get`/`.toOption.get`
  on values that are not provably present. The remaining sweep and CI guard are tracked by M11-E
  in [the roadmap](docs/roadmap.md). The original #82 is closed; the semantic follow-up is
  [#189](https://github.com/KD5UJC-XYZ/scarab/issues/189).
- **Functional code by default.** Imperative, side-effecting implementations are kept only for the
  exceptions that [AGENTS.md](AGENTS.md#how-code-is-written-here) lists (a measured
  performance-critical path, recorded in [performance.md](docs/performance.md), and a few narrow
  others), each with its written reason; that list is the one authoritative statement of them.
- **Pure rules.** Ledger, consensus, mempool and chain-selection rules are pure functions over
  values. Effects wrap them, not the other way around.
- **Laws and properties.** New algebras and stateful structures get cats-laws or ScalaCheck
  properties (round trips, model equivalence, rollback), not only examples.
- **Original-bytes rule.** Anything that is hashed or signed (block bodies, transaction bodies,
  witness sets, scripts, datums, auxiliary data) is hashed over the bytes as received, never over a
  re-encoding. Keep the original bytes next to the decoded value.
- Exact arithmetic (`Rational`) in ledger calculations; no floating point.
- Format with scalafmt (`scalafmtAll`); SPDX header (`// SPDX-License-Identifier: Apache-2.0`) on new source files.
- Keep comments outside parameter and argument lists. Document parameter contracts in the
  declaration's Scaladoc, and explain a call before the expression. Comments in a definition's
  body are useful when they explain an invariant, intent, reference behaviour or a tradeoff.
  Evaluate that purpose before rewriting or removing a comment; prefer maintained symbols,
  ADRs and pinned upstream references to private notes or implementation-stage labels.
- No secrets in the repository: never commit `.skey`, `.key`, KES/VRF/cold keys or node sockets.
  Fixtures must be public chain data or throwaway keys, with provenance and licence recorded in
  the fixture directory's README and `NOTICE`.

Maintained native snapshot payload fixtures and ordinary test builders use the current native
format (`FileKind.SnapshotFile.current`, V7; `fixtures/snapshot-v7`). Assert typed native state directly; a flat-state
completion adapter is not the fixture's expected state. Pin conversion provenance and preserve
original private captures outside the checkout. A deliberate future format bump still requires
its migration and a previous-version read test under [ADR 0006](docs/adr/0006-versioned-formats-migrate-on-open.md).
Generic toy-format migration framework tests keep testing version transitions independently of
the maintained native fixture corpus. Fixture conversion alone does not retire production readers
or authorise an operational writer upgrade.

## Conformance comparisons

Original upstream cases must execute without Scarab-specific expected failures, masked fields or
rounding tolerances. Historical non-conformance and ledger reachability are reasons to fix the
implementation or match the upstream harness profile, not to omit a case. A specific JVM limitation
requires a minimal reproducer, platform evidence, exact affected cases and a tracked removal
condition. Missing implementations and missing fixtures make the relevant gate incomplete; they
must not contribute a pass. Report environmental skips and projection coverage separately from
conformance results.

## Gates and ceremony by level of change

Thoroughness lives in two places: the gates a change passes before it lands, and the nightly CI,
which runs every expensive scenario. A change runs only what can catch a fault in what it touched;
everything else is caught within a day by CI. Land in minutes, not hours.

### Levels

| Level | Examples | Before landing | Paperwork |
|---|---|---|---|
| **0. Docs, notes, comments** | wording, a typo, a new section of a design doc | relative links resolve | none |
| **1. Small code change** | refactor, test-only change, tooling, a local fix with no change in behaviour visible outside the module | `scripts/sbtw check` (formatting, `-Werror`, every module's tests, the fuzz suite and scalafix) | commit message; a changelog fragment only if a user could notice |
| **2. Behaviour change in one area** | a bug fix, a new query, a new flag or event, a performance change | `check` plus **one** scenario for the area (table below) | changelog fragment; a test that fails without the change; `docs/running.md` if a flag, event or metric changed; a `docs/findings.md` row if it was found on real data |
| **3. Consensus-critical or cross-cutting** | ledger rules, chain selection, a persisted format, the rules switch, anything forging | `check` plus the area scenarios, run in parallel | as level 2, plus the compatibility matrix row and the design document "as built" |
| **4. Stage boundary** | the jump gates of a design (all Preview epochs, preprod from genesis), a release | the long runs the design names, once per stage, not per commit | an evidence-log entry under `docs/compatibility/` with commit, date and command |

A persisted format change is level 3 and also needs a version bump, a migration and a test that
reads the previous version ([ADR 0006](docs/adr/0006-versioned-formats-migrate-on-open.md)).

### The one scenario per area

| Area touched | Scenario (`python3 scripts/devnet.py --name <yours> ...`) |
|---|---|
| Ledger rules, rewards, governance, Plutus, nonces, headers | `e2e` |
| Network: mini-protocols, mux, peers, chain selection, fetch | `relay --seconds 300` |
| Storage: stores, snapshots, the UTxO set | `restart` |
| Forging, mempool | `pool` |
| KES key, certificates, SIGHUP reload, `scripts/pool` | `kes-rotate` |
| Node-to-client: queries, submission, rejections | `ntc` |
| A decoder | none beyond `check`; a new entry point gets a fuzz target; a decoder change also runs `scripts/sbtw "fuzz 100000 1 4"`, which `fuzz-gate.yml` now runs in CI on a pull request that touches a decoder, the fuzz project, the CDDL schemas or the fixtures (running it locally first saves a round trip) |
| `scripts/devnet/**` | `python3 -m unittest discover -s scripts/tests` and the scenario the change touches |

Two areas, two scenarios: run them at the same time (distinct `--name`s), at most two devnets at
once on a shared machine. Repeats (three relays, two ntc runs) and `soak` are flakiness checks and
belong to nightly CI, not to a change.

### Nightly CI is the safety net

`.github/workflows/e2e.yml` runs soak, e2e, relay, pool, kes-rotate, ntc and restart every night, each scenario in its own job (a matrix), and on demand and on
version tags; `fuzz.yml` and `cuddle-crosscheck.yml` run the longer fuzzing (rotating daily seeds) and the CDDL cross-check; `fuzz-gate.yml` runs the fixed-seed `fuzz 100000 1 4` gate on pull requests that change decoder or fuzz inputs and passes without fuzzing on any other. `.github/workflows/ci.yml` runs `check`, the script tests, actionlint, a gitleaks
scan of the checked-out commit's full history, including merge parents (unrelated fetched branches
are scanned in their own runs; `.gitleaks.toml`; a new false positive is allowlisted there by path or
commit, scoped to its rule, with a reason) and, on pull requests, a dependency review. A red
nightly is fixed first, before new work lands: it is the cheapest point at which to find what the
per-change gates did not cover. Run the E2E scenarios on a branch with `python3 scripts/dispatch_e2e.py --ref
<branch>`, which waits until no E2E run is waiting before it dispatches: the workflow's concurrency group keeps one
run going and one waiting, and a plain `gh workflow run` replaces, and so cancels, the waiting one.
`.github/workflows/flaky-tests.yml` reads the last day's CI runs every night: a test that failed and then passed on
the same commit gets an issue of its own, or a comment on its existing one (`scripts/flaky_tests.py`).

The scenarios need the reference image (see [docs/reference-image.md](docs/reference-image.md))
and are described in [docs/running.md](docs/running.md). `prepare_reference_image.py --download`
fetches and verifies the official release itself. Every scenario exits non-zero on any mismatch or
error.

### Landing in parallel

Each piece of work lives in its own git worktree (`git worktree add`) with its own build container,
devnet name and sbt server socket. Work branches from and targets `main` for now; run the
change-level gates and rebase onto the intended target before reporting. The owner will activate
`feat/*` -> `dev` -> `main` promotion separately. After that cutover, only the same repository's
`dev` branch may be promoted into `main`; all other PR targets accept any source branch.
The [promotion workflow](docs/promotion-cutover.md) replaces main's merge button at cutover,
because a commit-bound status check alone cannot distinguish PRs sharing the same commit.
`release/alpha`, `release/beta`, `release/preprod` and `release/prod` also require reviewed PRs
after cutover. Their planned tag suffixes are `-alpha.N`, `-beta.N`, `-rc.N` and no suffix,
respectively; environment creation and protection setup remain deferred.

The owner-directed [release and hardening design](docs/design/releases.md) supersedes the research
phase landing and `main` hotfix-line plans at cutover. #161 tracks enforcement: required checks, branch/tag
protection and independent review for consensus, cryptography, persistence and key handling.
These documents state the intended policy, not proof settings are enabled. Repository settings
remain the maintainer's responsibility. The owner authorized aligning `dev` to `main` on
2026-10-21; preserve its prior history before a lease-protected alignment. Any later destructive
alignment needs its own owner authorization.

The adopted preprod block remains the final v0.2 gate (not available before October 23 per the
2026-10-21 owner update). Release preparation proceeds without asserting that gate passed.
Dedicated public genesis-sync and observation campaigns remain v0.3 work; per-change and nightly
gates remain required. Rebase validation follows the change-level gates; do not reuse receipts
from a different source revision as if they tested the rebased code.

### Changelog fragments

Each change that needs a changelog entry adds a file `changes/<issue-or-branch>.md` with its entry
(a `### Added|Changed|Fixed|Removed (...)` heading and the bullets). Nobody edits `CHANGELOG.md`
between releases, so branches do not conflict there; a release folds the fragments into a version
section and deletes only its selected inputs in the same reviewed PR. Prerelease notes retain pending
fragments. See [changes/README.md](changes/README.md) and the
[consolidation contract](docs/design/releases.md#changelog-fragments-and-consolidation).

## Owner review feedback

The owner reviews landed work by leaving inline comments on commits and by filing issues labelled
`owner-review`. Whoever picks them up triages each one into exactly one of these, and always replies on
the comment or issue with the commit that fixes it (or the answer):

| The feedback is | What to do |
|---|---|
| A specific defect or preference in one place | Fix it in that area, in a small commit that names the comment. |
| A general rule ("never X", "always Y") | Write the rule into [AGENTS.md](AGENTS.md) (and here if humans need it), then sweep the tree for other occurrences and fix them in separate commits per area. A rule with no sweep is only half applied. |
| A change of design | Open an issue (and an ADR or a design-document stage if it reverses a decision); do not fold it into a fix. |
| A question | Answer it in the thread. If the answer shows a document is wrong or missing, fix the document too. |

Close the `owner-review` issue only when every point in it has a reply with its commit or answer.

## How evidence is recorded

A claim of compatibility needs evidence a reader can check:

- A bug found against real data gets a regression test or fixture (the failing transaction recorded
  with `--plutus-cases`, or captured blocks via `CaptureMain`), a commit message that names the
  slot or epoch, and a row in [docs/findings.md](docs/findings.md).
- A scenario result is recorded with the **commit, date, command and image versions** in
  [docs/compatibility.md](docs/compatibility.md#evidence-log) (the `evidence-log-N.md` pages under
  `docs/compatibility/`); numbers without a commit are not recorded.
- A change in what is verified updates the matching component of the compatibility register
  (`docs/data/compatibility.json` and its page `docs/compatibility/<id>.md`), including the "explicitly
  unchecked" section, then regenerates the matrix with `python3 scripts/render_compatibility.py`
  (`docs/compatibility.md` is generated; CI runs the script with `--check`). A roadmap item's status comes from
  its issues: closing the issue is what marks it done, and the nightly triage workflow refreshes the
  issue-state snapshot (`docs/data/issue-states.json`) and renders `docs/roadmap.md` again in a pull request of
  its own, which the next run merges once every check has passed if it changes only those two files
  (`scripts/roadmap_sync.py`; the only pull request merged without a person). A change that adds an item, or an issue to one, runs `python3 scripts/render_roadmap.py
  --refresh-states` (adds the issues the snapshot lacks); IDs are never renumbered.
- Performance numbers go in [docs/performance.md](docs/performance.md) with the setup: an entry in
  `docs/data/performance-log.json`, then `python3 scripts/render_performance.py --write`.

## Commits and pull requests

- Small, logical commits. The subject says what changed in the imperative or as a statement, and
  names the evidence when the change was driven by data (for example "... (Preview slot 65493476)").
- Describe the why in the body when it is not obvious; reference roadmap IDs.
- Do not rewrite shared history. Do not commit generated run data (`/tmp/scarab`
  is outside the checkout on purpose; set `SCARAB_DATA_DIR` to move it; the old name `CSL_DATA_DIR` still works, and an existing `/tmp/cardano-scala-lab` is used while `/tmp/scarab` is absent).
- Fill in the pull request template; it asks for the level of the change and the gates you ran. At level 2 and
  above, the body also has a "Deviations from the reference" section (`None`, or a table of the behaviour, the
  reference file and line at its pin, what Scarab does and the link to the owner's approval) and a "Gaps" section
  (`None`, or bullets that each start with the issue tracking the gap); a section that opens with `None` may go on
  in prose. `scripts/pr_policy.py` checks the level, its floor from the changed files (a file other than
  documentation makes it at least 1, the persisted-format register at least 3), and both sections on every pull
  request event, except for a bot's pull request and a promotion of `dev`
  (decided 2026-10-29, [#400](https://github.com/KD5UJC-XYZ/scarab/issues/400#issuecomment-6506671003)).

For a persisted-format change, follow the [storage migration procedure](docs/storage-migrations.md)
alongside the applicable gates above.
