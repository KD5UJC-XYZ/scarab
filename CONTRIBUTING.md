# Contributing

Thanks for looking. This is research software (see [SECURITY.md](SECURITY.md)); the bar for changes
is that they are checked against the reference node or real chain data wherever the behaviour is
consensus-relevant. By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).
Work items are tracked in [docs/roadmap.md](docs/roadmap.md) by stable ID (A1, B3, ...); reference
the ID in issues and pull requests.

## Build: Docker only

No host JDK or sbt is needed. `scripts/sbtw` runs a sha256-pinned sbt launcher and needs a JDK, so
run it in the JDK image:

```sh
docker build -f docker/jdk.Dockerfile -t cardano-scala-jdk:local .
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  cardano-scala-jdk:local bash scripts/sbtw check
```

`check` (an alias in `build.sbt`) runs `scalafmtCheckAll`, compiles all test sources and runs the
tests of the four modules; the compiler runs with `-Werror`. Dependencies and the sbt launcher are
cached under `.cache/` (gitignored). `bash scripts/sbtw node/runtimeClasspathFile` writes
`node/target/runtime-classpath.txt`, which the scenarios use.

### Fast iteration: a persistent sbt server (optional)

Starting sbt costs 40 to 60 seconds of JVM start and compile. For many small edits keep one
container alive and talk to its sbt server:

1. Start a long-lived container from `cardano-scala-jdk:local` with the checkout mounted at `/work`
   and the same `HOME` as above (for example `docker run -d --name csl-sbt ... tail -f /dev/null`).
2. Run sbt once inside it with the server enabled (a copy of `scripts/sbtw` without
   `-Dsbt.server.autostart=false`, kept outside the repository).
3. Send commands with `docker exec csl-sbt <that launcher> --client "node/testOnly lab.node.ledger.*"`.

Two traps: a running JVM loads classes lazily from `target/`, so never recompile under a live
devnet run (`devnet.py` copies the classes it runs from for this reason), and do not `pkill -f`
with a pattern that matches your own shell command line.

## Style rules

- **Tagless final at effect boundaries.** Capabilities are algebras over `F[_]`; interpreters are
  chosen in `Main`. `IO` appears only in `Main`, `JsonLog` and tools. No hidden global state:
  the memoising caches are instances created in a `Resource` (`VerificationCache`) and handed to
  the pure rules through a read interface (`Verdicts`); tools live under `lab.node.tools`.
- **Closed error ADTs through cats-mtl.** Domain errors are values of a closed ADT (`PeerError`,
  `ChainError`, `ValidationError`, `StorageError`, `ForgeError`, `ConfigError`) raised with
  `Raise[F, E]` and handled with lexically scoped `Handle.allow`. Never use `raiseError`/`throw` for
  a domain error; IO exceptions are mapped to `StorageError`/`PeerError` at the boundary only. No
  `.get`/`.toOption.get` on values that are not provably present.
- **Pure rules.** Ledger, consensus, mempool and chain-selection rules are pure functions over
  values. Effects wrap them, not the other way around.
- **Laws and properties.** New algebras and stateful structures get cats-laws or ScalaCheck
  properties (round trips, model equivalence, rollback), not only examples.
- **Original-bytes rule.** Anything that is hashed or signed (block bodies, transaction bodies,
  witness sets, scripts, datums, auxiliary data) is hashed over the bytes as received, never over a
  re-encoding. Keep the original bytes next to the decoded value.
- Exact arithmetic (`Rational`) in ledger calculations; no floating point.
- Format with scalafmt (`scalafmtAll`); SPDX header (`// SPDX-License-Identifier: Apache-2.0`) on new source files.
- No secrets in the repository: never commit `.skey`, `.key`, KES/VRF/cold keys or node sockets.
  Fixtures must be public chain data or throwaway keys, with provenance and licence recorded in
  the fixture directory's README and `NOTICE`.

## Gates by type of change

| Change | Run before opening a pull request |
|---|---|
| Any | `scripts/sbtw check` (CI runs it on every pull request) |
| Docs only | check that relative links resolve; no gate beyond review |
| Scripts (`scripts/devnet/**`) | `python3 -m unittest discover -s scripts/tests` (CI runs it), plus the scenario the change touches |
| Ledger or consensus (rules, rewards, governance, Plutus, nonces, headers) | `check`, plus `python3 scripts/devnet.py soak --seconds 480` and `e2e`; a Preview regression run through the affected region (use `fixtures/preview` or a `--db` follower) when the change was motivated by Preview data |
| Network (mini-protocols, mux, peer, chain selection) | `check`, plus `soak` and `relay` |
| Storage (stores, snapshots, UTxO set) | `check` with the storage suites, plus a restart check (`follow --db`, stop, restart) and `soak` |
| Forging or mempool | `check`, plus `pool` and `relay` |

The scenarios need the reference image (see [docs/reference-image.md](docs/reference-image.md))
and are described in [docs/running.md](docs/running.md). `prepare_reference_image.py --download`
fetches and verifies the official release itself. On GitHub, `.github/workflows/ci.yml` runs
`check`, the script tests, actionlint, a gitleaks scan of the whole history (`.gitleaks.toml`; a new
false positive is allowlisted there by path or commit, scoped to its rule, with a reason) and, on
pull requests, a dependency review; `.github/workflows/e2e.yml` runs
soak, e2e, relay and pool nightly, on demand and on version tags. Every scenario exits non-zero on
any mismatch or error, so run the ones your change touches before opening a pull request.

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
- Do not rewrite shared history. Do not commit generated run data (`/tmp/cardano-scala-lab`
  is outside the checkout on purpose; set `CSL_DATA_DIR` to move it).
- Fill in the pull request template; it asks which gates you ran and which compatibility row changes.
