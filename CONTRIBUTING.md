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
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  scarab-jdk:local bash scripts/sbtw check
```

`check` (an alias in `build.sbt`, built from its `modules` list; CI runs the same alias) runs `scalafmtCheckAll`, compiles all test sources, runs the
tests of all modules (`blstNative` and its `BlstSuite` among them: library selection, start-up checks, the export list of the JNI glue), the fuzz suite among them, and ends with `scalafixAll --check`; the compiler runs with `-Werror` and the unused-code warnings (imports, privates, locals, explicit parameters, pattern variables); a module may only depend on the modules before it in the graph of [docs/architecture.md](docs/architecture.md#modules), and sbt refuses a cycle. Dependencies and the sbt launcher are
cached under `.cache/` (gitignored). `bash scripts/sbtw node/runtimeClasspathFile` writes
`node/target/runtime-classpath.txt`, which the scenarios use.

The classpath file names jars as `/work/.cache/...`. `scripts/devnet.py` mounts the dependency cache
there itself: `SCARAB_CACHE_DIR` if set, else the checkout's own `.cache` if it holds anything, else
(in a linked git worktree, whose `.cache` is only an empty mount point) the `.cache` of the main
checkout, found as the parent of `git rev-parse --git-common-dir`. Mount the same directory at
`/work/.cache` when you run sbt in a worktree, and never copy jars into the worktree.

### Reproducible runtime artifacts

`node/assembly` packages a standalone node fat JAR runnable with `java -jar`.
The first-class sbt task `reproducibleCheck`, invoked through the host wrapper, builds an immutable
source export twice in isolated output trees inside one JDK image and compares complete bytes.
See [the artifact contract and verification commands](docs/reproducible-builds.md).

### Fast iteration: a persistent sbt server (optional)

Starting sbt costs 40 to 60 seconds of JVM start and compile. For many small edits keep one
container alive and talk to its sbt server:

1. Start a long-lived container from `scarab-jdk:local` with the checkout mounted at `/work`
   and the same `HOME` as above (for example `docker run -d --name scarab-sbt ... tail -f /dev/null`).
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
  `target/scala-3.3.8/scoverage-report`), then `coverageOff`.
- **`dependencyUpdates`** lists newer versions of the dependencies and plugins (it only reads; the
  Scalus artifacts move together). **`dependencyTree`** (also `node/dependencyTree`) prints the
  resolved graph, to see where a transitive version comes from.
- **sbt-buildinfo** generates `xyz.kd5ujc.scarab.node.BuildInfo` (version, Scala and sbt versions, git
  commit, read from `.git` by `project/GitHead.scala` so the build container needs no `git`; `unknown`
  outside a checkout, or in a worktree whose `gitdir` path the container cannot see). The `started`
  log line carries it as
  `build`.
- **sbt-tpolecat** is on the classpath but disabled per project: its option set would replace
  the one in `build.sbt`. `show node/scalacOptions` before and after a build change shows whether
  the effective options moved (`-Xsemanticdb` and its target are added for scalafix).

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
- **Functional code by default.** Imperative, side-effecting implementations are an exception only
  for performance-critical paths. Support each exception with profiling or benchmark evidence and
  a written reason; record measurements in [performance.md](docs/performance.md). Keep mutation
  local behind a pure interface or an effect interpreter. The exception changes implementation style
  only; typed errors, capability scope and original-byte guarantees still apply.
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
format (`FileKind.SnapshotFile.current`, V5). Assert typed native state directly; a flat-state
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
| **4. Stage boundary** | the jump gates of a design (all Preview epochs, preprod from genesis), a release | the long runs the design names, once per stage, not per commit | an evidence-log row in `docs/compatibility.md` with commit, date and command |

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
| A decoder | none beyond `check`; a new entry point gets a fuzz target; a decoder change also runs `scripts/sbtw "fuzz 100000 1 4"` |
| `scripts/devnet/**` | `python3 -m unittest discover -s scripts/tests` and the scenario the change touches |

Two areas, two scenarios: run them at the same time (distinct `--name`s), at most two devnets at
once on a shared machine. Repeats (three relays, two ntc runs) and `soak` are flakiness checks and
belong to nightly CI, not to a change.

### Nightly CI is the safety net

`.github/workflows/e2e.yml` runs soak, e2e, relay, pool, kes-rotate, ntc and restart every night, each scenario in its own job (a matrix), and on demand and on
version tags; `fuzz.yml` and `cuddle-crosscheck.yml` run the longer fuzzing and the CDDL cross-check. `.github/workflows/ci.yml` runs `check`, the script tests, actionlint, a gitleaks
scan of the checked-out commit's full history, including merge parents (unrelated fetched branches
are scanned in their own runs; `.gitleaks.toml`; a new false positive is allowlisted there by path or
commit, scoped to its rule, with a reason) and, on pull requests, a dependency review. A red
nightly is fixed first, before new work lands: it is the cheapest point at which to find what the
per-change gates did not cover.

The scenarios need the reference image (see [docs/reference-image.md](docs/reference-image.md))
and are described in [docs/running.md](docs/running.md). `prepare_reference_image.py --download`
fetches and verifies the official release itself. Every scenario exits non-zero on any mismatch or
error.

### Landing in parallel

Each piece of work lives in its own git worktree (`git worktree add`) with its own build container, devnet
name and sbt server socket, and lands on its own: when its gates pass, rebase onto `main`. If the rebase is clean and touches no file the other
side changed in code, rerun `check` only and push; otherwise rerun the level's gates. Nobody waits
for another branch's gates, and there is no shared integration branch that serialises landings.

This direct-push flow is for the research phase. When the v0.2 milestone closes (the remaining
reference-grade work and an adopted preprod block; see "Order of work" in
[docs/roadmap.md](docs/roadmap.md)), the maintainer applies the merge-protection decision in
[#161](https://github.com/KD5UJC-XYZ/scarab/issues/161): branch protection, required CI checks,
pull requests and independent review for consensus, cryptography, persistence and key handling.
This section is then rewritten for pull requests. Dedicated public genesis-sync and observation
campaigns belong to v0.3; the per-change and nightly gates above remain required.

### Changelog fragments

Each change that needs a changelog entry adds a file `changes/<issue-or-branch>.md` with its entry
(a `### Added|Changed|Fixed|Removed (...)` heading and the bullets). Nobody edits `CHANGELOG.md`
between releases, so branches do not conflict there; a release folds the fragments into a version
section and deletes them ([changes/README.md](changes/README.md)).

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
  [docs/compatibility.md](docs/compatibility.md) (evidence log); numbers without a commit are not
  recorded.
- A change in what is verified updates the matching row of the compatibility matrix, including the
  "explicitly unchecked" cell, and the roadmap item it closes (mark it done, never renumber).
- Performance numbers go in [docs/performance.md](docs/performance.md) with the setup.

## Commits and pull requests

- Small, logical commits. The subject says what changed in the imperative or as a statement, and
  names the evidence when the change was driven by data (for example "... (Preview slot 65493476)").
- Describe the why in the body when it is not obvious; reference roadmap IDs.
- Do not rewrite shared history. Do not commit generated run data (`/tmp/scarab`
  is outside the checkout on purpose; set `SCARAB_DATA_DIR` to move it; the old name `CSL_DATA_DIR` still works, and an existing `/tmp/cardano-scala-lab` is used while `/tmp/scarab` is absent).
- Fill in the pull request template; it asks for the level of the change and the gates you ran.

For a persisted-format change, follow the [storage migration procedure](docs/storage-migrations.md)
alongside the applicable gates above.
