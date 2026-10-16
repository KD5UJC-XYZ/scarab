# Running the node and the devnet scenarios

Everything runs in Docker; no host JDK or sbt is needed. See [CONTRIBUTING.md](../CONTRIBUTING.md)
for the build workflow. This node is research software: do not run it with keys that hold real
funds (see [SECURITY.md](../SECURITY.md)).

## Images

| Image | Built from | Used for |
|---|---|---|
| `scarab-jdk:local` | `docker/jdk.Dockerfile` (digest-pinned Temurin 21 plus Python 3) | building, running the node, `devnet.py` |
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
particular `adx`, `bmi2` and `avx` are not needed; an older 2-core x86-64 CPU runs the node. The
BLS12-381 library (Plutus builtins and Leios certificates) is blst v0.3.15 built with
`-D__BLST_PORTABLE__`, which picks the ADX/BMI2 multiplication at run time
([ADR 0012](adr/0012-portable-blst.md)); blst-java's own library, used by Scalus before, executed
`mulx` unconditionally and killed the JVM with SIGILL on a CPU without it. At start `follow` checks
the library and `/proc/cpuinfo` and logs a `blst` event (`platform`, CPU `features`, `library`
`portable`); a library that is not the portable one on a processor not shown to have ADX and BMI2
is refused with an `error` event of kind `NativeLibraryError` and exit code 1.

To check a build on a machine (for example a bundle on an old CPU; run it in the bundle directory, whose `classpath.txt` names `/app` paths):

```sh
docker run --rm -v "$PWD":/app:ro scarab-jdk:local sh -c 'java -cp "$(cat /app/classpath.txt)" xyz.kd5ujc.scarab.node.tools.BlstCheckMain bench'
```

It prints one JSON line: the platform, the CPU features, the library, three Plutus V3 scripts with the
BLS12-381 builtins (`builtins`), and, with `bench`, operations per second (`opsPerSecond`); exit code 0 when all pass. The class path must list the
`blst-native` module directory before the jars (`node/runtimeClasspathFile` does).

## `follow`: the node

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow \
  (--genesis-dir <dir> | --config <config.json>) (--peer <host>:<port> | --topology <topology.json>) [options]
```

(Inside the JDK image with the checkout mounted, or on a host with JDK 21.) The sbt form is
`sbt "node/runMain xyz.kd5ujc.scarab.node.Main follow ..."`. The node logs one JSON object per line on stdout
(`started`, `extended`, `eraTransition`, `epochValidated`, `ledgerEpoch`, `snapshotWritten`,
`volatileResumed`, `shutdownSnapshot`, `forged`, ...). The epoch lines carry a `scheduling` object
(timer lateness and the longest CPU-bound step of that epoch, see "Scheduling" below).

Flags, as parsed by `Main.parse` (`node/src/main/scala/xyz/kd5ujc/scarab/node/Main.scala`). Every flag takes a
value except `--forge`, `--dump-state-full` and `--mirror-check`. An unknown flag, a repeated flag (except `--peer`, which accumulates) and a flag without its value are refused with a typed `ConfigError` (M1).

| Flag | Default | Meaning |
|---|---|---|
| `--genesis-dir <dir>` | required unless `--config` | directory with `shelley-genesis.json`, `alonzo-genesis.json`, `conway-genesis.json`, `byron-genesis.json` (needed when the chain starts in Byron) and optionally a node configuration (`configuration.yaml`, `configuration.json` or `config.json`). Every hash that configuration lists is verified. With `--config` it is where the files the configuration names are looked up |
| `--config <config.json>` | off | read the Haskell node's configuration as `cardano-node run` does, see "Configuration and topology files" below |
| `--peer <host>:<port>` | required unless `--topology`, repeatable | node-to-node peer to follow; with several peers each is followed and chain selection picks the best chain. One peer that keeps failing is fatal after 30 attempts, with several it is retried forever |
| `--topology <topology.json>` | off | the Haskell node's P2P topology: its local roots and bootstrap peers are followed after the `--peer` ones |
| `--listen <host>:<port>` | off | accept node-to-node connections (versions 14 and 15) and serve our chain; on an inbound connection that agreed duplex the node also follows the peer (see `--inbound-sync`) |
| `--max-inbound N` | `64` | most inbound connections at once (G2): the connection manager refuses the next one with a typed error and logs `connection` / `inbound` `refused`; one connection per peer address in either direction |
| `--inbound-sync on\|off` | `on` | run our ChainSync, BlockFetch and KeepAlive clients on inbound duplex connections, so a peer that connected to us is a source of blocks as well as a consumer of ours (G2); `off` only serves them |
| `--header-first on\|off` | `on` | validate the headers a peer announces (envelope, VRF, leader threshold, KES, opcert, nonces) before any of their blocks is fetched (G6); `off` leaves every check to the block, as before. Throughput comparison in [performance.md](performance.md) |
| `--local-socket <path>` | off (`SocketPath` with `--config`) | Unix socket for the node-to-client protocols LocalStateQuery, LocalTxSubmission, LocalTxMonitor and ChainSync of whole blocks (versions 16 to 23); `cardano-cli conway query ...`, `transaction submit` and `query tx-mempool` work against it, see "Node-to-client" below |
| `--seconds N` | run until stopped | stop after N seconds |
| `--query-port P` | `7071` | loopback port of the HTTP query server (`GET /tip`, `GET /state?slot=&hash=`, `GET /scheduling`, `GET /connections`: the connection table and the inbound sessions) |
| `--db <dir>` | off (in-memory; replay from genesis on every start; `DatabasePath` with `--config`) | persistent chain: `network-identity`, `immutable/`, `volatile/`, `snapshots/`, `utxo/` |
| `--snapshot-every <N\|Nslots\|Nepochs>` | `1epochs` | snapshot interval; a bare number is slots |
| `--keep-snapshots N` | `3` | snapshots (and UTxO checkpoints) to keep |
| `--utxo-store memory\|rocksdb` | `rocksdb` with `--db`, otherwise `memory` | UTxO set interpreter |
| `--utxo-cache-mb N` | `256` | native-memory budget of the RocksDB store (it holds the UTxO set and the ledger tables): one LRU block cache shared by all tables, holding data, index and filter blocks, to which the memtables are charged, so the store stays near this figure. A smaller cache costs throughput, see [performance.md](performance.md) section 8 |
| `--memory-report-seconds N` | `0` (off) | log a `memory` event every N seconds: heap, non-heap, buffer pools, `/proc/self/status` (`RssAnon`, `RssFile`), the cgroup's `memory.current` and its `anon`/`file`, and the RocksDB properties (block cache usage, memtables). Use it with `-XX:NativeMemoryTracking=summary` and `jcmd <pid> VM.native_memory` to see where memory is |
| `--read-ahead N` | `128` | ChainSync requests pipelined per round, and the blocks of a round fetched in ranges of at most 100, while far behind. 64, 128, 200 and 256 were each run for 15 minutes against the public Preview relay without a timeout (the relay answers a 256-header round in 0.26 s on average); 128 and above are about 6 to 10% faster than 64, see [performance.md](performance.md) section 7 (D3) |
| `--cpu-threads N` | one per available CPU | workers of the pool the follower runs on (`scarab-cpu-N`: header handling, chain selection, the rules, persisting), apart from the pool that serves the network, timers and the forger; `0` runs everything on the cats-effect compute pool, as before D8 (still measured) |
| `--dump-state <dir>` | off | write a ledger-state digest (`<slot>-<hash>.json`) at the points of `--dump-state-at`, for the db-analyser comparison below |
| `--dump-state-at <points>` | `epoch-boundaries` | with `--dump-state`: `epoch-boundaries` (the first block of every epoch), `epoch-boundaries/N` (every Nth epoch) or `slot,slot,...` (the first block at or after each slot, what `db-analyser --store-ledger` stores) |
| `--mirror-check` | off | debug check of the ledger-state mirror (the Haskell `NewEpochState` as `DebugNewEpochState` answers it): at the first block and at the first block of every epoch the live state is turned into the mirror and back, and the log gets `mirrorChecked` (`era`, `epoch`, `slot`, `gaps`, `residueEmpty`, `retracts`, `ok`, `millis`) or, when the conversion refuses, `mirrorCheckFailed` (`stage`, `error`). It only reads the state, never changes it, and costs nothing when off; `devnet.py soak --mirror-check` fails unless every check held |
| `--rules old\|new\|differential` | `old` | which ledger rules run (step 2 of the ledger-state mirror, [design](design/ledger-mirror-step2.md) §2.5). `old` is the flat rules, unchanged. `new` is the era-indexed rules, which are not ported yet: it is refused at start with a `ConfigError` (J2.8 makes it the default). `differential` runs the old rules, whose verdict and state are the node's, and beside them the new side on every block: the verdicts are compared at every block, and the whole state (the old state through the mirror, against the new side's, by `Diff`) at the first block, at the first block of every epoch and era, and every 2,160 blocks. The tick of every Shelley-based era is ported (J2.3): the ledger state a block meets, after the old rules' tick and after the era-indexed one, is compared (`Stage.Tick`) at every epoch boundary, hand-over from Byron, start of the reward update and adoption of genesis delegations, and at the sampling points; the rest is not ported, and the new side takes the old state at the sampling points ("reseed everything"). The log shows what the conversion and the tick cost: `differentialSampled` (`slot`, `hash`, `epoch`, `era`, `toMirrorMillis`, `diffMillis`, `seeded`), `differentialTicked` (`slot`, `hash`, `epochsCrossed`, `oldMillis`, `seedMillis`, `newMillis`, `diffMillis`) and, at the end of a run, `differentialSummary` (blocks, samples, the cost's total, median, p99 and maximum, the ticks compared and their cost on both sides, and the eras and stages not ported). A disagreement is `rulesDiverged` (`kind` `StateDiffers` with the `path` of the first difference and both values, `VerdictDiffers`, or `Unconvertible`; `bundle`, where the repro was kept) and, with `--on-divergence halt`, `rulesHalted` and exit status 3. Memory: the new side's states ride beside the old ones for the volatile window, so a differential run needs more heap. `devnet.py {soak,e2e,follow,relay,pool,restart} --rules differential` (or `SCARAB_RULES=differential` for any scenario, `ntc` included) runs the scenario with it; the soak and e2e fail on a divergence |
| `--on-divergence halt\|reseed` | `halt` | with `--rules differential`: stop the run at the first disagreement (exit status 3; the devnet and the fixtures), or adopt the old side, seed the new side from it and go on (a long replay finds every divergence in one run) |
| `--repro-dir <dir>` | `<db>/differential` with `--db`, else none | with `--rules differential`: where the repro bundle of a divergence is kept, in a directory `<slot>-<hash prefix>`: `pre.snapshot` (the state before the block, a format-4 snapshot), `utxo.bin` (the entries of the UTxO set that the block reads), `block.cbor` and `divergence.json`. `xyz.kd5ujc.scarab.node.differential.DifferentialReplayMain <bundle> (--genesis-dir <dir> \| --config <file>)` runs it again in seconds; its exit status (1: still diverges) is the predicate of a bisect |
| `--dump-state-full` | off | with `--dump-state`: also write the whole projection, UTxO included, as `<slot>-<hash>.full` (drill-down on small states only) |
| `--mempool-bytes N` | `1048576` (`MempoolCapacityBytesOverride` with `--config`) | mempool capacity in bytes; LocalTxMonitor reports it as the reference does, a whole number of block-sized units |
| `--plutus-cases <dir>` | off | record self-contained JSON cases of Plutus transactions (used to build `fixtures/preview/plutus`) |
| `--forge` | off | produce blocks; requires `--vrf-skey`, `--kes-skey`, `--opcert` |
| `--vrf-skey <file>` | with `--forge` | `cardano-cli` text envelope `VrfSigningKey_PraosVRF` |
| `--kes-skey <file>` | with `--forge` | `KesSigningKey_ed25519_kes_2^6` (the period-0 key) |
| `--opcert <file>` | with `--forge` | `NodeOperationalCertificate` (it also carries the cold verification key; the cold signing key is never needed) |

Caveats:

- A `--db` records the network it was created for (`<db>/network-identity`: magic and the Shelley and
  Byron genesis hashes) and refuses to open with another genesis directory: `error` event `kind`
  `NetworkMismatch`, "the database belongs to another network: it was created for magic ..., this node
  is configured for magic ...". A database from before the file existed is adopted if its first block
  names the configured Byron genesis hash (`ForeignChain` otherwise); on a Shelley-start network that
  check cannot tell two networks apart. The devnet rewrites its genesis at every cluster start, so a
  `--db` of an earlier cluster is refused too.
- A database written before the rename (format 1: `cardano-scala-lab ...` identifiers, index magic
  `CSLIDX01`) is migrated to format 2 the first time it opens: the identity file, every chunk index and the
  `format` file are rewritten one by one (temporary file, fsync, rename), `formatMigrated` events say what,
  and the second open does nothing. A crash in the middle is harmless, the next open finishes. The
  migration is one way and an older build cannot open the result: keep a copy (the chunk files are not
  touched, so copying `network-identity`, `immutable/format` and `immutable/*.index` is enough to go back).
  A database of a format newer than the build refuses to open (`UnknownVersion`) and nothing is rewritten.
- The ledger snapshots are format 4 since the chain-dependent state moved onto the Haskell types (step
  2, jump 1, J1.5; format 3 was the first with the Haskell ledger state plus a sidecar,
  `docs/architecture.md`, "Snapshot format"). A database with snapshots of format
  1, 2 or 3 has them rewritten as format 4 the first time this build opens it, before any block is
  replayed: one file at a time, atomically, under the same name, so a crash leaves each snapshot whole
  in either format and the next open finishes. It costs a few seconds per snapshot on a Preview-size
  state (a Preview database with the default 3 kept snapshots opens in well under a minute); the log says
  `formatMigrated` with `kind` `SnapshotFile` and, for a snapshot it could not convert (corrupt, another
  schema, a state the mirror refuses), `snapshotRefused`: that one is left as it was, skipped when the
  snapshots are opened, and the node falls back to an older snapshot or replays. Nothing is replayed
  by the migration itself. It is one way and an older build cannot read format 4: to keep the option of
  an older build, copy `<db>/snapshots/` (and nothing else changes: the chunk files, the indexes and
  the UTxO checkpoints are not touched) before the first start with this build. A snapshot of a format
  newer than the build refuses the open (`UnknownVersion`) before anything is rewritten.
- The volatile blocks (the last `k`) are kept in `<db>/volatile/` and applied again on start
  (`volatileResumed`), and a graceful stop (SIGTERM, SIGINT, `--seconds`) writes a snapshot at the
  immutable tip (`shutdownSnapshot`): the next start replays no stored block and continues at the
  previous tip. After `kill -9` the node resumes from the newest periodic snapshot, replays the stored
  blocks after it, and takes the volatile chain from the log as far as the log reached the disk.
  Run the node so that the signal reaches the JVM (`docker stop`, not a shell wrapper that eats it).

### Configuration and topology files

`--config <config.json>` reads the Haskell node's configuration file (JSON; a YAML file written in JSON
syntax works, full YAML does not) the way `cardano-node run` reads it (`Cardano.Node.Configuration.POM`),
and `--topology <topology.json>` its P2P topology (`Cardano.Node.Configuration.TopologyP2P`). The public
Preview, preprod and mainnet files are the tests (`ConfigSuite` over `fixtures/<network>/genesis`).

What the configuration does:

| Key | Use |
|---|---|
| `ByronGenesisFile` (or `GenesisFile`), `ShelleyGenesisFile` (or `GenesisFile`), `AlonzoGenesisFile`, `ConwayGenesisFile` | the genesis files, looked up next to the configuration (or in `--genesis-dir`); required; `GenesisFile` next to the specific name is an error |
| `ByronGenesisHash`, `ShelleyGenesisHash`, `AlonzoGenesisHash`, `ConwayGenesisHash` | verified against the files: the Byron hash is that of the canonical JSON of the file, the others the blake2b-256 of the file's bytes (`cardano-cli genesis hash`). A mismatch is a `ConfigError` of kind `GenesisHashMismatch` that names the file, the listed and the actual hash; a hash that is not 64 hexadecimal digits is refused. The check runs for `--genesis-dir` too |
| `DijkstraGenesisFile`/`Hash`, `CheckpointsFile`/`Hash` | the hash is verified when the file is present; the Dijkstra era and checkpoints are not implemented |
| `TestShelleyHardForkAtEpoch` ... `TestConwayHardForkAtEpoch` | the first epoch of each era (`0` for all of them: the chain starts in that era, as Preview); absent for Shelley: the Byron ledger decides |
| `RequiresNetworkMagic`, `PBftSignatureThreshold` | the Byron address magic and the PBFT signing threshold |
| `LastKnownBlockVersion-Major/Minor/Alt`, `MaxKnownMajorProtocolVersion`, `Protocol` (must be `Cardano`), `ExperimentalHardForksEnabled`, `ExperimentalProtocolsEnabled`, `ConsensusMode`, `PeerSharing`, `DiffusionMode` | read, validated and logged (`started`); the node does not act on them. `configUnsupported` in the `started` line lists the ones that ask for something not implemented: experimental hard forks (the Dijkstra era), `GenesisMode` (no Genesis syncing: the configured peers are followed as in Praos mode), `Test<Era>HardForkAtVersion`, peer sharing |
| `SocketPath`, `DatabasePath`, `MempoolCapacityBytesOverride` (a byte count; `NoOverride` leaves the default) | the defaults of `--local-socket`, `--db` and `--mempool-bytes`, with `--config` only; a flag wins |
| everything else (`TraceOptions`, `LedgerDB`, peer targets, ...) | ignored |

The topology needs `localRoots` and `publicRoots`; `bootstrapPeers` (a list or `null`), `useLedgerAfterSlot`
(above 0: after that slot, `0`: always, below: never) and `peerSnapshotFile` are optional. An access point is
`{"address": <name or IP>, "port": <number>}`; an access point without a port (a DNS SRV name), the
unspecified address, a port that is not a number, the legacy `Producers` format, and an empty
`bootstrapPeers` with no trustable local root are `ConfigError`s. The peers followed are the access points
of the local roots, then the bootstrap peers (the public roots when there are none of those), after the
`--peer` ones and without repeats; the node follows all of them rather than keeping `hotValency` of them
active. `useLedgerAfterSlot`, `peerSnapshotFile`, `advertise`, `behindFirewall`, `trustable` and the
valencies are read and logged (`started.topology`), not acted on: there are no ledger peers or peer
sharing.

### Preview

The node follows the public Preview testnet from genesis (network magic 2):

```sh
java -Xmx3g -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow \
  --config <preview config dir>/config.json --topology <preview config dir>/topology.json \
  --db "${SCARAB_DATA_DIR:-/tmp/scarab}/preview-db"
```

(`--genesis-dir <preview config dir> --peer preview-node.play.dev.cardano.org:3001` still works.)
The Preview genesis files (Byron, Shelley, Alonzo, Conway), `config.json` and `topology.json` come from the
official Cardano network configuration (`fixtures/preview/genesis/` holds a copy for the tests). Fetch them
into the config directory, in a container like everything else:

```sh
mkdir -p "${SCARAB_DATA_DIR:-/tmp/scarab}/preview/config"
docker run --rm --user "$(id -u):$(id -g)" -w /out \
  -v "${SCARAB_DATA_DIR:-/tmp/scarab}/preview/config":/out --entrypoint bash scarab-jdk:local -c \
  'for f in config.json topology.json byron-genesis.json shelley-genesis.json alonzo-genesis.json conway-genesis.json; do
     curl -fsSL -o "$f" "https://book.play.dev.cardano.org/environments/preview/$f"; done'
```

Every hash `config.json` lists is verified against its file (Byron, Shelley, Alonzo, Conway). Preview
starts in Alonzo at epoch 0. The recorded
genesis-to-tip numbers are in [performance.md](performance.md). The run needs about 14 GB of disk
for the immutable blocks, RocksDB and checkpoints.

**Memory.** The resident anonymous memory of the process is the heap limit plus about 0.8 GB: with
`-Xmx3g` it was 3.86 GB after 45 minutes of a Preview sync (heap 3.0, the JVM's own native memory 0.3,
RocksDB 0.26, malloc 0.23), and the page cache of the files the node writes comes on top and is given
back by the kernel under a limit. **Set `MALLOC_ARENA_MAX=2`** (the `scarab-jdk` image and the
devnet do): with glibc's default of one arena per thread the same sync grew by 0.9 GB in 30 minutes and
had reached 5.66 GB in the uninterrupted run (roadmap K6, [performance.md](performance.md) section 8).
A container limit of `-Xmx` plus 2 GiB (5 GiB for `-Xmx3g`) is enough; `--utxo-cache-mb` is the other
number that moves native memory. The page cache shows in `docker stats` only partly (as the cgroup's
active file pages) and in `memory.current` fully: bound `RssAnon`, not `memory.current`.

### `import-haskell-db`: start from a Haskell node's blocks

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main import-haskell-db \
  --haskell-db <haskell db dir> (--genesis-dir <dir> | --config <config.json>) --db <dir> [--stop-slot N]
```

Reads `<haskell db dir>/immutable` (a Haskell node's ImmutableDB; never written, so a running
node's directory may be given, but copy it if you can) and appends its blocks to the immutable store
of `--db`. A following `follow --db <dir>` replays them locally instead of downloading them, then
intersects the peer at the imported tip. The command refuses a database whose `protocolMagicId` is not
the configured network's, a first block that does not name the configured genesis, blocks that do
not link or whose header hash or slot differ from the index, and a corrupt chunk (a typed error naming
the chunk and the fault); the newest chunk, which the node may be writing, is cut back to its valid
blocks and the number ignored is printed. `--stop-slot N` stops after the last block at or before slot
`N`. A second run continues after the blocks already stored. It prints JSON lines
(`importHaskellDb`: `progress`, `done` or `error`). The Haskell node's ledger snapshots are not read:
the ledger state is rebuilt by the replay.

Measured (2026-10-15, 3 CPUs, 200 Preview chunks copied from the Haskell node's database): 40,357
blocks (slots 0 to 863,996, 46 MB) imported in 2 s; `follow` then replayed them in 42.7 s, connected
with the intersection at the imported tip (slot 863,996), and went on to slot 3.33 M (epoch 38) in
the following 6.5 minutes.

### Preprod

The node follows the public preprod testnet (network magic 1) from genesis through its Byron era
(an epoch 0 boundary block and 45 main blocks over four epochs) into Shelley and on:

```sh
mkdir -p "${SCARAB_DATA_DIR:-/tmp/scarab}/preprod/config"
docker run --rm --user "$(id -u):$(id -g)" -w /out \
  -v "${SCARAB_DATA_DIR:-/tmp/scarab}/preprod/config":/out --entrypoint bash scarab-jdk:local -c \
  'for f in config.json topology.json byron-genesis.json shelley-genesis.json alonzo-genesis.json conway-genesis.json; do
     curl -fsSL -o "$f" "https://book.play.dev.cardano.org/environments/preprod/$f"; done'
java -Xmx3g -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow \
  --config "${SCARAB_DATA_DIR:-/tmp/scarab}/preprod/config/config.json" \
  --topology "${SCARAB_DATA_DIR:-/tmp/scarab}/preprod/config/topology.json" \
  --db "${SCARAB_DATA_DIR:-/tmp/scarab}/preprod-db"
```

`config.json` has no `TestShelleyHardForkAtEpoch`, so the Byron ledger decides when the chain
enters Shelley (epoch 4, from the update proposals the Byron blocks carry); `RequiresNetworkMagic`
and `PBftSignatureThreshold` are read from it. The `started` line shows `"byronEpochs": null` until
then, and the `eraTransition` line to Shelley the derived epoch. The mempool and the forger take the
epoch history at start-up, so run them (`--listen`, `--forge`) from a database that is already past the
transition (restart once after the Byron era); a node that starts from Origin and stays up through
the transition validates correctly but would serve its mempool with the Byron-era history.
The recorded run is in [performance.md](performance.md).

**With one or two CPUs** (`docker run --cpus 1`, a small VM) start the JVM with
`-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`. A JVM that finds one CPU picks the serial
collector, whose pauses stop every thread: on Preview it made 7,595 collections in 420 s (11 full, the
longest 396 ms) and the node's timers fired late (cats-effect starvation warnings, worst 674 ms); with
the flags above a 31 min sync had none, at the same blocks per second as the base build
([performance.md](performance.md) section 6). A fixed heap avoids the full collections that come from
growing it. The node keeps its follower on its own pool (`--cpu-threads`, default one worker per CPU)
and reports the worst timer lateness and the longest CPU-bound step of every epoch in the log.

Stopping: SIGTERM or SIGINT (`docker stop`, Ctrl-C) cancels the node, which closes its servers and
writes a snapshot at the immutable tip before it exits (a second or two on Preview; allow
`docker stop -t 60`). `kill -9` is recovered from, at the cost of replaying the stored blocks since the
newest periodic snapshot.

### Ledger state against the Haskell node (the oracle, roadmap K1)

Following a chain shows that valid blocks are accepted. The oracle shows that the ledger state is the
same: at the first block of every epoch the Scala node writes a digest of its state (`--dump-state`), the
reference image's `db-analyser` stores the Haskell ledger state after the same block, and
`scripts/oracle_compare.py` decodes that snapshot (`scripts/oracle/`) into the same projection and
compares. The projection is the one the devnet comparison uses (`Projection.node`): UTxO, fees,
deposits, treasury, reserves, accounts, pools and their parameters, DReps, the three stake snapshots,
the reward update, the pool distribution, protocol parameters, block counts, the Praos nonces and
opcert counters and, from Conway, the whole governance state (proposals and votes, committee, constitution,
ratification state, DRep distribution, committee hot keys, donations).

A map with more than 64 entries is reduced to its entry count and the sum, modulo 2^256, of the
SHA-256 of each entry's text (key, a NUL byte, compact JSON with sorted keys), plus that sum for each
of sixteen buckets of the key hash; a difference names the buckets, which narrows it to a sixteenth of
the entries. The UTxO set is one such map (`StateDump.scala`, `scripts/oracle/digest.py`; a
shared test vector keeps the two in step). A difference is drilled down with `--dump-state-full` on the Scala
side and `oracle_compare.py project --full` on the Haskell side.

On a public network (Preview shown; the configuration files are those of "Preview" above):

```sh
# 1. A Haskell node that syncs the network. PraosMode and a plain topology (the peer snapshot and the
#    checkpoints file of the official Preview configuration are not needed); 6 CPUs, db outside the checkout.
D="${SCARAB_DATA_DIR:-/tmp/scarab}"
mkdir -p "$D/haskell-preview-config" "$D/haskell-preview-db" "$D/haskell-preview-ipc"
cp "$D"/preview/config/*.json "$D/haskell-preview-config/"
#    edit config.json: "ConsensusMode": "PraosMode", remove CheckpointsFile and CheckpointsFileHash;
#    topology.json: {"bootstrapPeers": null, "localRoots": [{"accessPoints": [{"address":
#    "preview-node.play.dev.cardano.org", "port": 3001}], "advertise": false, "valency": 1, "trustable": true}],
#    "publicRoots": [{"accessPoints": [], "advertise": false}]}
docker run -d --name scarab-k1-haskell-preview --cpus 6 --memory 32g --user "$(id -u):$(id -g)" \
  -v "$D/haskell-preview-config":/config:ro -v "$D/haskell-preview-db":/db -v "$D/haskell-preview-ipc":/ipc \
  --entrypoint cardano-node cardano-reference-11.1.3:local run --config /config/config.json \
  --topology /config/topology.json --database-path /db --socket-path /ipc/node.socket --port 3001 --host-addr 0.0.0.0

# 2. The Scala node from genesis, writing a digest at every epoch boundary (the same configuration as above).
java -Xmx3g -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow --genesis-dir "$D/preview/config" \
  --peer preview-node.play.dev.cardano.org:3001 --db "$D/preview-scala-db" --keep-snapshots 2000 \
  --dump-state "$D/preview-dump" --dump-state-at epoch-boundaries

# 3. The Haskell side: db-analyser over the node's ChainDB, one projection per epoch (Docker, parallel
#    segments; 86400 slots per epoch on Preview). It works on hard-linked copies; --live first copies the
#    finished chunks of a node that is still running.
python3 scripts/oracle_compare.py reference --config "$D/haskell-preview-config" --db "$D/haskell-preview-db" --live \
  --work "$D/preview-ref-work" --out "$D/preview-ref" --epoch-slots 86400 --jobs 5 --seed-every 60

# 4. Pair the projections with the Scala digests by block and compare (run it again as either side grows).
python3 scripts/oracle_compare.py diff --reference "$D/preview-ref" --dump "$D/preview-dump" --results "$D/preview-diff.json"
```

`reference` chains `db-analyser --store-ledger <slot> --analyse-from <previous snapshot>` (Docker, `--cpus 2
--memory 16g` each): a seed pass over the chain stores the state at every `--seed-every`-th epoch, and each
segment of that many epochs then continues from its seed in a worker of its own. Each epoch costs one
snapshot load, one epoch of blocks and one snapshot write (5 s with a tiny state, about 30 s at one
million UTxO entries); the snapshot is projected, the previous one deleted, and the projection written to
`<out>/<slot>-<hash>.json` with a line in `<out>/index.jsonl`; a restart skips the epochs in the index.
`db-analyser` needs a ChainDB of its own (it opens the newest chunk for writing), which `--work` holds.

The Scala side does not need the network either: a Scala `--db` directory that holds only `immutable/` is
replayed from genesis (about 200 to 1,100 blocks/s depending on load and era) and writes the same digests.

Without a Haskell node that has synced the network, the Scala node's own immutable store can be exported
as a Haskell ImmutableDB (`oracle_compare.py export --scala-immutable <db>/immutable --out <dir>
--chunk-slots 4320 --magic-file <a protocolMagicId file> --verify-against <a Haskell node's ChainDB>`). The
blocks are the same bytes; only the chunk, primary and secondary index files are written. Against the chunks a
Haskell node synced from the Preview relay, the first 2,943 chunks (slots up to 12.7 million, epoch 147)
of the export are identical byte for byte, files and indexes. `--chunk-slots` is ten times the security
parameter of the Byron genesis (Preview 4320, Preprod and Mainnet 21600).

The recorded Preview run (2026-10-14) kept everything under `.workspace/data/2026-10-14-k1-*`: the Haskell node
(`csl-k1-haskell-preview`, `--cpus 6`), the Scala node from a frozen copy of the build (`csl-k1-scala-preview`,
`--cpus 6 --memory 10g`, `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`; `run.sh` resumes from its
database) and a loop (`watch.sh`) that repeats steps 3 and 4 every 20 minutes. About 1.8 epochs per minute on
both sides while the other is also syncing.

On a devnet cluster: `python3 scripts/devnet.py e2e --oracle` (or `soak --oracle`, or `follow --dump-state`
then `oracle`) runs `db-analyser` inside the reference container on a copy of node 1's ImmutableDB and
compares at every epoch boundary the follower digested (`scripts/oracle/devnet.py`); the summary is in
`oracle-summary.json` of the run directory and in the `oracle` block of `e2e-summary.json`.
The first epoch of a chain the follower started in Byron has no digest (the follower digests an epoch
when it sees the epoch number change in Shelley-based blocks).

### Forging

```sh
follow ... --listen 127.0.0.1:3001 --forge --vrf-skey vrf.skey --kes-skey kes.skey --opcert opcert.cert
```

The pool must already be registered with stake in the ledger the node follows. Only Praos (Conway)
blocks are forged. Keep key files outside the repository and readable only by the node's user (the
scenarios mount them read-only with mode 0400). See [architecture.md](architecture.md) for the
forging design and [compatibility.md](compatibility.md) for what is not covered (no KES agent, no
opcert issuing, never run on a public network).

### Node-to-client

With `--local-socket <path>` the node serves four node-to-client protocols on a Unix socket: LocalStateQuery,
LocalTxSubmission, LocalTxMonitor and ChainSync of whole blocks. The reference image's `cardano-cli` can talk
to it:

```sh
docker run --rm -v <socket dir>:/sock --entrypoint /opt/reference/bin/cardano-cli cardano-reference-11.1.3:local \
  conway query tip --testnet-magic <magic> --socket-path /sock/node.socket
```

Answered, with CBOR equal to the reference node's at the same block (the `ntc` scenario below):
`tip`, `protocol-parameters`, `utxo` (whole, by address, by input), `stake-distribution`, `stake-pools`,
`pool-params`, `pool-state`, `stake-snapshot`, `stake-address-info`, `gov-state`, `drep-state`,
`drep-stake-distribution`, `committee-state`, `constitution`, `ratify-state`, `future-pparams`,
`treasury`, `spo-stake-distribution`, `protocol-state`, `era-history`, `slot-number`, and the queries
behind them (see [architecture.md](architecture.md#node-to-client---local-socket)). `ledger-state`
(`DebugNewEpochState` and `DebugEpochState`, also as `GetCBOR`) is answered from the mirror of the Haskell
state, in every Shelley-based era: byte-equal to the reference's except inside the documented gaps (the
non-myopic likelihoods, the `numDelegators` of the snapshots, the VRF registry of the genesis pools and the
reward update; `scripts/devnet/gapmask.py`, [compatibility.md](compatibility.md)), and without the UTxO, which
the reference leaves out too (`map(0)`). Not answered yet
(the connection ends, the log line `inbound ... closed` names the query): `GetRewardInfoPools`, `GetNonMyopicMemberRewards`,
`GetRewardProvenance`, `GetLedgerPeerSnapshot`, `DebugLedgerConfig`, and every query but `ledger-state` in an era other than
Conway (roadmap F1). A transaction refused over the socket carries the ledger's predicate failure, so `cardano-cli
transaction submit` prints the same error as against the Haskell node.

LocalTxMonitor (mini-protocol 9) gives `cardano-cli conway query tx-mempool info | next-tx | tx-exists` and
the protocol's `GetMeasures` (no CLI command): a client acquires a snapshot of the mempool (the slot after
the tip, the transactions in arrival order), walks it with `NextTx`, asks `HasTx`, `GetSizes` and
`GetMeasures`, and `AwaitAcquire` waits until the pool differs. The sizes are those of the reference's server:
each transaction counts for its size plus 4 bytes, and the capacity is a whole number of block-sized units
(`2 * (maxBlockBodySize - 1024)` by default for the reference; here `ceil(--mempool-bytes / unit)` units, so
start the node with `--mempool-bytes` equal to the reference's to see the same `capacityInBytes`).
Local ChainSync (mini-protocol 5, what db-sync, Ogmios and `cardano-cli`'s followers use) serves each block as
`#6.24(bytes)` of the block as stored (`[era, block]`), from the immutable store (with `--db`) and the volatile
chain, with the same intersection, roll-forward, roll-backward and await behaviour as on node-to-node.
Without `--db` only the last `k` blocks can be served, so a client that starts from Origin once the chain is
longer than that is told there is no intersection.

### Query server

`GET /tip` and `GET /state?slot=<n>&hash=<hex>` serve the node's projection of the ledger and
consensus state at a block; the devnet comparator diffs this against the Haskell node. It binds to
loopback and is a development interface, not a stable API. `GET /scheduling` serves the scheduling
measurements and, on a node started with `--forge`, `GET /forging` the forger's counters per epoch and in
total (`slotsChecked`, `leaderSlots`, `forged`, `adopted`, `lostSlotBattles`, `failed`, `skipped` and
`skippedByReason`, `forgeLatencyMs` from the slot's start to adoption); the same counters of an epoch are
logged as a `forgeEpoch` line when the next epoch begins.

The slot clock starts at the Byron genesis' `startTime` when `byron-genesis.json` has one (as the
reference's does), else at the Shelley `systemStart`; the `started` line logs it as `systemStart`.
Write both identically in a network you make yourself.

## Monitoring long runs

Grafana dashboards and log search for the runs on the workers: Prometheus, Loki and Grafana on one
server, and an agent on each worker that pushes the `scarab-*` containers' logs and the host and
container metrics. Setup and deployment: [ops/monitoring](../ops/monitoring/README.md).

## Fuzzing the decoders (roadmap K3)

The `fuzz` project (`fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz`) throws mutated and generated bytes at every decoder that reads
data from a peer, a client or a block: raw CBOR, the node-to-node and node-to-client handshakes, the mux (header,
frame, stream), ChainSync, BlockFetch, TxSubmission2, KeepAlive, LocalStateQuery, LocalTxSubmission and
LocalTxMonitor in every state and for both roles, headers and blocks (Byron and each later era), transaction bodies,
witness sets and whole submitted transactions per era, addresses and outputs, scripts, Plutus data, node-to-client
queries and protocol-parameter updates. 53 targets in all (`Targets.scala`).

Inputs (`Mutate.scala`, `Corpus.scala`): ScalaCheck generators that take a seed item and apply one to four
mutations (bit flips, truncation, extension, a head rewritten to declare 2^60 elements, a fresh head with a huge
length, nesting of up to 100,000 levels, an indefinite item without its break or a break without its item, extra
tags, a whole item replaced by a deeply nested one, splicing two items), random bytes, and random heads with random
tails. The seed items are the committed fixtures (`fixtures/*/*.hex`, every era) and valid items sampled from the
pinned CDDL by the `models-gen` sampler (messages of each mini-protocol, headers, blocks, transaction bodies and
witness sets of each era, Plutus data).

Properties (`Harness.scala`, `Targets.scala`): the decoder never throws (a result or a typed error), finishes within
2 s (a decode stuck for 30 s kills the fork after printing the input), allocates at most 24 MB plus 400 bytes per
input byte (so a declared length cannot buy memory), and what decodes re-encodes and decodes to the same message
(mini-protocols: also the consumed prefix decodes alone and a shorter prefix does not). `GuardSuite` adds the fixed
cases: the seeds of every target are accepted by it, and a short input that declares 2^60 or 2^32 elements or bytes
(alone, nested, behind tags) fails within 4 MB of allocation in every decoder.

```sh
scripts/sbtw check                    # includes fuzz/Test/test: 20,000 cases per target, seed 1, about a minute
scripts/sbtw "fuzz"                   # the same suite with 2,000 cases, seed 1
scripts/sbtw "fuzz 100000 1000 8"     # fuzz <cases per target and seed> <first seed> <number of seeds> [target]
scripts/sbtw "fuzz 100000 1000 8 block-header"   # only targets whose name contains the text
```

The suite runs in a forked JVM (`-Xmx1536m`) from the repository root, so a hang or an out-of-memory ends the fork
and not the build server. The cases are deterministic functions of (target name, seed, case number): the same
command repeats the run.

**Reproducing a failure.** A failing target prints the seed, the shrunk input in hex and the command, for example

```
fuzz target address-and-output failed with seed 1 (reproduce: fuzz 3000 1 1 address-and-output)
> ARG_0: Input(5512 bytes, hex 5b8181...)
> Exception: xyz.kd5ujc.scarab.fuzz.Violation: address-and-output allocated 27450488 bytes (limit 27370624) for 5512 bytes
```

Run that command to see it again (the shrinking removes chunks of the input, then zeroes bytes). To keep it, paste
the hex into a regression test next to the decoder (see `DecoderRobustnessSuite`), minimise it by hand if the
shrunk input is still large, and record the finding in [findings.md](findings.md). The nightly workflow
`.github/workflows/fuzz.yml` runs `fuzz 60000 <date-based seed> 8` and keeps the log of a failing run for 30 days;
start it by hand with other values from the Actions tab (`cases`, `first-seed`, `seeds`).

## `capture`: recording blocks

`xyz.kd5ujc.scarab.node.tools.CaptureMain` records raw blocks (one hex-encoded block per line) for offline replay tests:

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.tools.CaptureMain capture \
  --peer <host>:<port> --magic M --blocks N|all --out <file> [--from <slot>.<hashhex>]
```

It starts at genesis (or after `--from`) and keeps going until the recorded blocks are 10 deep, so
none is rolled back; `--blocks all` records up to 10 blocks behind the tip. The fixtures under
`fixtures/preview`, `fixtures/mainnet` and `fixtures/eras` were captured with it.

## Devnet scenarios (`scripts/devnet.py`)

`devnet.py` drives Docker containers: a `cardano-testnet` cluster of three Haskell pools from the
reference image, and the Scala node running from a frozen copy of the compiled classes in
`scarab-jdk:local`. The Scala node shares a reference container's network namespace because
the reference nodes bind loopback. Requirements: both images, the classpath file from "Images", Docker,
Python 3 (standard library only), roughly 10 minutes per scenario.

Run data lives in `${SCARAB_DATA_DIR:-/tmp/scarab}/<name>` (never in the checkout or the home
directory). `--name` selects the cluster name (default `scarab-devnet`, `scarab-eras` for `up-eras`), so a
second cluster can run beside another. Directories and sockets that containers of other uids must
write are made world-writable (mode 0777) on a local devnet; the key files are 0400.

```sh
python3 scripts/devnet.py <subcommand> [options]     # python3 scripts/devnet.py --help
python3 scripts/devnet.py down                       # remove containers and the run directory
```

| Subcommand | What it does | What it asserts |
|---|---|---|
| `soak [--seconds 600] [--every 20] [--seed 1] [--governance-extended] [--oracle] [--mirror-check] [--rules differential]` | `up` + Scala follower + ADA, staking and Conway governance/Plutus V3 load + periodic comparison; `--governance-extended` adds the scenarios of [`govextra.py`](../scripts/devnet/govextra.py) (below; needs `--seconds 900`) | exit status fails on any mismatched comparison or follower error; with `--governance-extended` also on a transaction the reference was expected to refuse but did not refuse for the named predicate failure, and on an extended stage that was not reached. Compares at matching tips: UTxO, fees, deposits, treasury, reserves, accounts, pools, DReps, snapshots, reward update, Praos nonces, governance state |
| `e2e [--timeout 1500] [--until-epoch N] [--keep] [--no-load] [--no-compare] [--min-comparisons 3] [--oracle]` | `up-eras` (Byron, Shelley, Allegra, Mary, Alonzo, Babbage, Conway) + follower from Origin + era-specific load + per-era ledger comparison | the `eraTransition` lines are exactly the seven eras at the configured epochs; every era applied blocks; all Byron signatures verified; no validation or fatal errors; every comparison matched, at least `--min-comparisons` per Shelley-based era. Writes `e2e-summary.json` and `compare.jsonl` |
| `relay [--seconds 300] [--peers 3] [--relay-mode ...] [--keep]` | the Scala node follows the pools and serves a non-producing Haskell relay (`--listen`, `--db`, `--local-socket`); load is submitted through the relay | the Haskell relay syncs the whole chain from the Scala node and stays at the tip (lag samples every `--every` seconds), transactions travel relay, Scala mempool, pools, block, and one is submitted with `cardano-cli` to the Scala socket. G2: the Haskell relay connects to the Scala node (it is in the relay's topology; the Scala node has no `--peer` for it) and, with the default `--relay-mode InitiatorAndResponder`, the Scala node's own ChainSync client follows the relay over that same inbound duplex connection: `GET /connections` shows an established inbound duplex connection and an inbound session with an intersection that keeps receiving headers and whose peer tip is within 3 blocks of the pools', and the log has the `inbound` events `duplex` and `following` |
| `ntc [--seconds 240] [--warmup 60] [--every 10] [--chain-sync-blocks 60] [--monitor-burst 4]` | a fresh cluster, the Scala node following pool 1 with `--local-socket` and `--db` (and `--mempool-bytes` equal to the reference's default capacity), and the ordinary and governance load running. Rounds of LocalStateQuery: the Scala node's volatile tip is acquired on the reference node by point, and every query of the battery (`scripts/devnet/ntcqueries.py`, about 55 queries built from the live state) goes to both. Then `cardano-cli conway query ...` (24 commands) against both sockets, each pair accepted only when the tip read before and after is the same block on both nodes. Then 18 refused transactions (`scripts/devnet/ntccases.py`: fee too small, inputs spent, value not conserved, validity interval, missing witness, small and wrong-network outputs, treasury value, registration, delegation, DRep, pool retirement, proposal, vote, size) and one valid transaction through both sockets. In parallel with the query rounds, local ChainSync on both sockets (`scripts/devnet/ntclocal.py`): `--chain-sync-blocks` blocks from Origin (the Scala node reads its immutable store and then its volatile chain), 12 from the tip, 4 as a backlog from a recent block. Last, LocalTxMonitor: snapshots of both mempools walked step for step while bursts of `--monitor-burst` chained transactions go through the Scala socket (which forwards them to the reference node), `cardano-cli conway query tx-mempool info | next-tx | tx-exists` on both sockets at the same time, and `AwaitAcquire` on both | every query that is implemented gives equal bytes in every round (matched / total per query); every `cardano-cli` pair equal; every refusal equal as raw `ApplyTxErr` bytes and as the text `cardano-cli transaction submit` prints; the valid transaction is accepted by the Scala node and reaches a block; every block of local ChainSync equal as the raw item and the same first instruction (a roll-back to the point found); every pair of mempool snapshots with the same slot and transactions has equal raw answers (at least 3 rounds with transactions), the bracketed `tx-mempool` outputs are equal (one with transactions) and `AwaitAcquire` wakes on both. Queries not answered yet are listed with their counts and do not fail the run. Writes `ntc-summary.json` |
| `pool [--seconds 480] [--pool 1\|2\|3] [--keep]` | the Scala node is one of three pools: pool 1's Haskell node is never started and the Scala node forges with its keys; `slotsPerKESPeriod = 250` | blocks forged, adopted and on the final chain; the Haskell ledger's blocks-made count for the pool equals the Scala-forged blocks on the chain; every slot the reference node's `query leadership-schedule --current` gives the pool (asked once per epoch) is either forged or taken by another pool's block, and a missed one is reported with the `forgeSkipped` reason; load transactions in Scala blocks; at least one KES period crossed; no rollback deeper than `k`; Haskell pools agree on the tip; comparisons match; no error event; the forger's own counters (`GET /forging`, per epoch: slots checked, leader slots, forged, adopted, lost slot battles, skips by reason, forge latency) agree with the log and no block took more than five slots from its slot's start to adoption. `--byron-start-offset N` writes the Byron genesis' `startTime` N seconds off the Shelley `systemStart` (the reference's clock follows the Byron one; the check that the Scala node does too). Records the host load; a run whose only misses are busy slots on a host with load above its CPU count exits 2 (inconclusive). Writes `pool-summary.json` (`forgerStats`, `slotClock`, `oracle`, `host`) |

`--scala-cpus N` (`follow`, `soak`, `relay`, `pool`, `e2e`; default 2) sets the CPUs of the Scala node's
container, and `--max-starvation-warnings N` makes a scenario fail when the node logged more cats-effect
CPU-starvation warnings than that (otherwise they are only reported: `cpuStarvationWarnings` in
`e2e-summary.json`, and the `scheduling` block of `pool-summary.json` with the worst timer lateness and
the longest CPU-bound step the node measured). `pool --scala-cpus 1` is the check that the forger's slot
clock holds with one CPU (roadmap D8).

`--oracle` (on `e2e` and `soak`) makes the follower write a state digest at every epoch boundary and ends the run with the db-analyser comparison described under "Ledger state against the Haskell node"; `devnet.py oracle` repeats that comparison on a cluster that is still up.

Building blocks (also runnable on their own): `up` (start the 3-pool cluster), `up-eras [--epoch-length 500 --active-slots-coeff 0.2 --byron-epochs 1 --byron-slot-ms 1000 --stall-seconds 90 --shelley-epochs 1 --min-utxo-value 0 --pool-margin 0 --cross-rewards]`, `capture-eras [--out fixtures/eras --conway-blocks 3]`, `tip [--node N]`, `follow [--seconds N] [--node N]`, `load [--seconds N --wallets 10 --stakers 4 ...]`, `govload [--seconds N]`, `compare`, `down`.

Cluster parameters: the `soak`, `relay` and `pool` clusters come from `cardano-testnet` 11.1.1 defaults
with k = 5, f = 0.05 and 500-slot epochs of 0.1 s (as recorded in the 2026-10-11 runs; the `up-eras`
cluster uses f = 0.2 by default, see [`fixtures/eras/README.md`](../fixtures/eras/README.md)). The genesis
files are rewritten at cluster start, so a cluster is not reproducible from the repository alone;
the committed `fixtures/eras` capture is the Docker-free replay of one `up-eras` run.

Node-to-client evidence: `scripts/devnet/ntcquery.py` also speaks LocalTxSubmission (`submit`) and can
connect to a socket on the host (`host=True`, used for the Scala node's socket directory); the unit
tests of the query envelope, the predicate-failure encoding and the answers against recorded reference
bytes (`fixtures/devnet/lsq`) need no Docker.

Comparison method: `cardano-cli` 11.2.3 refuses `query ledger-state` and `query protocol-state`
before Conway, so `scripts/devnet/ntcquery.py` speaks LocalStateQuery to the reference node directly and
`scripts/devnet/eraview.py` projects `DebugNewEpochState`, the whole UTxO and `DebugChainDepState` onto the
node's `/state` shape. Byron is not compared directly (the CLI cannot query a Byron ledger); the Byron UTxO
is compared through the first Shelley epoch, which starts from it.

`e2e` shares the `up-eras` options but changes four defaults so the pre-Allegra rules have something to
decide: `--shelley-epochs 3` (the pools hold stake from epoch 2, produce blocks from epoch 3 and the
update for those blocks is made and paid under protocol 2 in epochs 4 and 5), `--min-utxo-value 1000000`,
`--pool-margin 0.1` and `--cross-rewards` (pool 1 pays its leader reward to delegator 2, who is also a
member of pool 2, and pool 2 pays delegator 3, who owns and leads pool 3). The plain `up-eras` defaults
are those of the committed `fixtures/eras` (one Shelley epoch, no minimum, margin 0).

Era load (`scripts/devnet/eraload.py`, transactions built and signed by `scripts/devnet/eratx.py` because
cardano-cli 11 builds Conway transactions only): Shelley transfers, stake registration and delegation,
a new pool, pool re-registration, MIR from the reserves, a genesis delegation certificate,
withdrawals; Allegra validity intervals, auxiliary data, a timelock script, pool retirement; Mary
minting and burning under native-script policies, multi-asset transfers, MIR from the treasury;
Alonzo collateral, datum-hash outputs, required signers; Babbage inline datums, reference scripts,
reference inputs, collateral return; Conway the governance load (`govload`) described below.

Boundary load (roadmap B7, B8, B12): outputs of exactly `minUTxOValue` (Shelley, Allegra, Mary) and one
lovelace less, which the reference must refuse; the Mary scaled minimum for one native asset at the
boundary; serialised values of exactly 4000 (Mary) and 5000 (Alonzo) bytes against 4001 and 5001; MIR
from the reserves early, mid-epoch, just before and just after the `3k/f` cut-off (the later one
replaces the earlier entry before Alonzo) and to an unregistered credential; MIR pot transfers and
negative entries refused in Mary and accepted in Alonzo; stake keys deregistered before and after the
reward calculation starts at `4k/f`. A submission that must be refused counts as `unexpected` (and fails
the run) if the reference accepts it or names another reason; the replies are in `loadEvidence` of
`e2e-summary.json`. The reference reward update is projected with the pre-Allegra choice (one reward
per account) when the previous epoch's parameters are protocol 2.
Plutus V1 and V2 scripts are not driven by the devnet load (`eraload.py` TODO, roadmap I3); they are verified by
the Preview sync and the unit and oracle tests only.

`govload` (run by `soak` next to `load`, and in the Conway epochs of `e2e`) drives, one pass per
epoch: wallets; stake registration with pool and DRep delegation and two DReps; a Plutus V3 script
address (inline datum, no datum, reference script) spent and minted; proposals of the info, parameter
change, treasury withdrawal, committee update, constitution, hard-fork and no-confidence kinds with
votes from DReps, pools and the committee; hot-key authorisation, DRep update and retirement,
committee resignation, stake deregistration with refund, and a treasury donation. The genesis
committee of `cardano-testnet` is empty with threshold 0.

`soak --governance-extended` (`govload --extended`) adds `scripts/devnet/govextra.py`, a thread of
the governance load with wallets and keys of its own that acts on the state of the reference chain
(not on epoch numbers). Every transaction is also compared by the ordinary ledger comparison; the
transactions marked "refused" must be refused by the reference with the named predicate failure
(`EraLoad.expect_rejected`), and their bytes are kept in `negative-txs.jsonl` (and the VRF sequence,
accepted and refused, in `vrf-txs.jsonl`) of the run directory for the Scala replay tests
(`DevnetRefusedTxSuite`, fixtures in `fixtures/devnet`). `summary.json` gets a `governanceExtended`
block (stages done, refusals, what the reference's ratification enacted or let expire per epoch).

| Roadmap item | What it drives | Reference outcome that is compared |
|---|---|---|
| B2 default votes | the three pool reward accounts are registered and delegated to always-abstain (pool 1), always-no-confidence (pool 2) and a DRep key (pool 3); a parameter change lowers the pool thresholds to 0.45; then two competing hard forks (pool 3 alone votes for the first, pools 2 and 3 for the second), a no-confidence action no pool votes on, and three committee updates (none voted by pools, one voted by pool 3, one with a long term) | only the second hard fork, the no-confidence action and one committee update are enacted |
| B3 committee | the elected committee holds a member that expires after four epochs; `committeeMinSize` is raised to 2 together with a treasury withdrawal in the same ratification run; after the member expired a withdrawal that every voter accepts is proposed | the parameter change and the first withdrawal are enacted in one run; the second withdrawal is never ratified |
| B4 scripts | a Plutus V3 script as stake credential, DRep (registered, delegated 30 ada of stake, votes with the Voting purpose) and reward account (treasury withdrawal paid in, then withdrawn with the Rewarding purpose) | accounts, DRep state and treasury |
| B5 protocol 11 | reference inputs that are inputs (before 11: refused; at 11: accepted without scripts, refused with a Plutus V3 script); votes of committee hot keys that are authorised but not elected (refused at 11, accepted once elected); ten pool registrations around a VRF key hash another pool holds, or has scheduled | the refusals above |

`e2e` starts the light subset (script credentials, reward accounts, the reference-input refusals and, if protocol 11 is reached, the VRF cases) in its Conway epochs.

Recorded results and the commits they were obtained on are in
[compatibility.md](compatibility.md) (evidence log).
