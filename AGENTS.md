# Instructions for agent sessions

Scarab is a Cardano-compatible node in Scala 3.3 (LTS), written as tagless-final cats-effect code
with cats-mtl, and checked against the Haskell `cardano-node` as the reference. These instructions
apply to every automated session working in this repository; human contributors follow the same
rules through [CONTRIBUTING.md](CONTRIBUTING.md).

## Before you start

1. Read [docs/process.md](docs/process.md) (how work is tracked), then the issue you are working on,
   then any [design document](docs/design/README.md) and [ADRs](docs/adr/README.md) it links.
2. Check the issue's state. If it is `status:in-progress` and you were not asked to take it over,
   someone else is on it.
3. Work in your own git worktree on a branch from `main` (or the base you were given), and rebase onto
   `main` before you report: other sessions land in parallel and there is no shared integration branch
   ([CONTRIBUTING.md](CONTRIBUTING.md#landing-in-parallel)).
4. Feedback from the owner arrives as inline commit comments and issues labelled `owner-review`; triage
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
- Closed error ADTs. No `Either[String, …]`, no string-based control flow, no `throw` for domain
  errors, no `.get` on values that are not provably present.
- Opaque types, type classes with extension syntax, instances in companions, `derives` where it
  works, Monocle optics for nested state. Type parameters for roles and eras (`KeyHash[StakePool]`,
  `LedgerState[Conway]`).
- The Haskell node is the reference design ([ADR 0001](docs/adr/0001-haskell-node-as-reference.md)):
  mirror its types and formats, cite the upstream file and line, and take a real improvement only with
  a written reason.
- Pure rules; exact `Rational` arithmetic; anything hashed or signed is hashed over its original bytes.
- Stack safety ([ADR 0011](docs/adr/0011-stack-safety.md)): recursion whose depth or width comes from outside
  data (blocks, peers, files, queries) is a library fold/traversal, `@tailrec`, `tailRecM` over a typed
  state, or an explicit stack machine; never recursion inside an `Either`/`Option` for-comprehension.
  Test it on a small-stack thread.
- Comment density and naming like the surrounding code; SPDX header on new files.

## Before you hand back

- Name the level of your change and run its gates, no more
  ([CONTRIBUTING.md](CONTRIBUTING.md#gates-and-ceremony-by-level-of-change)): at least `check`
  (formatting, `-Werror`, every module's tests) and `scalafixAll --check`, plus one scenario per area touched.
  A documentation-only change (level 0) needs only that its relative links resolve.
  Soak, repeats and the long network replays are nightly CI's, not yours, unless your stage's design
  names them as its jump gate.
- A persisted format must not change without a version bump, a migration and a test that reads the
  previous version ([ADR 0006](docs/adr/0006-versioned-formats-migrate-on-open.md)). The snapshot
  fingerprint golden in `node/src/test/.../storage/CodecSuite.scala` guards this.
- Record evidence where the [definition of done](docs/process.md#definition-of-done) says, and track
  every gap you leave as an issue or roadmap item.
- Small, logical commits as CONTRIBUTING describes. A changelog entry is a fragment in `changes/`,
  never an edit of `CHANGELOG.md`. Do not push to `main` unless you were asked to.
- Report: branch and commits, what changed, gate results, deviations from the plan and why, and what
  the next stage needs to know.

## What does not belong in the repository

Signing keys or any secret; run data and logs; private working notes (keep them outside the checkout
or in an ignored directory). Distil what a later reader needs into the documents listed in
[docs/process.md](docs/process.md#one-place-per-question).
