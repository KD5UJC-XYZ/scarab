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
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  scarab-jdk:local bash scripts/sbtw node/compile node/runtimeClasspathFile
```

This writes `node/target/runtime-classpath.txt`. Regenerate it with `node/runtimeClasspathFile`
after any dependency change: a stale file lacked rocksdb.

## CPU and platform requirements

Any x86-64 CPU or aarch64 that blst supports: nothing beyond what the architecture has. In
particular `adx`, `bmi2` and `avx` are not needed; an older 2-core x86-64 CPU runs the node. Two
native libraries carry BLS12-381, and both pick the ADX/BMI2 multiplication at run time:

- The Leios BLS keys (`MinSig`) use blst v0.3.15 built with `-D__BLST_PORTABLE__` by this
  repository ([ADR 0012](adr/0012-portable-blst.md)); blst-java's own library executed `mulx`
  unconditionally and killed the JVM with SIGILL on a CPU without it. At start `follow` checks the
  library and `/proc/cpuinfo` and logs a `blst` event (`platform`, CPU `features`, `library`
  `portable`); a library that is not the portable one on a processor not shown to have ADX and BMI2
  is refused.
- The Plutus builtins (Ed25519, secp256k1, BLS12-381) use `libscalus_crypto` of
  `org.scalus:scalus-crypto-jni:0.1.0`
  ([ADR 0020](adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md)). It needs glibc 2.33 or
  newer on Linux x86-64 and aarch64; musl (Alpine) is not supported. It is extracted to
  `java.io.tmpdir` and loaded from there, so that directory must exist, be writable and allow
  execution (a `noexec` `/tmp` makes the load fail; point `-Djava.io.tmpdir` elsewhere). At start
  `follow` checks the sha-256 of the library it would load against the pinned build of the
  platform, refuses a library of the same name on `java.library.path`, loads it, computes known
  answers and logs a `plutusCrypto` event. Without it no V1/V2 script that verifies an Ed25519
  signature could be evaluated.

Either refusal is an `error` event of kind `NativeLibraryError` and exit code 1.

To check a build on a machine (for example a bundle on an old CPU; run it in the bundle directory, whose `classpath.txt` names `/app` paths):

```sh
docker run --rm -v "$PWD":/app:ro scarab-jdk:local sh -c 'java -cp "$(cat /app/classpath.txt)" xyz.kd5ujc.scarab.node.tools.BlstCheckMain bench'
```

It prints one JSON line: the platform, the CPU features, the library, the Plutus native library
(`plutusCrypto`), three Plutus V3 scripts with the BLS12-381 builtins (`builtins`), and, with `bench`,
operations per second (`opsPerSecond`); exit code 0 when all pass. The class path must list the
`blst-native` module directory before the jars (`node/runtimeClasspathFile` does).

## `follow`: the node

```sh
java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow \
  (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> | --topology <topology.json>) [options]
```

(Inside the JDK image with the checkout mounted, or on a host with JDK 25.) The sbt form is
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
| [Flags of `follow`](running/flags.md) | the table of every `follow` flag |
| [Databases, snapshots and imports](running/snapshots.md) | `import-haskell-db`: start from a Haskell node's blocks; `import-mithril`: certified bootstrap into a fresh database; `import-ledger-snapshot`: start from a reference ledger snapshot; `export-ledger-snapshot`: write a reference ledger directory; `export-scls` and `import-scls`: canonical ledger-state interchange |
| [Configuration, topology, peers and networks](running/configuration.md) | Configuration and topology files; Choosing the consensus mode; Peer selection; Preview; Preprod |
| [Ledger state against the Haskell node](running/oracle.md) | Ledger state against the Haskell node (the oracle, roadmap K1) |
| [Forging](running/forging.md) | Forging |
| [Node-to-client and the query server](running/node-to-client.md) | Node-to-client; Query server |
| [Logging](running/logging.md) | Logging |
| [Metrics](running/metrics.md) | Metrics |
| [Stalls and failures that stop the node](running/troubleshooting.md) | Stall watchdog; Failures that stop the node (#154) |
| [Monitoring long runs](running/monitoring.md) | Monitoring long runs |
| [Fuzzing the decoders](running/fuzzing.md) | Fuzzing the decoders (roadmap K3) |
| [Capture and the devnet scenarios](running/devnet.md) | `capture`: recording blocks; Devnet scenarios (`scripts/devnet.py`) |
