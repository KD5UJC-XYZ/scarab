# Running the node and the devnet scenarios

**Status: Current usage reference.** Recorded network runs are historical evidence with their
own build and environment identities.

Everything runs in Docker; no host JDK or sbt is needed. See [CONTRIBUTING.md](../CONTRIBUTING.md)
for the build workflow. This node is research software: do not run it with keys that hold real
funds (see [SECURITY.md](../SECURITY.md)).

## Images

| Image | Built from | Used for |
|---|---|---|
| `scarab-jdk:local` | `docker/jdk.Dockerfile` (digest-pinned Temurin 25 plus Python 3) | building, running the node, `devnet.py` |
| `cardano-reference-11.1.3:local` | the checksum-pinned official release archive, see [reference-image.md](reference-image.md) | the Haskell reference cluster for the scenarios |

```sh
docker build -f docker/jdk.Dockerfile -t scarab-jdk:local .
```

Compile once and write the runtime classpath (the scenarios and the commands below use it):

```sh
scarab_git_metadata="$(git rev-parse --path-format=absolute --git-common-dir)"
scarab_cache="$(python3 -I -c 'import sys; sys.path.insert(0, "scripts")
from devnet.util import cache_dir; print(cache_dir())')"
mkdir -p "$scarab_cache"
docker run --rm -v "$PWD":/work -v "$scarab_cache":/work/.cache \
  -v "$scarab_git_metadata:$scarab_git_metadata:ro" -w /work --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  scarab-jdk:local bash scripts/sbtw node/compile node/runtimeClasspathFile
```

The Git metadata and dependency cache mounts are [CONTRIBUTING.md](../CONTRIBUTING.md#build-docker-only)'s.

This writes `node/target/runtime-classpath.txt`. Regenerate it with `node/runtimeClasspathFile`
after any dependency change: a stale file lacked rocksdb.

## CPU and platform requirements

Any x86-64 CPU or aarch64 that blst supports: nothing beyond what the architecture has. In
particular `adx`, `bmi2` and `avx` are not needed; an older 2-core x86-64 CPU runs the node. One
native library carries BLS12-381, and it picks the ADX/BMI2 multiplication at run time:

- The Leios BLS keys (`MinSig`) and the BLS12-381 builtins of Plutus (hash-to-group aside, which takes
  Bouncy Castle's hash-to-curve) use blst v0.3.17 from `tech.pegasys:jblst`, whose Linux/amd64 library is a portable build ([ADR 0025](adr/0025-jblst.md));
  the library of the earlier blst-java executed `mulx` unconditionally and killed the JVM with SIGILL on a
  CPU without it. At start `follow` checks the library and `/proc/cpuinfo` and logs a `blst` event
  (`platform`, CPU `features`, `library` `portable`); a library that is not the portable one on a
  processor not shown to have ADX and BMI2 is refused.
- The other cryptographic builtins of Plutus (Ed25519, secp256k1) run on the JVM, through the `crypto`
  module and Bouncy Castle ([ADR 0027](adr/0027-own-uplc-evaluator-as-a-correctness-backend.md)). The
  native library of the former Scalus backend, `libscalus_crypto`, is no longer shipped
  ([ADR 0020, amendment of 2026-11-15](adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md#amendment-2026-11-15-superseded-the-library-is-no-longer-shipped)).

The refusal is an `error` event of kind `NativeLibraryError` and exit code 1.

To check a build on a machine (for example a bundle on an old CPU; run it in the bundle directory, whose `classpath.txt` names `/app` paths):

```sh
docker run --rm -v "$PWD":/app:ro scarab-jdk:local sh -c 'java -cp "$(cat /app/classpath.txt)" xyz.kd5ujc.scarab.node.tools.BlstCheckMain bench'
```

It prints one JSON line: the platform, the CPU features, the library, three Plutus V3 scripts with the
BLS12-381 builtins (`builtins`), and, with `bench`,
operations per second (`opsPerSecond`); exit code 0 when all pass. It runs on the node's class path
(`node/runtimeClasspathFile`).

## `follow`: the node

```sh
java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow \
  (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> | --topology <topology.json>) [options]
```

(Inside the JDK image with the checkout mounted, or on a host with JDK 25: a developer's path, with JVM options on its
command line.) The release image and the Nix apps start the node through their launcher instead, which takes the heap,
the processor count and diagnostics from environment variables and, unless diagnostics are on, drops
`JAVA_TOOL_OPTIONS` ([Starting the node's JVM](running/launcher.md)). The sbt form is
`sbt "node/runMain xyz.kd5ujc.scarab.node.Main follow ..."`. The node logs one JSON object per line on stdout
(`started`, `extended`, `eraTransition`, `epochValidated`, `ledgerEpoch`, `snapshotWritten`,
`volatileResumed`, `shutdownSnapshot`, `forged`, ...). The epoch lines carry a `scheduling` object
(timer lateness and the longest CPU-bound step of that epoch, see "Scheduling" below). Each line has a namespace and a
severity, and the `--log-*` flags send them to stdout, stderr and a rotated file, filtered and sampled by namespace
(see "Logging" below).

## Pages

Each topic of this guide has a page of its own; the section names below are the original headings.

| Page | Sections |
|---|---|
| [A tester's runbook](running/tester-runbook.md) | Before you start; The images; A devnet: Scarab forging beside two Haskell pools; Mainnet from a Mithril snapshot to the tip; A preprod node; `cardano-cli` against the node-to-client socket; A pool on preprod; Cleaning up (each step with its commands and what success looks like) |
| [Starting the node's JVM](running/launcher.md) | the launcher of the release image and the Nix apps: its calling form, the variables it reads and removes, the options it sets, diagnostics, other JVM options, exit statuses, upgrading from an image before it |
| [Flags of `follow`](running/flags.md) | the table of every `follow` flag |
| [Databases, snapshots and imports](running/snapshots.md) | `import-haskell-db`: start from a Haskell node's blocks; `import-mithril`: certified bootstrap into a fresh database; `import-ledger-snapshot`: start from a reference ledger snapshot; `export-ledger-snapshot`: write a reference ledger directory; `export-scls` and `import-scls`: canonical ledger-state interchange |
| [Configuration, topology, peers and networks](running/configuration.md) | Configuration and topology files; Choosing the consensus mode; Peer selection; Preview; Preprod |
| [Ledger state against the Haskell node](running/oracle.md) | Ledger state against the Haskell node (the oracle, roadmap K1) |
| [Forging](running/forging.md) | Forging |
| [Node-to-client and the query server](running/node-to-client.md) | Node-to-client; Query server |
| [Logging](running/logging.md) | Logging |
| [Metrics](running/metrics.md) | Metrics |
| [Stalls and failures that stop the node](running/troubleshooting.md) | Stall watchdog; Failures that stop the node (#154); The JVM does not take an option |
| [Monitoring long runs](running/monitoring.md) | Monitoring long runs |
| [Fuzzing the decoders](running/fuzzing.md) | Fuzzing the decoders (roadmap K3) |
| [Timing of the secret paths](running/secret-timing.md) | What it measures (and does not cover); The nightly check; What a result means, and what to do; Running it by hand |
| [Capture and the devnet scenarios](running/devnet.md) | `capture`: recording blocks; Devnet scenarios (`scripts/devnet.py`) |
| [Conformance reports for node-diversity-conformance](running/conformance.md) | Running a scenario locally; Names and the reports repository; Publishing on demand (and setting up the reports repository and its key); Moving to a newer corpus |
