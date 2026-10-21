# Running the node and the devnet scenarios

**Status: Current usage reference.** Recorded network runs are historical evidence with their
own build and environment identities.

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
(timer lateness and the longest CPU-bound step of that epoch, see "Scheduling" below). Each line has a namespace and a
severity, and the `--log-*` flags send them to stdout, stderr and a rotated file, filtered and sampled by namespace
(see "Logging" below).

Flags, as parsed by `Main.parse` (`node/src/main/scala/xyz/kd5ujc/scarab/node/Main.scala`). Every flag takes a
value except `--forge` and `--dump-state-full`. An unknown flag, a repeated flag (except `--peer`, `--log-severity` and `--log-every`, which accumulate) and a flag without its value are refused with a typed `ConfigError` (M1).

| Flag | Default | Meaning |
|---|---|---|
| `--genesis-dir <dir>` | required unless `--config` | directory with `shelley-genesis.json`, `alonzo-genesis.json`, `conway-genesis.json`, `byron-genesis.json` (needed when the chain starts in Byron) and optionally a node configuration (`configuration.yaml`, `configuration.json` or `config.json`). Every hash that configuration lists is verified. With `--config` it is where the files the configuration names are looked up |
| `--config <config.json>` | off | read the Haskell node's configuration as `cardano-node run` does, see "Configuration and topology files" below |
| `--peer <host>:<port>` | required unless `--topology`, repeatable | an outbound peer: all the `--peer` peers form one local-root group with `hotValency = warmValency =` their number (the governor keeps every one of them hot and reconnects with the reference's backoff; a peer that keeps failing is never the end of the node). See "Peer selection" |
| `--topology <topology.json>` | off | the Haskell node's P2P topology: its local roots (each group with its `hotValency` and `warmValency`), public roots and bootstrap peers, chosen by the peer governor. See "Peer selection" |
| `--listen <host>:<port>` | off | accept node-to-node connections (versions 14 and 15) and serve our chain; on an inbound connection that agreed duplex the node also follows the peer (see `--inbound-sync`) |
| `--max-inbound N` | `64` | most inbound connections at once (G2): the connection manager refuses the next one with a typed error and logs `connection` / `inbound` `refused`; one connection per peer address in either direction |
| `--inbound-sync on\|off` | `on` | run our ChainSync, BlockFetch and KeepAlive clients on inbound duplex connections, so a peer that connected to us is a source of blocks as well as a consumer of ours (G2); `off` only serves them |
| `--header-first on\|off` | `on` | validate the headers a peer announces (envelope, VRF, leader threshold, KES, opcert, nonces) before any of their blocks is fetched (G6); `off` leaves every check to the block, as before. Throughput comparison in [performance.md](performance.md) |
| `--local-socket <path>` | off (`SocketPath` with `--config`) | Unix socket for the node-to-client protocols LocalStateQuery, LocalTxSubmission, LocalTxMonitor and ChainSync of whole blocks (versions 16 to 23); `cardano-cli conway query ...`, `transaction submit` and `query tx-mempool` work against it, see "Node-to-client" below |
| `--seconds N` | run until stopped | stop after N seconds |
| `--query-port P` | `7071` | loopback port of the HTTP query server (`GET /tip`, `GET /state?slot=&hash=`, `GET /scheduling`, `GET /connections`: the connection table and the inbound sessions, `GET /metrics`: the Prometheus text format, see "Metrics") |
| `--metrics-listen <host>:<port>` | off | a listener that serves `GET /metrics` and nothing else, for a scraper that is not on loopback (`0.0.0.0:12798` in a container); see "Metrics" |
| `--db <dir>` | off (in-memory; replay from genesis on every start; `DatabasePath` with `--config`) | persistent chain: `network-identity`, `immutable/`, `volatile/`, `received/`, `snapshots/`, `utxo/` |
| `--snapshot-every <N\|Nslots\|Nepochs>` | the reference node's policy (below) | snapshot once the immutable tip is this far past the previous snapshot; a bare number is slots. Given, it replaces the default policy |
| `--keep-snapshots N` | `2` | snapshots (and UTxO checkpoints) to keep (the reference node keeps 2) |
| `--snapshot-check structure\|full` | `structure` | what a snapshot write proves before the file is kept: `structure`, that it parses (cheap); `full`, that it reads back to the state written (a second ledger state in memory, 0.84 GB on preprod epoch 188). A file that parses but cannot be restored is skipped at the next start, which restores an older snapshot or replays |
| `--utxo-store memory\|rocksdb` | `rocksdb` with `--db`, otherwise `memory` | UTxO set interpreter |
| `--utxo-cache-mb N` | `256` | native-memory budget of the RocksDB store (it holds the UTxO set and the ledger tables): one LRU block cache shared by all tables, holding data, index and filter blocks, to which the memtables are charged, so the store stays near this figure. A smaller cache costs throughput, see [performance.md](performance.md) section 8 |
| `--memory-report-seconds N` | `0` (off) | log a `memory` event every N seconds: heap, non-heap, buffer pools, `/proc/self/status` (`RssAnon`, `RssFile`), the cgroup's `memory.current` and its `anon`/`file`, and the RocksDB properties (block cache usage, memtables). Use it with `-XX:NativeMemoryTracking=summary` and `jcmd <pid> VM.native_memory` to see where memory is |
| `--stall-minutes N` | `10` (`0`: off) | log a `stalled` event, once, when no block has been extended for N minutes while a peer announced a longer chain than ours; `scarab_follower_stalled` is 1 for as long as it lasts and `scarab_follower_stalls_total` counts them, so Grafana can alert on a node that is up but not following. See "Stall watchdog" |
| `--stall-exit-after <Ns\|Nm\|Nh>` | off | end the node when a stall (see `--stall-minutes`) has lasted this long: it logs `stallExit`, closes down like on a signal (the shutdown snapshot is taken) and exits with code 4, so a supervisor that restarts it (Docker `unless-stopped`) brings it back from the newest snapshot. Needs `--stall-minutes` and a longer time than it. See "Stall watchdog" |
| `--churn-interval <Ns\|Nm>` | off (test only) | the churn governor's rounds follow each other this often (with a tenth of it as fuzz) and the waits for connecting and for the known set are at most that long, instead of 3300 s plus up to 600 s in the fetch mode deadline and 900 s plus up to 60 s in bulk sync, with waits of up to 300 s. The `governor` devnet scenario uses it; a real node does not |
| `--read-ahead N` | `128` | Maximum headers in an ordered body-publication chunk. This does not change the ChainSync request window. Historical fixed-batch measurements remain in [performance.md](performance.md), section 7 (D3) |
| `--chain-sync-low N` | `200` | Native rolling ChainSync low watermark; zero is allowed, and it must not exceed the high watermark |
| `--chain-sync-high N` | `300` | Native rolling ChainSync high watermark and maximum outstanding requests; 1 to 65,535 |
| `--plutus-workers N` | min(4, available CPUs), at least 1 | node-wide bound for dependency-batched Plutus transaction jobs; `1` uses exact serial block validation; ledger application stays ordered; see [design](design/parallel-plutus.md) |
| `--cpu-threads N` | one per available CPU | workers of the pool the follower runs on (`scarab-cpu-N`: header handling, chain selection, the rules, persisting), apart from the pool that serves the network, timers and the forger; `0` runs everything on the cats-effect compute pool, as before D8 (still measured) |
| `--dump-state <dir>` | off | write a ledger-state digest (`<slot>-<hash>.json`) at the points of `--dump-state-at`, for the db-analyser comparison below |
| `--dump-state-at <points>` | `epoch-boundaries` | with `--dump-state`: `epoch-boundaries` (the first block of every epoch), `epoch-boundaries/N` (every Nth epoch) or `slot,slot,...` (the first block at or after each slot, what `db-analyser --store-ledger` stores) |
| `--rules`, `--mirror-check`, `--on-divergence`, `--repro-dir` | removed | J2.9 removed the old rules and the differential harness: the era-indexed rules on the Haskell ledger state are the only rules, and the chain's state is the mirror. A command line that names one of these flags is refused with a typed `ConfigError` (`ConfigProblem.RemovedFlag`, exit status 2) that says what to do instead (drop the flag; `--snapshot-check full` is the read-back check of the state) |
| `--dump-state-full` | off | with `--dump-state`: also write the whole projection, UTxO included, as `<slot>-<hash>.full` (drill-down on small states only) |
| `--mempool-bytes N` | none: two blocks of the protocol parameters in force (`MempoolCapacityBytesOverride` with `--config`) | the reference's `MempoolCapacityBytesOverride` in bytes: the capacity is the least whole number of blocks (at least one) that holds N bytes, a block being `maxBlockBodySize - 1024` bytes, `maxBlockExUnits` and 1 MiB of reference scripts, and it follows the parameters; LocalTxMonitor reports the capacity the pool enforces |
| `--plutus-cases <dir>` | off | record self-contained JSON cases of Plutus transactions (used to build `fixtures/preview/plutus`) |
| `--log-stdout`, `--log-stderr`, `--log-file`, `--log-file-format`, `--log-file-size`, `--log-file-keep`, `--log-severity`, `--log-every` | every line as JSON on stdout | the log's sinks, thresholds and sampling, see "Logging" below; `--log-severity` and `--log-every` repeat |
| `--forge` | off | produce blocks; requires `--vrf-skey`, `--kes-skey`, `--opcert` |
| `--vrf-skey <file>` | with `--forge` | `cardano-cli` text envelope `VrfSigningKey_PraosVRF` |
| `--kes-skey <file>` | with `--forge` | `KesSigningKey_ed25519_kes_2^6`: the period-0 key as `cardano-cli` writes it, or the key as the node last wrote it back; the node rewrites this file when the key moves to a new period, so it must be writable (see "Rotating the KES key") |
| `--opcert <file>` | with `--forge` | `NodeOperationalCertificate` (it also carries the cold verification key; the cold signing key is never needed). SIGHUP reads this file and `--kes-skey` again |

Caveats:

- A persistent Scarab writer owns a stable sibling file `.<database name>.scarab-lock` in the
  database's parent. The process needs write access to that parent or to the existing lease file.
  A container running as a non-root user with only `-v <host database>:/db` must also bind the same
  host lease inode at `/.db.scarab-lock`; do not create an unrelated container-local lease. For example:

  ```sh
  DB=/absolute/path/to/db
  LEASE="$(dirname "$DB")/.$(basename "$DB").scarab-lock"
  touch "$LEASE"
  docker run --rm --user "$(id -u):$(id -g)" \
    -v "$DB":/db -v "$LEASE":/.db.scarab-lock ... follow --db /db ...
  ```

  Mounting a writable parent instead (for example `/data` with `--db /data/db`) also works. Keep the
  lease file in place after the process exits: unlinking it can split ownership between two inodes.
  This coordinates new Scarab followers and import commands, not older Scarab or Haskell writers.

- A `--db` records the network it was created for (`<db>/network-identity`). Format V6 binds the
  protocol magic, Byron and Shelley genesis hashes, and BLAKE2b-256 of the exact loaded Alonzo and
  Conway genesis bytes. A mismatch refuses to open with `NetworkMismatch`. V1/V2 identity records
  lack the Alonzo and Conway bindings and are never upgraded automatically. A populated legacy
  identity or database without an identity requires `--adopt-legacy-network-identity`; the original
  legacy identity bytes are retained beside the new file, and the recorded operator confirmation is
  not historical proof. Before adoption, the node checks every known identity field, the first
  immutable block when available, all snapshot headers, and immutable format/index headers. Any
  refusal happens before RocksDB, recovery or format migration. A pre-identity Shelley-start database
  cannot be historically distinguished from another network with the same first-block predecessor.
  The devnet rewrites its genesis at every cluster start, so a `--db` of an earlier cluster is refused too.
- A database written before the rename (format 1: `cardano-scala-lab ...` identifiers, index magic
  `CSLIDX01`) is migrated to format 2 the first time it opens: every chunk index and the
  `format` file are rewritten one by one (temporary file, fsync, rename), `formatMigrated` events say what,
  and the second open does nothing. A crash in the middle is harmless, the next open finishes. The
  migration is one way and an older build cannot open the result: preserve a consistent database copy
  before first open if rollback matters. The identity file is separately backed up during explicit
  legacy adoption.
  A database of a format newer than the build refuses to open (`UnknownVersion`) and nothing is rewritten.
- Native ledger snapshots remain format 5 (`SCRBSNP5`, Haskell ledger CBOR plus a typed sidecar,
  framing and checksum). Snapshot payload formats 1–4 are known retired identifiers, refused with
  `RetiredVersion`; an unknown identifier of the family is `UnknownVersion`. Database ownership,
  read-only identity verification and snapshot preflight happen before opening RocksDB, migrating
  identity or recovering immutable/volatile stores. The old readers and one-shot conversions are
  recoverable from pinned historical source; they are not retained in the runtime. Preserve the
  original database and convert a separate copy with the matching historical build before opening
  it. A pre-idle-pools fingerprint also needs its exact historical schema, not a guessed decoder.
  [Storage migrations](storage-migrations.md) describes backup, inventory, verification and the
  local input requirements for retirement and the separate deployment transition. An existing older
  producer stays on its compatible pinned binary until its final database copy is converted and
  verified for an explicitly approved upgrade (#37/#115); this source change does not restart it.
  Do not delete snapshots to force replay as an upgrade procedure.
- A format-5 sidecar holds the mirror state without the old rules' flat bookkeeping. Historical
  conversions preserve the source gaps and observed eras rather than claiming information the old
  state lacked. Live states retain these gaps and observed era starts through rollback and V5
  restore/rewrite (#129). Missing likelihood history remains marked; arbitrary imported delegator
  counts need three observed snapshot rotations, with the proof conservatively restarting after
  restore. An old Byron-start snapshot before Allegra can lack the redeem stash. Keep the source
  and matching checkpoint backup. Operational preprod/mainnet upgrades remain separate; see
  [the provenance upgrade procedure](storage-migrations.md#per-state-provenance-upgrades-129).
- **The UTxO checkpoints beside the snapshots** (`<db>/utxo/checkpoints/<snapshot stem>`) are RocksDB
  directories whose key is `txid ++ index` as a big-endian u16 (34 bytes, the Haskell node's) since K6
  (2026-10-14). Older checkpoints used `txid ++ u32` (36 bytes). The independent checkpoint-key
  converter can restore that layout into `<db>/utxo/live` under current keys without modifying the
  checkpoint; it does not make retired snapshot payloads readable. A usable current-format snapshot
  and its matching checkpoint are still required. A mixed layout, an output index above 65,535 or a
  key of another length is refused with `StorageError.UtxoCheckpoint`.
  Preserve the database and its matching snapshot/checkpoint pairs before investigating a refusal;
  follow [the storage migration procedure](storage-migrations.md) to convert or recover a separate
  copy. Replaying from immutable chunks is an operator choice, not an upgrade step that requires
  deleting the only recoverable state. Historical #145 involved a bad restore producing mixed key
  layouts; current restoration rejects that condition rather than silently using it.
- The volatile blocks (the last `k`) are kept in `<db>/volatile/` and applied again on start
  (`volatileResumed`), and a graceful stop (SIGTERM, SIGINT, `--seconds`) writes a snapshot at the
  immutable tip (`shutdownSnapshot`): the next start replays no immutable block when that snapshot
  is usable, reapplies the retained volatile log through the rules, and continues at the
  previous tip. After `kill -9` the node resumes from the newest periodic snapshot, replays the stored
  blocks after it, and takes the volatile chain from the log as far as the log reached the disk.
  Run the node so that the signal reaches the JVM (`docker stop`, not a shell wrapper that eats it).
- **When a snapshot is taken** (`SnapshotPolicy`, the Haskell node's default policy: ouroboros-consensus 4.2.0.0,
  `Storage/LedgerDB/Snapshots.hs` `defaultSnapshotPolicy` and `mithrilSnapshotPolicyArgs`, with `NumOfDiskSnapshots 2`):
  when the immutable tip passes a multiple of `40 * k` slots (86,400 on mainnet and Preprod, a day on mainnet) and the
  previous snapshot was taken at least 10 minutes ago, so a replay or a sync far behind the tip writes at most one
  snapshot per 10 minutes (each costs about 50 s of background CPU on mainnet) and a node at the tip writes one a day;
  a crossing that falls inside the 10 minutes is skipped. Two snapshots are kept. `--snapshot-every` replaces the
  policy by "once the tip is this far past the previous snapshot" with no time limit (`--snapshot-every 1epochs` is
  the earlier default), and `--keep-snapshots` the number kept. The snapshot at a graceful stop is independent of
  both. Details and numbers: [performance.md](performance.md) section 13.

**Checking a database before a node is moved to this build.** First inventory its snapshot versions.
This procedure requires verified current V5 snapshots and their associated checkpoints. V1–V4 inputs
need separate verified current-format replacements using pinned historical source, as described in
[Storage migrations](storage-migrations.md); this build refuses retired formats before mutable stores
open. Work on a copy; the original is never opened by the new build. With the node stopped (a running
node's last chunk and volatile files are moving), `D` the database and `C` the copy, on the same file
system as `D`:

```sh
mkdir -p "$C/volatile"
cp -a "$D/network-identity" "$D/snapshots" "$D/utxo" "$C/"     # snapshots and every checkpoint
cp -al "$D/immutable" "$C/immutable"                           # hard links: the chunks cost no space
# the build rewrites the index files and may extend the newest chunk: those must be real copies
newest=$(ls "$D/immutable" | grep chunk | sort | tail -n 2 | sed 's/\.chunk//')
for c in $newest; do rm "$C/immutable/$c".{chunk,index}; cp "$D/immutable/$c".{chunk,index} "$C/immutable/"; done
rm "$C/immutable/format"; cp "$D/immutable/format" "$C/immutable/format"
```

The space needed is that of `du -sh "$D/snapshots" "$D/utxo"` (`cp -a` keeps the hard links between the
checkpoints' files, so it is what `du` counts once) plus, during the run, a snapshot and a checkpoint's new
files (the live store is linked from the checkpoint, and the first snapshot written adds a file the size of
one snapshot). Then run the build to be checked on the copy with the flags of the node, in a container the
way the node runs (the replay takes place before any peer is contacted, so a peer that is never reached
does not matter), and stop it when enough blocks have gone through:

```sh
LEASE="$(dirname "$C")/.$(basename "$C").scarab-lock"
touch "$LEASE"
docker run -d --name check-old-db -v "$C":/db -v "$LEASE":/.db.scarab-lock ... \
  follow --genesis-dir /config --peer <relay> --db /db --keep-snapshots 2000 > node.log
until [ "$(grep -c '"event":"replayProgress"' node.log)" -ge 5 ] || grep -q '"event":"error"' node.log; do sleep 10; done
docker stop -t 120 check-old-db                                 # a graceful stop writes a snapshot
grep -E '"event":"(formatMigrated|snapshotRestored|replayStarted|replayProgress|snapshotRefused|snapshotSkipped|error|shutdownSnapshot)"' node.log | cut -c1-300
```

What shows the current-format database opens: `snapshotRestored` with the `anchor` of the newest
snapshot; `replayStarted` from that anchor; `replayProgress` lines whose `tip` moves past it, with
`replayed` growing by the progress interval each time and no `"event":"error"` (a `ValidationError ... BadInputsUTxO` right after the restore is this
bug); no `snapshotRefused`, no `snapshotSkipped`, no `UtxoCheckpoint` error; and, after the stop, a
`snapshotWritten`/`shutdownSnapshot` at a block past the restored anchor. A replay from the newest
snapshot of a mainnet follower that is a day behind covers some thousands of blocks, so
`replayFinished` appears; for a database whose newest snapshot is older, five progress lines (the
`replayed` counts) are enough. Remove the copy afterwards.

Historical builds that supported V4→V5 migration also emitted `formatMigrated` with
`"kind":"SnapshotFile"`, `"from":"V4"`, `"to":"V5"` and the number of migrated files. That is
historical conversion evidence, not an automatic upgrade supported by this build.

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
| `DijkstraGenesisFile`/`Hash` | the hash is verified when the file is present; the Dijkstra era is not implemented |
| `CheckpointsFile`/`Hash` | a configured file is required; its byte hash is verified before parsing. Unique unsigned block numbers map to 32-byte header hashes, enforced during header and block validation across eras |
| `TestShelleyHardForkAtEpoch` ... `TestConwayHardForkAtEpoch` | the first epoch of each era (`0` for all of them: the chain starts in that era, as Preview); absent for Shelley: the Byron ledger decides |
| `RequiresNetworkMagic`, `PBftSignatureThreshold` | the Byron address magic and the PBFT signing threshold |
| `LastKnownBlockVersion-Major/Minor/Alt`, `MaxKnownMajorProtocolVersion`, `Protocol` (must be `Cardano`), `ExperimentalHardForksEnabled`, `ConsensusMode`, `DiffusionMode` | read, validated and logged (`started`). `GenesisMode` integrates GSM, LoE/GDD, LoP, ChainSync jumping, historicity and devoted BlockFetch across outbound and inbound duplex clients; full canonical header validation applies even with header-first disabled. It refuses bootstrap peers and checks peer snapshots against the immutable point. Praos remains the default. Experimental hard forks (the Dijkstra era) and `Test<Era>HardForkAtVersion` remain unsupported |
| `LowLevelGenesisOptions` | the reference's `EnableCSJ`, `EnableLoEAndGDD`, `EnableLoP`, `BlockFetchGracePeriod`, `BucketCapacity`, `BucketRate`, `CSJJumpSize` and `GDDRateLimit`; [defaults and resource policy](design/genesis.md#mechanisms-and-resource-policy) follow the pinned production configuration |
| `PeerSharing` | used by both handshakes and the governor; absent means on for a relay and off with `--forge`, an explicit Boolean wins. Both peers must enable it. Eligible connected peers are asked below the known target; only advertised, previously connected peers with no failures are shared (G1.4) |
| `ExperimentalProtocolsEnabled` (also `TestEnableDevelopmentNetworkProtocols`, which must agree) | the highest node-to-node version the node offers and accepts (G4): absent or `false`, **15**, the latest release of cardano-node 11.1.3 (14 and 15 are offered, as the reference does, `Cardano/Node.hs` 456 and `limitToLatestReleasedVersion`); `true`, **16**, which adds the Peras flag to the version data (always sent as false: the node has no Peras mini-protocols). A relay or pool of the reference needs the same key to agree on 16. The public Leios testnet sets it; its relay accepts 14 to 16. Node-to-client versions 16 to 23 are always accepted. The devnet `relay` scenario takes `--max-ntn-version 16` to turn the key on for the Scala node and the Haskell relay |
| `SocketPath`, `DatabasePath`, `MempoolCapacityBytesOverride` (a byte count; `NoOverride` is the default of two blocks) | the defaults of `--local-socket`, `--db` and `--mempool-bytes`, with `--config` only; a flag wins |
| `MaxConcurrencyDeadline` | nonnegative target active fetching peers in deadline mode (default 1); preferred-peer handover can temporarily exceed it |
| `MaxConcurrencyBulkSync` | corresponding nonnegative bulk-sync fetching-peer target (default 1) |
| `TargetNumberOfRootPeers`, `TargetNumberOfKnownPeers`, `TargetNumberOfEstablishedPeers`, `TargetNumberOfActivePeers`, and the three `...BigLedgerPeers` | the targets of the peer governor (see "Peer selection"); absent keys leave the reference defaults |
| everything else (`TraceOptions`, `LedgerDB`, ...) | ignored |

The topology needs `localRoots` and `publicRoots`; `bootstrapPeers` (a list or `null`), `useLedgerAfterSlot`
(above 0: after that slot, `0`: always, below: never) and `peerSnapshotFile` are optional. An access point with a
port is `{"address": <name or IP>, "port": <number>}`; a DNS name without a port is an SRV relay resolved through
`_cardano._tcp.<domain>`. The unspecified address, a port that is not a number, the legacy `Producers` format, and an empty
`bootstrapPeers` with no trustable local root are `ConfigError`s. Bootstrap peers are refused in GenesisMode.
`useLedgerAfterSlot` and `peerSnapshotFile` are acted on (ledger peers and big ledger peers, below); `advertise`,
`trustable` and the valencies feed peer selection and peer sharing; `behindFirewall` is retained in topology metadata.
SIGHUP reloads the topology. A malformed replacement leaves the active settings running; a valid reload preserves
original CLI roots and installs the new runtime/governor roots at an acknowledged generation boundary.

- **`useLedgerAfterSlot`.** A number above 0: take peers from the ledger once the ledger's tip is at or past that slot;
  `0`: always; below 0 or absent: never (`UseLedgerPeers`). The official Preview file has 119231973.
- **`peerSnapshotFile`.** The big ledger peer snapshot, relative to the topology file's directory, read at startup and
  when topology is reloaded while ledger peers are on (`updateLedgerPeerSnapshot`, `cardano-node` `Run.hs`): a file
  that cannot be read is ignored with a `ledgerPeers` event `snapshotIgnored`; a legacy (version 1, 2) file, a snapshot at the origin, of another network
  (`NetworkMagic`) or older than `useLedgerAfterSlot` stops the node with a `ConfigError`. The snapshot is used for the
  stake maps only while it is not older than the ledger's tip: the official Preview file is at slot 123.6 M, so a node whose
  ledger is further ahead takes its peers from the ledger (event `ledgerPeers`, `source`). In GenesisMode, when the
  judgement enters `TooOld`, the monitor waits cancelably for the exact immutable slot and checks both slot and hash;
  it emits `snapshotVerified` with the nonfatal match verdict. A storage failure remains fatal.

### Peer selection

The outbound peers are chosen by the peer governor (roadmap G1.1, [design](design/peer-selection.md),
[ADR 0016](adr/0016-peer-states-and-the-governor.md)), which follows the reference
(`Ouroboros.Network.PeerSelection.Governor`) and runs for the life of the node; there is no switch to turn it off.

- **Peers.** Local roots are the `localRoots` groups of the topology and the `--peer` peers (one group of their own,
  `hotValency = warmValency =` their number). A group is kept at its `warmValency` established and its `hotValency` hot
  peers; the governor connects, activates, demotes and reconnects to hold that. The `bootstrapPeers` of the topology
  are used while the node is behind (below); the public roots are asked for when fewer peers are known than
  `TargetNumberOfRootPeers`. A peer that is a local root or a bootstrap peer is never forgotten.
- **Temperatures.** Cold is known, warm is connected with KeepAlive and negotiated PeerSharing, hot also runs ChainSync, BlockFetch and
  TxSubmission2; the transitions in between are states of their own. A warm peer is promoted to hot, and a hot peer is
  demoted, by starting and ending the hot protocols on the same connection; the blocks a hot session claimed are
  released when it ends.
- **Targets.** The relay defaults of the reference (`TargetNumberOfRootPeers` 60, `KnownPeers` 150, `EstablishedPeers` 30,
  `ActivePeers` 20, and for big ledger peers 15, 10, 5), read from the configuration file by these keys
  (`TargetNumberOfRootPeers`, `TargetNumberOfKnownPeers`, `TargetNumberOfEstablishedPeers`, `TargetNumberOfActivePeers`,
  `TargetNumberOfKnownBigLedgerPeers`, `TargetNumberOfEstablishedBigLedgerPeers`, `TargetNumberOfActiveBigLedgerPeers`); a set
  that breaks the reference's sanity rule (active <= established <= known, root <= known, and at most 100 active, 1000 established, 10000 known) is a `ConfigError`. The big ledger targets apply when `useLedgerAfterSlot` enables ledger peers; without them they are zero (there is nothing to draw from).
- **Ledger peers and big ledger peers** (roadmap G1.3). A *ledger peer* is a relay registered by a stake pool; the node reads
  them from the pool distribution of its ledger state (`nesPd`, with each pool's registered relays followed by the relays of
  its pending re-registration), once the ledger is at the `useLedgerAfterSlot` slot, and draws them in proportion to the
  square root of the pool's stake. The *big ledger peers* are the relays of the pools that hold the first 90% of the stake,
  drawn in proportion to the stake itself, and are a population of their own in every set: their targets (15 known, 10
  established, 5 active) and counters are separate, the ordinary counters leave them out, and the ordinary decisions do not
  touch them. The stake maps are rebuilt every 1847 s (30 s while empty). A relay that is a name is resolved when the
  connection is made, one that is an SRV name is not used (design D9), a pool whose relays do not decode is skipped with a
  `ledgerPeers` event `poolSkipped`. Requests for big ledger peers and the promotions of big ledger peers to warm and hot are
  off in the sensitive state; when the ledger turns `YoungEnough` the bootstrap peers that are not connected are forgotten
  and the ordinary and big ledger peers are asked for at once. The gauges `cardano_node_metrics_peerSelection_*BigLedger*`,
  `scarab_peer_selection_peers{origin}` (where the known peers came from), `scarab_peer_selection_targets{target}` (also the
  three big ledger ones) and `scarab_ledger_peers_pools{map}` tell what the governor holds; `GET /connections` gives each
  peer's `origin` (`Local`, `Public`, `Bootstrap`, `Ledger`, `BigLedger`, `Other`).
- **Peer sharing** (G1.4, mini-protocol 10). Below the known target, the governor chooses inbound peers or
  asks established peers that negotiated sharing, outside the bootstrap-sensitive state. It waits 300 s after
  connection, retries after 900 s (3600 s on request failure), and has at most two requests in flight. Fast
  answers are published as one batch within 3 s; stragglers have 10 s overall. Known and big-ledger peers
  are removed from answers and each batch stays within its original discovery objective. Discovered peers
  are advertised by default; configured local/public roots retain their `advertise` setting (`--peer` roots
  are not advertised). The server shares only advertised peers with a successful connection and zero
  failures, returns at most `min(requested,10)`, and holds its ordering salt for 823 s. IPv4 words are
  converted from the reference's little-endian host convention; IPv6 words stay in network order.
- **Peer metrics and churn.** The feedback from the chain (roadmap G1.2). Outbound hot sessions and
  inbound duplex client sessions share one metrics board, keyed by the remote socket address.
  Accepted headers retain their arrival timestamp; invalid headers do not earn usefulness credit.
  Each client session reports the headers and blocks it delivers; two boards of 180 slots keep, per slot, the peer that was *first* (`PeerMetrics`, the reference's
  `PeerMetric`: the earliest arrival wins, the join-time correction `adjustAvg` is zero in the pinned release and is not
  implemented). When the governor must demote hot peers it drops those with the lowest `upstreamness + fetchynessBlocks`,
  ties by the slot at which the peer joined, then at random. The churn governor (`ChurnLoop`, the reference's
  `peerChurnGovernor`) is the one writer of the targets: 3 s after start the root target is released (a head start for the
  local roots), then it sleeps 3300 s plus up to 600 s (fetch mode deadline) or 900 s plus up to 60 s (bulk sync) and runs a
  round. A round changes one target at a time, in the reference's order, and waits for the counters or a timeout: it lowers
  the active target by 20% (at least one), so that the governor demotes the least useful hot peers, restores it so that
  others are promoted, then does the same for the established and the known sets (steps 7 to 12; steps 1 to 6 do the same for
  the big ledger peers, and are part of a round only when ledger peers are on: otherwise their counters are zero and each
  increase would wait out its timeout). While the node is behind with bootstrap peers or in the other sync regime
  the active targets follow the local roots' valency (`Regime`, design section 1.10). A change of regime during the sleep
  asks for the new regime's targets at once. The log events `peerSelectionChurn` and the `scarab_peer_selection_churn_*`
  metrics tell the rounds.
- **How long a demotion takes.** A hot peer is deactivated by ending its protocols cleanly, which waits for the peer's next
  blocking TxSubmission2 request and for the ChainSync reply in flight: on the devnet about a minute, at most the 300 s of
  `deactivateTimeout`; then the connection is closed. A churn round's active decrease therefore takes that long.
- **Fetch mode and BlockFetch.** Bulk sync suppresses duplicate in-flight blocks; deadline mode
  can fetch the same block through different admitted peers and uses the first delivery.
  `MaxConcurrencyBulkSync` and `MaxConcurrencyDeadline` are global fetching-peer targets,
  both defaulting to 1. The pinned preferred-peer handover exception can temporarily exceed
  a target while old requests drain. Per-peer GSV byte windows and a ten-request pipeline
  regulate outstanding work. Inbound duplex clients and recovery share the same coordinator.
  See the [as-built contract](design/block-fetch-coordination.md).
- **Failures.** A protocol violation, an invalid header or an oversize message makes the peer forgotten (unless it is a local
  root or a bootstrap peer); a network failure, a timeout or a refused connection is backed off: 5 s doubling to 160 s
  (plus or minus 2 s) for connecting, 10 s (plus or minus 2 s) before a peer whose activation failed is activated again. One function decides, `FailurePolicy.classify`
  (owner decision, design section 8a). The node no longer stops after 30 failed attempts.
- **Bootstrap peers and the sensitive state.** The Genesis State Machine (`Ouroboros.Consensus.Node.GSM`: PreSyncing,
  Syncing, CaughtUp, the `caught_up.marker` file in the database directory, a caught-up node falls back after 20 to 25
  minutes without a block) says whether the ledger is `YoungEnough` or `TooOld`. While it is `TooOld` and the topology
  has `bootstrapPeers`, the node is in the sensitive state: targets drop to the bootstrap regime, only bootstrap peers
  are kept, local roots that are not trustable are set aside, and it must reach the clean state (no other peer
  connected) within 15 minutes or the node stops (`BootstrapPeersCriticalTimeout`). It also sets the fetch mode
  (`BulkSync` while behind, `Deadline` when caught up; logged as `fetchModeChanged`, gauge `scarab_fetch_mode`).
- **Where to look.** The log events `peerSelectionTargets`, `peerSelectionRoots`, `peerSelectionPromote`,
  `peerSelectionDemote`, `peerSelectionForget`, `peerSelectionSensitive`, `peerSelectionCounters`, `peerSelectionChurn`, `ledgerPeers`, `outboundConnections`,
  `gsmTransition` and `fetchModeChanged` (table in "Logging"); the gauges of "Peer selection (G1)" in "Metrics";
  `GET /connections` has a `peerSelection` object next to the connection table: `counters` (cold, warm, hot, known, root),
  `targets`, `judgement`, `sensitive` and each `peer` with its `temperature`, `origin`, `failCount`, `advertise`, `everConnected` and negotiated `peerSharing`.
- **Genesis.** In `GenesisMode`, LoE limits adoption while GDD compares validated candidates, LoP
  bounds withholding, and CSJ shares header work. Devoted fetching uses actual selection starvation
  and preserves outstanding request accounting during handover. Received original bodies,
  including postponed forks, are retained in a separate `received/` journal across restart.
  Normal ChainSync returns drain hot protocols and keep the bearer warm for the reference's
  120- or 180-second repromotion delay. [Design and acceptance](design/genesis.md).
  Topology reload, SRV/TTL resolution and duplex outbound service are implemented in [#142](https://github.com/KD5UJC-XYZ/scarab/issues/142).
  The Genesis LocalRootsOnly relay on `3819a43af` passed sync, duplex following and transaction
  inclusion (2026-10-21); earlier failed relay runs remain historical evidence. See the
  [compatibility evidence](compatibility.md).

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

### `import-mithril`: certified bootstrap into a fresh database

Build the reviewed Linux amd64 verifier with Docker (an existing clean pinned upstream clone can
be supplied with `--source`; otherwise the script fetches it):

```sh
python3 scripts/prepare_mithril_client.py --out /tmp/scarab/mithril-client
```

The script verifies source, Cargo lockfile, immutable Docker image, integration patch and resulting
executable digest. Mount that executable, configuration and two trusted verification-key files into
the JDK container. Mount the destination's parent writable so its sibling ownership lease and staging
directory share the same filesystem. Then run inside the container:

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main import-mithril \
  --artifact <exact lowercase artifact hash> --aggregator <https aggregator URL> \
  --genesis-verification-key <trusted genesis.vkey> \
  --ancillary-verification-key <trusted ancillary.vkey> \
  --mithril-client <pinned executable> --db <fresh database> --config <config.json>
```

`--genesis-dir <directory>` can replace `--config`. The destination must not exist. The default is
an official full download including ancillary files; `--source-db <Haskell database>` instead copies
an offline cache into private staging and runs full certification verification there. Metadata and
certificate retrieval still use the configured aggregator. An isolated loopback HTTP aggregator is
accepted for recorded offline fixtures; public endpoints require HTTPS. No implicit `latest`, range,
overwrite or missing-file acceptance is available. `--timeout-seconds N` bounds the verifier process
(default 1,800 seconds).

Certification and ancillary trust are separate: the genesis key verifies the Mithril certificate
chain and immutable proof; the ancillary key authenticates the ledger snapshot and unfinished trio.
This command requires both. The generic snapshot command below retains the explicit unauthenticated
opt-out. The bootstrap records these distinctions in `mithril-bootstrap.json` and refuses certificate
network or artifact substitution before ledger import. Byron and non-Mem snapshots are refused.

Successful JSON `importMithril` output names the artifact and ledger anchor. A subsequent ordinary
`follow --db <database>` restores its checkpoint and replays recorded or newly received blocks.
Failure leaves staging subject to best-effort cleanup and never overwrites an existing database;
a failed parent sync with denied rollback may retain the complete owned destination. Inspect the
reported error before retrying. [The design](design/mithril-bootstrap.md) records exact proof scope,
executable provenance, typed refusals and acceptance evidence.

### `import-ledger-snapshot`: start from a reference ledger snapshot

Run offline in the JDK container, with the extracted ancillary archive and configuration mounted
read-only, and the destination database and its sibling ownership lease mounted writable (as above):

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main import-ledger-snapshot \
  --haskell-ledger <archive>/ledger/<slot> --db <database> --config <config.json> \
  --ancillary-verification-key <trusted-ancillary.vkey>
```

First import the immutable blocks with `import-haskell-db` (above). The destination must contain the
configured network identity and the exact snapshot point in its current-format immutable chunks,
and no existing `utxo/`, `snapshots/`, `volatile/` or `ledger/` artifacts. Copy a database if its node
is running. The new follower and both import commands share an ownership lease; older Scarab builds
and Haskell nodes do not participate, so keep their inputs offline too.

Verification is required by default. Supply the network's trusted Mithril ancillary verification key
in its native hex-encoded JSON format. The default manifest is `<archive>/ancillary_manifest.json`;
`--ancillary-manifest <file>` overrides its location. All listed files, including the unfinished
immutable chunk trio, must be extracted and match their digests. `--no-verify-ancillary` explicitly
accepts an unauthenticated ledger instead; the result records `ancillaryVerified: false`.

The command checks metadata, era, exact immutable block and recomputed instant stake, builds a fresh
RocksDB checkpoint and V5 snapshot in staging, and publishes only after validation. It never changes
existing immutable files or overwrites ledger artifacts. JSON `importLedgerSnapshot` events report
`done` with slot/hash, era, output count, lovelace and trust, or a typed `error`. A following `follow`
restores that checkpoint and replays local blocks after the snapshot. Byron and non-Mem snapshots
are refused. Import/export details and recovery of an interrupted staged import are in
[the accepted design](design/haskell-snapshots.md#10-stage-3-as-built-offline-import); export and
cross-node acceptance are recorded in Step D S4/S5 (#140).

### `export-ledger-snapshot`: write a reference ledger directory

Run offline in the JDK container, with the source database readable and the output directory
writable. The source's sibling ownership lease must be writable too (see the Docker lease
example above); source database files can remain read-only.

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main export-ledger-snapshot \
  --db <database> --out <ledger directory> [--slot N]
```

The default is the newest snapshot; `--slot` selects an exact stored snapshot slot, an unsigned
64-bit number. The command checks the existing V5 file's checksum and schema, preserves its
Haskell state bytes, and reads its matching checkpoint without recovery or migration. A state
with recorded gaps, a Byron state, an unsettled checkpoint or a missing selection is refused.
This means an imported reference state can be exported; a state whose recorded gaps have not
been resolved cannot be exported by inventing those fields.

The fresh directory `<ledger directory>/<slot>_scarab` contains `state`, `tables` and `meta`.
The suffix prevents the Haskell node's temporary-snapshot policy from deleting it. The command
streams sorted MemPack outputs, closes and forces staged files, then publishes the directory
atomically. An existing destination is refused, and the output must be outside the source
database. Failure or cancellation removes staging. JSON `exportLedgerSnapshot` events report
`done` with the point, output count and directory, or a typed `error`.

Reference replay and epoch-boundary acceptance are recorded in #140; the command alone does not prove
that acceptance. It preserves V5 and the node's stored CBOR values.

### `export-scls` and `import-scls`: canonical ledger-state interchange

Run these offline in the JDK container, as with the Haskell snapshot tools above. Both require a
complete Conway native snapshot/checkpoint, external writable scratch, the exact block reference,
and local configuration/genesis inputs for the complete network identity. The supported pinned
SCLS profile and lossy fields are described in [the design](design/scls-interchange.md).

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main export-scls \
  --db <complete base DB> --scratch <external scratch dir> --genesis-dir <genesis dir> \
  --slot <slot> --block-no <block number> --block-hash <64 hex digits> --out <new file>
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main import-scls \
  --db <complete base DB> --scratch <external scratch dir> --genesis-dir <genesis dir> \
  --slot <slot> --block-no <block number> --block-hash <64 hex digits> \
  --input <SCLS file> --target-db <separate DB with immutable blocks>
```

`--config <file>` can supply the node configuration; `--genesis-dir` is optional with it and supplies
an explicit genesis override when both are given. The record and per-entry CBOR budget defaults to
16 MiB; `--record-bytes` accepts 4 KiB through 256 MiB. The independent decoded-item ceiling
remains 100,000 at every byte setting. Export takes an optional `--comment` and
uses fixed local diagnostic metadata. The output parent must already exist and support hard links;
an existing file is refused. Output and all scratch must resolve outside the source DB.

SCLS omits restart and consensus information. Import therefore compares every namespace commitment
with the explicit same-point complete base, preserves unrepresented fields, validates the new native
files, and publishes only after the target's full identity and immutable block match. The target
must have no existing UTxO, snapshots, volatile or ledger artifacts. This is interchange against an
explicit base, not standalone bootstrap. Both commands retain V5 restart snapshots, stored CBOR,
and the existing Haskell import/export tools. JSON events report the point/count/path or typed error.

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
blocks are forged. Keep key files outside the repository and readable only by the node's user. The KES
key file must be writable by the node (mode 0600, in a directory it can write): the node writes the evolved
key back (see below); the VRF key and the certificate can stay read-only. See
[architecture.md](architecture.md) for the forging design and [compatibility.md](compatibility.md) for what
is not covered (no KES agent, never run on a public network).

#### Rotating the KES key (H2a, [#116](https://github.com/KD5UJC-XYZ/scarab/issues/116))

A KES key signs for at most `maxKESEvolutions` periods (62 on mainnet, one period is `slotsPerKESPeriod` slots,
36 hours on mainnet), and so does the operational certificate that names it. A pool that is to run unattended
has to change both before they run out. The node does four things for that; a driver script does the rest.

- **Forward security.** The Haskell node never writes its KES key: it reads the period-0 file at every start
  and evolves it in memory (`Ouroboros.Consensus.Protocol.Ledger.HotKey.evolveKey`, which calls
  `forgetSignKeyKES` on the old key: `HotKey.hs` lines 385 to 436 in ouroboros-consensus-protocol at the tag
  `release-ouroboros-consensus-4.2.1.0`, the release series that cardano-node 11.1.3 requires, `^>= 4.2.0.1`
  in `cardano-node.cabal` line 179), so the period-0 key stays on
  disk for ever. Scarab retains a different file-backed policy ([ADR 0015](adr/0015-kes-key-written-back-and-checked-reload.md)): when the key moves to a new period, at the first slot of the period
  whether or not the pool leads in it, the new key is written back to the `--kes-skey` file and the earlier key
  is overwritten, before the key signs in the new period. The file stays a `cardano-cli` envelope: the period
  is read off the key itself (the erased seeds of the tree spell it), so there is no new format and no
  migration. A restart needs no earlier key than the live file contains. This reduces the state available from
  a later read of that path if earlier copies have not been retained; it does not establish complete
  past-key erasure. The replacement protocol writes every byte to `<file>.next`,
  forces the file, decodes it again to check the key structure, verification key, period and serialized bytes,
  then forces its directory entry before it erases any predecessor byte. It overwrites the predecessor fully,
  forces that file, renames `.next` over the file atomically and forces the directory again. Recovery applies
  the same readback and pre-erasure directory force; a valid `.next` is retained and retried if rename fails,
  and a retry after a post-rename sync failure forces the main directory entry again. Thus process-crash recovery
  has a complete replacement after predecessor erasure. Power-loss durability depends on the filesystem,
  mount, device and storage controller honoring file and directory force and same-filesystem atomic rename;
  unsupported or failed directory force is surfaced as a typed persistence failure. Injected tests do not
  reproduce physical power loss. If persistence fails the node reports `kesPersistFailed`, counts
  `scarab_kes_persist_failures_total`, prevents further signing and stops through its typed fatal
  policy: storage failure exits 5, an internal durability failure exits 6, and a defect exits 7.
  Correct the cause and restart; the running process does not retry persistence or resume signing. A
  file system that keeps old blocks (copy-on-write, journalling) may keep the overwritten key; overwriting is
  not physical secure erasure. Use an encrypted filesystem and restrict key-directory access;
  encryption does not block plaintext reads of a mounted volume. The owner accepts this file-backed
  policy for now. The separate upstream KES agent process is tracked in [H2 / #38](https://github.com/KD5UJC-XYZ/scarab/issues/38). The key signs only after the lock that also guards a
  reload is held, so a block is never signed with a key and certificate of two different reloads, and the key
  only moves forward, so it never signs one period with two states.
- **Reload on SIGHUP.** `kill -HUP` (or `docker kill -s HUP`) makes the node read the KES key and the
  certificate again, as the Haskell node does: `Cardano.Node.Run.installSigHUPHandler` (cardano-node 11.1.3,
  `cardano-node/src/Cardano/Node/Run.hs` line 596) runs `updateBlockForging` (line 626), which reads the
  credentials with `mkConsensusProtocol` (line 627) and hands them to `setBlockForging` (line 648; the
  consensus side is `NodeKernel.setBlockForging` and `blockForgingController`, ouroboros-consensus-diffusion
  `NodeKernel.hs` lines 194, 416 and 428 at the same tag). The Haskell node checks that the KES key
  is the one the certificate names (`opCertKesKeyCheck`, `cardano-node/src/Cardano/Node/Protocol/Shelley.hs`
  line 214) and then switches; it does not check the certificate's signature, its window or its counter, so a
  bad one shows up as rejected blocks. Scarab, by a documented improvement, checks all of that first
  and switches only when all of these hold, and otherwise keeps the old credentials and logs a typed
  `kesReloadRefused` (`kind` is the case of `ReloadRefusal`) and counts `scarab_kes_reloads_total{outcome="refused"}`:
  the cold verification key hashes to the pool this node forges for (`WrongPool`); the certificate names the
  KES key in the file (`KeyNotCertified`) and carries the cold key's signature (`BadCertificateSignature`);
  malformed certificate input or a certificate verifier failure retains its typed cause as
  `CertificateFailure`, separately from a signature that was checked and rejected; the
  current KES period is inside `[start, start + maxKESEvolutions)` (`NotYetValid`, `Expired`); the key has not
  been evolved past the period it must sign in (`KeyPastPeriod`); and the certificate counter is the pool's
  counter on the chain or one above it (`Counter`, the rule of `doValidateKESSignature` in ouroboros-consensus-protocol
  `Protocol/Praos.hs` lines 620 to 647 at the tag above, which the node's header validation shares), so the chain will
  take the blocks. A good reload logs `kesReloaded` (`opCertCounter`, `kesStart`, `remainingKesPeriods`),
  erases the old key from memory and brings a period-0 key to the present period at once. Putting the same key
  back (a spurious HUP, or someone restoring the period-0 file) keeps the key in memory, which is further on.
  No reload can leave the node without credentials or with a mixture; a reload while a block is being signed
  waits for it. A missing replacement file (`NoSuchFileException`) is also a reload refusal.
  Other filesystem errors, verifier defects and failures to persist an evolved key are terminal,
  with their typed cause preserved. An interrupted key update prevents later signing until restart;
  cancellation itself remains cancellation.
- **Alerts.** `cardano_node_metrics_remainingKESPeriods_int`, `currentKESPeriod_int`,
  `operationalCertificate{Start,Expiry}KESPeriod_int` and `scarab_opcert_counter` are set at start-up and after
  every reload (the first two also after every evolution); `forgeStarted` and `kesReloaded` carry the same
  numbers. Alert on `remainingKESPeriods_int` falling below the driver's threshold while the driver's service is
  failing: the driver's exit status is the signal (below), and "Pool monitoring" lists the alerts and the dashboard.
- **Nothing in the node needs the cold key.** It is never read by the node and need not be mounted into its
  container.

**The rotation driver** `scripts/pool/rotate_kes.py` (Python 3 standard library, `cardano-cli` from the
`ghcr.io/intersectmbo/cardano-node:11.1.3` image through `docker run`) does the cold-key work. Run it from
a timer: `scripts/pool/rotate-kes.service`, `rotate-kes.timer` and `rotate-kes.env.example` are generic
systemd examples. The node's key directory has the shape

```
keys/current -> gen-000003-00000120      a relative link
keys/gen-000003-00000120/kes.skey, node.cert
keys/vrf.skey
```

and the node runs with `--kes-skey keys/current/kes.skey --opcert keys/current/node.cert --local-socket <socket>`
(the driver needs the socket). Each run reads the tip through the socket, computes the KES period from the
genesis' `slotsPerKESPeriod`, reads the certificate in use, and when it has `--threshold` periods or fewer left:
generates a KES pair and a certificate with the next counter (the pool's counter on the chain plus one, from
`query kes-period-info`) and the current period as its start, checks the certificate, switches `current` to
the new generation with one atomic rename, sends SIGHUP, waits up to `--confirm-timeout` seconds for the
`kesReloaded` line (counter and start period equal to the new certificate's) and then overwrites and removes
the old generation. It is idempotent and crash-safe: the rotation is recorded in `rotation.json` before the link
moves, the previous generation stays until the node confirmed, and the next run finishes what was left (the
signal is sent again, which is harmless); a `kesReloadRefused` puts the link back. Exit status: 0 done or
nothing to do, 1 the node refused (link restored), 2 not confirmed in time (run again), 3 the socket does not
answer, 4 configuration or tools wrong. When the node's `query kes-period-info` is not answered, the driver
says so and counts from the last counter it issued (`last-counter`); that fallback can issue a counter the
chain refuses if a rotation was not yet followed by a block, and the node then refuses the reload (typed, the old
pair stays).

**Cold key.** The driver signs with the pool's cold key, so on a *test network* the cold key is online, on the
machine of the driver, mounted into the `cardano-cli` container only (never into the node's); the owner accepts
that for a pool whose stake is test stake. A *mainnet* pool keeps the cold key offline: do the same steps by hand
on the offline machine (`cardano-cli node key-gen-KES`, `node new-counter --counter-value <on-chain + 1>`,
`node issue-op-cert --kes-period <current period>`), carry only the new `kes.skey` and `node.cert` to the producer
as a new `gen-...` directory, switch `current`, and send SIGHUP; the node performs the same checks.

Not covered yet: a node that is killed in the middle of a rotation, and a rotation the node refuses, are tested
in units only, not in a scenario ([#122](https://github.com/KD5UJC-XYZ/scarab/issues/122)); a KES agent that
holds the key instead of a file is H2 ([#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)).

The devnet scenario `kes-rotate` (below) runs all of this unattended: a short KES period, a small
`maxKESEvolutions`, the driver on a loop, several rotations while the node forges beside two Haskell pools.

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
state, in every Shelley-based era, without the UTxO, which the reference leaves out too (`map(0)`).
The `ntc` gate requires the complete answer bytes to match at the same acquired point. It does not
mask historical state gaps: any remaining difference fails the comparison and must be resolved.
Earlier qualified measurements are retained in [compatibility.md](compatibility.md). Not answered yet
(the connection ends, the log line `inbound ... closed` names the query): `GetRewardInfoPools`, `GetNonMyopicMemberRewards`,
`GetRewardProvenance`, `GetLedgerPeerSnapshot`, `DebugLedgerConfig`, and every query but `ledger-state` in an era other than
Conway (roadmap F1). A transaction refused over the socket carries the ledger's predicate failure, so `cardano-cli
transaction submit` prints the same error as against the Haskell node.

Fresh native genesis retains injected pool records with an empty VRF registry, matching the
reference's genesis injection. The same-point `ntc` regression for this initialization fix compares
all four ledger-state wrappers as raw bytes and checks CLI `ledger-state` output. Existing database
state is restored as stored; historical provenance remains [#129](https://github.com/KD5UJC-XYZ/scarab/issues/129).

If this build cannot decode or evaluate a script, it has no ledger rejection to return. Local submission
ends that connection with a typed service-unavailable outcome and sends no `ApplyTxErr` bytes. The
listener and node continue serving fresh clients. Unavailable validations are not remembered in the
recent-refusal cache, so recovery of the backend permits a fresh admission attempt. This differs from adopting a block: the node cannot
continue its chain without that block's consensus verdict, so the same implementation limit there stops
it with exit 8 (see "Failures that stop the node").

LocalTxMonitor (mini-protocol 9) gives `cardano-cli conway query tx-mempool info | next-tx | tx-exists` and
the protocol's `GetMeasures` (no CLI command): a client acquires a snapshot of the mempool (the slot after
the tip, the transactions in arrival order), walks it with `NextTx`, asks `HasTx`, `GetSizes` and
`GetMeasures`, and `AwaitAcquire` waits until the pool differs. The sizes are those of the reference's server:
each transaction counts for its size plus 4 bytes, and the capacity is a whole number of block-sized units
(`2 * (maxBlockBodySize - 1024)` without an override, as the reference; `ceil(--mempool-bytes / unit)` units with
one, so the same `--mempool-bytes` gives the same `capacityInBytes`). The pool enforces that capacity in all four
components (bytes, execution-unit memory and steps, reference-script bytes). A transaction that fits an empty pool
but not the room left waits for the pool to change instead of being refused, as the reference does, whether it
comes from a peer (TxSubmission2: the peer's replies are held back meanwhile, which a peer's own timeouts allow) or
from a local client (`cardano-cli transaction submit` waits for its answer); the `mempoolWaiting` log line marks
the start of a wait. Local clients are served before peers that queue, a closed client or lost peer leaves the line,
and one transaction larger than the whole capacity is refused at once as "mempool full" (the reference would wait for
ever, [IN-82](implementer-notes.md)).
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
logged as a `forgeEpoch` line when the next epoch begins. `GET /metrics` serves the Prometheus text format
(next section).

`GET /state` without point parameters reads the tip. An explicit point requires one unsigned 64-bit
`slot` and one 32-byte hex `hash`: missing, duplicate or malformed point parameters return HTTP 400.
A well-formed point outside the retained chain returns HTTP 404.

The slot clock starts at the Byron genesis' `startTime` when `byron-genesis.json` has one (as the
reference's does), else at the Shelley `systemStart`; the `started` line logs it as `systemStart`.
Write both identically in a network you make yourself.

### Logging

Every line the node writes is a typed event (roadmap J3a, [#92](https://github.com/KD5UJC-XYZ/scarab/issues/92);
[ADR 0014](adr/0014-typed-log.md)): it has a kind, a **namespace** `Area.Kind` (`Chain.Extended`) and a
**severity** (`Debug`, `Info`, `Notice`, `Warning`, `Error`, `Critical`, `Alert`, `Emergency`, the levels of the
Haskell node's trace-dispatcher). The JSON line is `{"event": ..., "at": ..., <fields>}`, the `event` being the
kind's name (`extended`); the catalogue below lists every kind. **Without a `--log-*` flag the log is what it always
was**: every line, as JSON, on stdout, and nothing on stderr. The scenarios of `devnet.py` and the run watchers parse
these lines; the field names and values are fixed by a golden (`fixtures/log/golden.jsonl`, `LogGoldenSuite`).

| Flag | Default | Meaning |
|---|---|---|
| `--log-stdout json\|human\|off` | `json` | the stdout sink and its format |
| `--log-stderr json\|human\|off` | `off` | an error stream on stderr; it lets through `Warning` and above unless a `--log-severity stderr:...` says otherwise |
| `--log-file <path>` | off | a log file, appended to (a restart continues it) and rotated by size: at `--log-file-size` it becomes `<path>.1`, the older ones move up to `<path>.<keep>`, and a new `<path>` starts |
| `--log-file-format json\|human` | `json` | with `--log-file` |
| `--log-file-size N[K\|M\|G]` | `100M` | with `--log-file`: the size at which the file turns over (powers of 1024, at least `1K`); a line is never split |
| `--log-file-keep N` | `10` | with `--log-file`: rotated files kept |
| `--log-severity [<sink>:]<namespace>=<Severity\|Silence>` | everything (stderr: `Warning`) | repeatable: the least severity `<sink>` (`stdout`, `stderr` or `file`; every sink without the prefix) lets through for `<namespace>`; `Silence` lets nothing through |
| `--log-every [<sink>:]<namespace>=N` | `1` | repeatable: `<sink>` keeps the first line of every `N` of `<namespace>` (one count for the namespace named) |

A namespace is `*` (everything), an area (`Node`, `Chain`, `Ledger`, `Rules`, `Net`, `Mempool`, `Forge`, `Storage`,
`Library`) or `Area.Kind` as in the catalogue. A setting applies to a namespace and everything below it unless a
longer one sets the same thing: `--log-severity Chain=Warning --log-severity Chain.EraTransition=Info` keeps the era
transitions and the warnings of `Chain`. This is how trace-dispatcher looks its options up (the namespace, then each
shorter prefix). A namespace that does not exist, a sink or severity that does not exist, or a file option without
`--log-file` is refused at start with a `ConfigError`. A setting for a sink that is off does nothing.

The human format is `[time][namespace](Severity) event key=value ...` (trace-dispatcher's without the host and the
thread):

```
[2026-10-16T14:48:18.570284Z][Chain.Reconnecting](Warning) reconnecting reason=unavailable: connect 127.0.0.1:9: Connection refused attempt=2
```

The libraries' own logs (http4s, fs2) go through SLF4J to the node's SLF4J backend (`LibraryLogs`, slf4j-api 2.0)
and from there to the same sinks as `slf4j` lines, namespace `Library.Slf4j`, with the library's level as the
severity and the logger name in `logger`. There is no "no SLF4J providers" (formerly `StaticLoggerBinder`) warning.

**A long run without a shell pipe.** The long runs used to pipe stdout through `tee` (everything to a file) and a
filter (stdout without the per-block `extended` lines but one in 1000). The node does both itself now, so the run
script can `exec` the JVM: it is then the container's process, `docker stop`'s SIGTERM reaches it, and the shutdown
snapshot is written (with a pipe, the signal goes to the shell and the JVM may be killed before it).

```sh
#!/bin/sh
# run.sh: the node is the container's process; no pipe
exec java -Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 \
  -cp "$(cat /work/node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow \
  --config /data/config/config.json --topology /data/config/topology.json --db /data/db \
  --log-file /data/sync.jsonl --log-file-size 256M --log-file-keep 20 \
  --log-every stdout:Chain.Extended=1000
```

`docker logs` (and the monitoring agent, [ops/monitoring](../ops/monitoring/README.md)) then sees every line but
999 of each 1000 `extended`, and `/data/sync.jsonl` with its rotated files holds everything.

**Differences from the Haskell node's tracing.** The namespaces have two levels (area and kind) where the Haskell
node's are deeper (`ChainDB.AddBlockEvent.AddedToCurrentChain`), and both levels are closed types, so a configuration
cannot name a namespace that does not exist. The frequency limit is a count (one line in `N`), not trace-dispatcher's
rate limiter (messages per second, with a budget that starts and stops it and messages about the limiting): the lines
kept do not depend on timing, so two runs of the same chain keep the same lines, and during a sync one `extended` in
`N` is readable whatever the speed. The configuration is on the command line, not the `TraceOptions` of the
configuration file (which is still ignored). There is no forwarder (`cardano-tracer`), no detail level and no EKG;
the metrics are the Prometheus endpoint below. The JSON lines keep the node's own format, not the Haskell node's
`{"at", "ns", "data", "sev", "thread", "host"}`.

#### Log events

The catalogue (`node/src/main/scala/xyz/kd5ujc/scarab/node/log/EventKind.scala`; `LogDocsSuite` fails when this
table and the code differ). The severity is the kind's; the rows marked so are raised for some events.

| Event | Namespace | Severity | Fields |
|---|---|---|---|
| `started` | `Node.Started` | Notice | `build`, `peer`, `peers`, `networkMagic`, `systemStart`, `epochLength`, `securityParam`, `byronEpochs`, `byronEpochSlots`, `hardForkEpochs`, `genesisHashes`, `configUnsupported`, `topology` |
| `blst` | `Node.Blst` | Info | `platform`, `cpu`, `library` |
| `listening` | `Node.Listening` | Notice | `address` |
| `memory` | `Node.Memory` | Info | `sample` |
| `cpuStarvation` | `Node.CpuStarvation` | Warning | `clockDriftMs`, `threshold`, `intervalMs` |
| `stateDump` | `Node.StateDump` | Info | `slot`, `hash`, `epoch`, `millis` |
| `stateDumpRefused` | `Node.StateDumpRefused` | Warning | `slot`, `hash`, `epoch`, `error` |
| `verificationCache` | `Node.VerificationCache` | Debug | `summary` |
| `error` | `Node.Error` | Error | `kind`, `message` |
| `fatal` | `Node.Fatal` | Error | `kind` (`storage`, `internal`, `defect` or `unavailable`), `message`, `exitCode` |
| `finished` | `Node.Finished` | Notice | `tip`, `retained` |
| `connected` | `Chain.Connected` | Info | `intersection` |
| `extended` | `Chain.Extended` | Info | `block`, `epoch`, `era`, `peerTip` |
| `rolledBack` | `Chain.RolledBack` | Notice | `from`, `to`, `depth` |
| `epoch` | `Chain.Epoch` | Notice | `epoch`, `first` |
| `eraTransition` | `Chain.EraTransition` | Notice | `from`, `to`, `epoch`, `slot`, `block` |
| `epochValidated` | `Chain.EpochValidated` | Info | `era`, `epoch`, `headers`, `thresholdChecked`, `overlay`, `leaderUnchecked`, `scheduling`, `boundary`, `signatureVerified`, `offSchedule`, `transactions` |
| `reconnecting` | `Chain.Reconnecting` | Warning | `reason`, `attempt` |
| `stalled` | `Chain.Stalled` | Warning | `idleSeconds`, `ourBlock`, `peerBlock` |
| `stallExit` | `Chain.StallExit` | Error | `idleSeconds`, `ourBlock`, `peerBlock` |
| `headersChecked` | `Chain.HeadersChecked` | Info | `validated`, `unchecked`, `waited` |
| `chainSyncRounds` | `Chain.ChainSyncRounds` | Info | `rounds`, `headers`, `avgMs`, `maxMs`, `maxDepth`; legacy batch facade only; production rolling sessions retain the per-reply header counter and do not emit round aggregates |
| `slowFetch` | `Chain.SlowFetch` | Info | `millis` |
| `ledgerEpoch` | `Ledger.LedgerEpoch` | Info | `epoch`, `utxo`, `accounts`, `pools`, `treasury`, `reserves`, `protocolVersion`, `phase2`, `scriptIntegrityHashChecked`, `scheduling` |
| `connection` | `Net.Connection` | Info (Warning when `what` is `refused`) | `remote`, `what`, `provenance`, `from`, `to`, `reason` |
| `inbound` | `Net.Inbound` | Info (Warning when `state` is `refused` or `cachedBlockRejected`) | `remote`, `state`, `reason` |
| `peerSelectionTargets` | `Net.PeerSelectionTargets` | Notice | `root`, `known`, `established`, `active`, `knownBigLedger`, `establishedBigLedger`, `activeBigLedger` |
| `peerSelectionRoots` | `Net.PeerSelectionRoots` | Info (Warning when `action` is `failure`) | `action`, `bigLedger`, `before`, `after`, `target`, `current`, `found`, `backoffs`, `retryInSeconds`; sharing actions `pickInbound`, `peerShareRequests`, `peerShareResults` add `peers`, `amount`, `results`, `added` |
| `peerSelectionPromote` | `Net.PeerSelectionPromote` | Info (Warning when `action` is `failed`) | `action`, `bigLedger`, `from`, `to`, `peers`, `peer`, `local`, `target`, `current`, `failure`, `retryInSeconds`, `forgotten` |
| `peerSelectionDemote` | `Net.PeerSelectionDemote` | Info (Warning when `action` is `failed`, or `asynchronous` with a `failure`) | `action`, `bigLedger`, `from`, `to`, `peers`, `peer`, `local`, `target`, `current`, `timedOut`, `failure`, `reason` |
| `peerSelectionSource` | `Net.PeerSelectionSource` | Warning | `action`, `peer`, `reason`, `sourceGeneration`, `sourceLive` |
| `peerSelectionForget` | `Net.PeerSelectionForget` | Info | `action`, `bigLedger`, `target`, `known`, `peers` |
| `peerSelectionSensitive` | `Net.PeerSelectionSensitive` | Notice | `action`, `judgement` |
| `peerSelectionCounters` | `Net.PeerSelectionCounters` | Info | `root`, `cold`, `warm`, `hot`, `known`, `established`, `active`, `availableToConnect`, `coldPromotions`, `warmPromotions`, `warmDemotions`, `activeDemotions`, `localRoots`, `bootstrap`, `bigLedger`, `origins` |
| `peerSelectionChurn` | `Net.PeerSelectionChurn` | Info | `action` (`round`, `step`, `timeout`, `wait`), `mode`, `step`, `number`, `tookSeconds`, `change`, `delaySeconds` |
| `ledgerPeers` | `Net.LedgerPeers` | Info (Warning when `action` is `snapshotIgnored`, `poolSkipped` or `unreadable`) | `action` (`snapshotLoaded`, `snapshotIgnored`, `snapshotVerified`, `refreshed`, `poolSkipped`, `unreadable`), `matchesImmutablePoint`, `slot`, `pools`, `source`, `bigLedgerPools`, `ledgerSlot`, `pool`, `reason` |
| `outboundConnections` | `Net.OutboundConnections` | Info | `state`, `trusted` |
| `gsmTransition` | `Net.GsmTransition` | Notice | `transition`, `state`, `peers`, `tipTooOld` |
| `fetchModeChanged` | `Net.FetchModeChanged` | Info | `mode` |
| `mempoolAdded` | `Mempool.MempoolAdded` | Info | `tx`, `size`, `count`, `bytes` |
| `mempoolRejected` | `Mempool.MempoolRejected` | Info | `tx`, `reason` |
| `mempoolWaiting` | `Mempool.MempoolWaiting` | Info | `tx`, `reason` |
| `mempoolDropped` | `Mempool.MempoolDropped` | Info | `tx`, `reason` |
| `mempoolAdopted` | `Mempool.MempoolAdopted` | Info | `tx` |
| `mempoolRemoved` | `Mempool.MempoolRemoved` | Info | `tx` |
| `mempoolSynced` | `Mempool.MempoolSynced` | Info | `count`, `bytes`, `dropped` |
| `forgeStarted` | `Forge.ForgeStarted` | Notice | `pool`, `vrfKeyHash`, `kesStart`, `kesEvolutions`, `opCertCounter`, `kesPeriod`, `remainingKesPeriods` |
| `forged` | `Forge.Forged` | Info | `slot`, `epoch`, `blockNo`, `hash`, `prev`, `txs`, `bodySize`, `kesPeriod`, `relativeKesPeriod`, `stake`, `totalStake`, `opCertCounter` |
| `adopted` | `Forge.Adopted` | Info | `block`, `latencyMs` |
| `lost` | `Forge.Lost` | Notice | `slot`, `hash`, `reason` |
| `kesEvolved` | `Forge.KesEvolved` | Info | `kesPeriod`, `relativeKesPeriod` |
| `kesReloaded` | `Forge.KesReloaded` | Notice | `opCertCounter`, `kesStart`, `kesEvolutions`, `kesPeriod`, `remainingKesPeriods` |
| `kesReloadRefused` | `Forge.KesReloadRefused` | Error | `kind`, `message` |
| `kesPersistFailed` | `Forge.KesPersistFailed` | Error | `kesPeriod`, `kind`, `message` |
| `forgeSkipped` | `Forge.ForgeSkipped` | Info | `slot`, `reason` |
| `forgeFailed` | `Forge.ForgeFailed` | Error | `slot`, `kind`, `message` |
| `forgeEpoch` | `Forge.ForgeEpoch` | Notice | `epoch`, `slotsChecked`, `leaderSlots`, `forged`, `adopted`, `lostSlotBattles`, `failed`, `skipped`, `skippedByReason`, `forgeLatencyMs` |
| `formatMigrated` | `Storage.FormatMigrated` | Notice | `kind`, `from`, `to`, `count` |
| `immutableOpened` | `Storage.ImmutableOpened` | Info | `tip`, `blocks`, `chunks`, `truncatedEntries`, `truncatedBytes` |
| `volatileOpened` | `Storage.VolatileOpened` | Info | `blocks`, `files`, `truncatedBytes`, `removedFiles` |
| `receivedBlockDiscarded` | `Storage.ReceivedBlockDiscarded` | Warning | Malformed unselected received cache entry discarded at restart. |
| `volatileResumed` | `Storage.VolatileResumed` | Info | `resumed`, `dropped`, `millis` |
| `networkRecorded` | `Storage.NetworkRecorded` | Info | `magic`, `shelleyGenesis`, `byronGenesis`, `alonzoGenesis`, `conwayGenesis`, `adoptedExisting` |
| `snapshotSkipped` | `Storage.SnapshotSkipped` | Warning | `file`, `reason` |
| `snapshotRestored` | `Storage.SnapshotRestored` | Info | `file`, `anchor` |
| `snapshotWritten` | `Storage.SnapshotWritten` | Info | `file`, `anchor`, `bytes`, `millis` |
| `snapshotFailed` | `Storage.SnapshotFailed` | Error | `reason` |
| `snapshotRefused` | `Storage.SnapshotRefused` | Error | `file`, `anchor`, `reason` |
| `replayStarted` | `Storage.ReplayStarted` | Info | `from`, `immutableTip` |
| `replayProgress` | `Storage.ReplayProgress` | Info | `tip`, `replayed`, `millis` |
| `replayFinished` | `Storage.ReplayFinished` | Info | `tip`, `replayed`, `millis` |
| `shutdownSnapshot` | `Storage.ShutdownSnapshot` | Info | `anchor`, `written` |
| `slf4j` | `Library.Slf4j` | Info (the library's level: TRACE and DEBUG are Debug, WARN is Warning) | `logger`, `level`, `message`, `thrown` |

### Metrics

The node exports its measurements in the Prometheus text format (version 0.0.4), for a Prometheus and Grafana stack
to watch a long sync or a pool (roadmap J3, [#47](https://github.com/KD5UJC-XYZ/scarab/issues/47); the logging
slice is J3a, #92).

**Where.** `GET /metrics` on the query server (`--query-port`, loopback only, `127.0.0.1:7071` by default), and,
with `--metrics-listen <host>:<port>`, on a listener of its own that serves `/metrics` and nothing else. The
query server also serves the whole ledger state at `/state`, so do not bind it beyond loopback; bind the metrics
listener instead. For a scraper in a sibling container (the monitoring stack) start the node with

```sh
follow ... --metrics-listen 0.0.0.0:12798
```

and scrape `http://<node container>:12798/metrics`. 12798 is the port the Haskell node's Prometheus
backend conventionally uses (`PrometheusSimple ... 12798`); the listener is off unless the flag is given. A
Prometheus job:

```yaml
scrape_configs:
  - job_name: scarab
    scrape_interval: 15s
    static_configs:
      - targets: ['scarab-node:12798']
```

The listener starts at the beginning of `follow`, before the database is opened, so a start that replays many
stored blocks is on the dashboard while it replays (the Haskell node starts its metrics and tracing before it opens
the ChainDB too). What a scrape reads from a part of the node that does not exist yet (the chain tip, the mempool,
the connection table, the forger) is absent from the output, not zero: `cardano_node_metrics_blockNum_int` and the
others appear when the replay is over. During the start read `scarab_node_phase{phase}` (one series is 1:
`Restoring`, `Replaying`, `Following`), the target `scarab_replay_target_block` / `scarab_replay_target_slot` (the
immutable tip, where the replay ends; absent when the store holds no block), the progress `scarab_replay_block` /
`scarab_replay_slot` (the last block reported, every progress interval) and `scarab_replay_blocks` /
`scarab_replay_seconds`. Progress is `scarab_replay_block / scarab_replay_target_block`. The Haskell node reports the
replay as ChainDB traces (`ChainDB.ReplayBlock.LedgerReplay`, with the slot replayed and the target) and has no
Prometheus metric for it, so these names are ours. `--query-port` still starts after the replay.

A scrape is cheap (it reads the chain tip, the mempool, the connection table and the JVM; about 150 series on a
follower) and may be as frequent as once a second. The `devnet.py` scenarios start the follower with
`--metrics-listen 127.0.0.1:12798` (inside the devnet's network namespace).

**Names.** The measurements the Haskell node also makes carry its names, in the form its new tracing writes with
`PrometheusSimple suffix`: `cardano_node_metrics_<name>_int` for an integer gauge, `_real` for a floating point
one, `_counter` for a counter. They were taken from a running cardano-node 11.1.3 (the reference image): started
as a relay and as a forging pool on the devnet with `"TraceOptions": {"": {"backends": ["Stdout MachineFormat",
"PrometheusSimple suffix 127.0.0.1 12798"]}}`, scraped at `:12798/metrics`. The names the table marks "scraped
from 11.1.3" were in that output; the ones marked "not scraped" are in the binary's metric table but the run had
no mempool traffic or lost slot battle to make them appear. The rest is ours: `scarab_*` for what is specific
to this node, `jvm_*` and `process_*` (the names of the Prometheus JVM client) in place of the Haskell node's
`RTS_*`, `rts_gc_*`, `Mem_*` and `Stat_*`, which describe the GHC runtime. Our counters end in `_total`, a
duration is in seconds, a size in bytes. Every metric has `# HELP` and `# TYPE` (the Haskell node writes no
`# HELP`); an info series is written as a gauge of value 1 (the 0.0.4 format has no `info` type).

A counter that restarts from zero is a restart of the process: use `rate()` and `increase()`. Counters with
labels appear on their first event (a label combination that never happened has no series); counters without
labels and histograms exist from the start at zero.

**How it is built** (`node/src/main/scala/xyz/kd5ujc/scarab/node/metrics/`). `Metrics[F]` is an algebra with
`inc`, `advance` (a counter whose total is known elsewhere; it only moves up), `set` and `observe`, given through
`using`; the interpreter keeps the registry in a `Ref` and `Exposition.render` writes a snapshot. A metric is a
`CounterDef[L]`, `GaugeDef[L]` or `HistogramDef[L]` with a label type `L` (`Unit`, an enum, a pair): the
`Catalogue` is the closed list, a metric or label name that the format does not allow does not compile, and a
label is a member of an enum (`Era`, `BlockRule`, ...), so the number of series is bounded by the type.
Every part of the node reports what it does as a value of a closed event type; `EventMetrics` is the one place
where those events become measurements, and `EventSink.alongside` runs it beside the JSON log (the log is
unchanged). What is read when asked for, not when it happens (the chain tip, the mempool, the connection table,
the forger's and scheduling statistics, the JVM), is read by `Collectors` at each scrape. `MeteredRules` wraps
the block rules to time them and to count a refusal by rule family.

Meanings and units worth knowing before building a dashboard:

- `scarab_validation_errors_total{rule}`: `rule` is the family of the rule that refused a block (`byron`,
  `structure`, `decode`, `envelope`, `ledger`, `tpraos`, `praos`: the `rule` field of the `error` event). A block
  from a peer that the rules refuse stops the node (an `error` event, exit status 1), so on a follower the series
  is non-zero only for the instant before it exits; it also counts a block this node forged and then refused
  itself, which does not stop it.
- `scarab_blocks_applied_total{era}` counts blocks applied to the chain since the start (the `extended` events,
  replay of stored blocks excluded); `scarab_block_apply_seconds` is the time of the block rules for each of
  them (buckets from 0.5 ms to 16.4 s, doubling), so the rate of applied blocks is
  `rate(scarab_blocks_applied_total[1m])` and the mean time to apply is
  `rate(scarab_block_apply_seconds_sum[5m]) / rate(scarab_block_apply_seconds_count[5m])`.
- Sync progress: `cardano_node_metrics_slotNum_int / scarab_peer_tip_slot`, or in blocks
  `cardano_node_metrics_blockNum_int / scarab_peer_tip_block` (the tip the peer last announced).
- `scarab_era{era}` is 1 for the era of the tip and 0 for the other six.
- `scarab_immutable_blocks` is what the immutable store held at the start plus the blocks that became
  immutable since; `scarab_volatile_blocks` is at most `k`.
- `scarab_ledger_utxo_entries`, `_accounts` and `_pools` are as of the start of the last epoch (they change once
  per epoch, not per block) and are the `utxo`, `accounts` and `pools` of the `ledgerEpoch` log line.
- `scarab_snapshots_migrated_total` counts files rewritten from an older storage format when the database was
  opened; `scarab_snapshot_last_write_seconds` and `_bytes` describe the last snapshot written.
- `scarab_scheduling_*` and `scarab_cpu_*` are the whole-run measurements of `GET /scheduling` (roadmap D8): a
  timer that fires late means the node's threads were starved.
- `jvm_gc_collection_seconds_total` is the time the JVM reports for each collector (a counter, in seconds, as
  precise as the JVM's own millisecond count); `jvm_memory_bytes_max{area="nonheap"}` is -1 when the JVM sets no
  limit.

**Label values.** Each label is a member of a closed set, so a dashboard can list them: `era` is `Byron`,
`Shelley`, `Allegra`, `Mary`, `Alonzo`, `Babbage` or `Conway`; `rule` is `byron`, `structure`, `decode`, `envelope`,
`ledger`, `tpraos` or `praos`; `state` (of a connection) is `Reserved`, `Unnegotiated`, `Established`, `Terminating`
or `Terminated`; `event` is, for `scarab_connection_events_total`, `Opened`, `Changed`, `Refused` or `Closed`, and for
`scarab_inbound_events_total`, `Accepted`, `Duplex`, `Unidirectional`, `Following`, `SessionEnded`, `SessionReturned`,
`SessionDropped`, `CachedBlockRejected`, `Closed` or `Refused`; `reason` is, for `scarab_mempool_rejected_total`, `Malformed`,
`AlreadyHeld`, `Ledger`, `Full` or `WrongEra`, and for `scarab_forge_skipped_total`, `Busy`, `TipTooOld`,
`SlotOccupied`, `NotConnected` or `Other`; `kind` (snapshots) is `Skipped`, `Failed` or `Refused`; `over_ms` is
`50` or `100`; `area` is `heap` or `nonheap`; `gc` is the name the JVM gives the collector (`G1 Young
Generation`, `G1 Old Generation`, `G1 Concurrent GC`); `version`, `scala_version` and `git_commit` of
`scarab_build_info` are the build's (`git_commit` is `unknown` when the build could not read `.git`). The series
of an enum label that never happened are absent, except `scarab_era` (written for every era after the first block),
`scarab_connections` and, on a node that forges, `scarab_forge_skipped_total`, which are written for every member.

**Differences in meaning from the Haskell node's metrics of the same name.**

- `cardano_node_metrics_density_real`: both are a number of blocks per slot over the volatile chain. Ours is
  the blocks of the volatile chain over the slots from its anchor (the immutable tip) to the tip; the Haskell
  node computes it over the fragment of its ChainDB (`fragmentChainDensity`, cardano-node
  `Cardano/Node/Tracing/Tracers/ChainDB.hs`; the formula is from the source as remembered, not read line by
  line in this work, and the two agree in magnitude on the devnet).
- `cardano_node_metrics_slotsMissed_int` is ours for the slots the forger skipped because it was still busy
  with an earlier one (the `busy` reason); the Haskell node counts the slots it failed to lead in time.
- `cardano_node_metrics_connectionManager_{inbound,outbound,duplex}Conns_int` count the connections of our
  connection manager (one per peer address, either direction); the Haskell manager has more states and counts
  all of them.
- `cardano_node_metrics_currentKESPeriod_int` and `remainingKESPeriods_int` appear at start, and again after every key evolution and every reload;
  `operationalCertificate{Start,Expiry}KESPeriod_int`, `scarab_opcert_counter` and `forging_enabled_int` at start and
  after every reload.
- The Haskell gauges `blocksForged_int`, `nodeIsLeader_int` and the `Forge_*_counter` counters are two views
  of the same counts; both are exported.

**Not exported** (cardano-node 11.1.3 has them; tracked in roadmap J3e): `cardano_node_metrics_served_block_counter`,
`served_header_counter` and `served_block_latest_counter` (what the node served to peers; only the inbound
sessions of `GET /connections` count headers), `utxoSize_int` and `delegMapSize_int` (see
`scarab_ledger_utxo_entries` for the UTxO set, once per epoch), `blockReplayProgress_real`, `txsSyncDuration*`,
`tipBlock` and `basicInfo`, `inboundGovernor_*`, the `blockfetchclient_*` series (see
`scarab_blockfetch_seconds`), and the GHC runtime's `RTS_*`, `rts_gc_*`, `Mem_*` and `Stat_*`.

The full list follows, in the order of the catalogue
(`node/src/main/scala/xyz/kd5ujc/scarab/node/metrics/Catalogue.scala`); `DocsSuite` fails when this
section and the catalogue differ.

#### The chain

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `cardano_node_metrics_blockNum_int` | gauge |  | Block number of the tip of the chain. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_slotNum_int` | gauge |  | Slot number of the tip of the chain. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_epoch_int` | gauge |  | Epoch of the tip of the chain. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_slotInEpoch_int` | gauge |  | Slot of the tip of the chain within its epoch. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_density_real` | gauge |  | Chain density of the volatile chain: its blocks per slot, from its anchor (the immutable tip) to the tip. | same name; scraped from 11.1.3 (see "Differences in meaning") |
| `cardano_node_metrics_forks_counter` | counter |  | Switches to another fork (a rollback of at least one block) since the start. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_node_start_time_int` | gauge |  | Start of the node, in seconds since the epoch of 1970. | same name; scraped from 11.1.3 |
| `scarab_build_info` | gauge | `version`, `scala_version`, `git_commit` | The build that is running (always 1). | `cardano_node_metrics_cardano_build_info` (an info series there) |
| `scarab_era` | gauge | `era` | 1 for the era of the tip of the chain, 0 for the others. | none |
| `scarab_blocks_applied_total` | counter | `era` | Blocks applied to the chain since the start, by era (replay of the stored blocks excluded). | none |
| `scarab_block_apply_seconds` | histogram |  | Time to validate a block and apply it to the ledger state. | none |
| `scarab_validation_errors_total` | counter | `rule` | Blocks the rules refused, by rule family. | none |
| `scarab_peer_tip_slot` | gauge |  | Slot of the tip of the peer's chain, as the peer last announced it. | none |
| `scarab_peer_tip_block` | gauge |  | Block number of the tip of the peer's chain, as the peer last announced it. | none |
| `scarab_peer_reconnects_total` | counter |  | Times the connection to the peer was lost and opened again. | none |
| `scarab_follower_stalls_total` | counter |  | Times the follower went the stall-minutes without extending its chain while a peer announced a longer one. | none |
| `scarab_follower_stalled` | gauge |  | 1 while the follower is stalled (see scarab_follower_stalls_total), else 0. | none |
| `scarab_rollbacks_total` | counter |  | Rollbacks of the chain. | none |
| `scarab_rollback_depth_blocks` | histogram |  | Blocks rolled back at a time. | none |
| `scarab_volatile_blocks` | gauge |  | Blocks in the volatile chain (at most k). | none |
| `scarab_immutable_blocks` | gauge |  | Blocks in the immutable chain: those found at the start and those that became immutable since. | none |
| `scarab_ledger_utxo_entries` | gauge |  | Entries of the UTxO set, as of the start of the last epoch (updated once per epoch). | none |
| `scarab_ledger_accounts` | gauge |  | Registered stake credentials, as of the start of the last epoch. | none |
| `scarab_ledger_pools` | gauge |  | Registered stake pools, as of the start of the last epoch. | none |

#### The mempool

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `cardano_node_metrics_txsInMempool_int` | gauge |  | Transactions in the mempool. | same name; in the 11.1.3 binary, not scraped (no traffic in the run) |
| `cardano_node_metrics_mempoolBytes_int` | gauge |  | Size of the transactions in the mempool, in bytes. | same name; in the 11.1.3 binary, not scraped (no traffic in the run) |
| `cardano_node_metrics_txsProcessedNum_counter` | counter |  | Transactions added to the mempool since the start. | same name; in the 11.1.3 binary, not scraped (no traffic in the run) |
| `scarab_mempool_rejected_total` | counter | `reason` | Transactions the mempool refused, by reason. | none (a trace message only) |
| `scarab_mempool_dropped_total` | counter |  | Held transactions dropped because the new tip no longer admits them. | none (a trace message only) |
| `scarab_mempool_adopted_total` | counter |  | Held transactions that appeared in a block of our chain. | none |
| `scarab_mempool_removed_total` | counter |  | Transactions removed from the mempool on request although they still apply (a forged block that our own chain refused). | none |

#### Forging (only on a node started with `--forge`)

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `cardano_node_metrics_Forge_about_to_lead_counter` | counter |  | Slots checked for leadership. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_node_is_leader_counter` | counter |  | Slots the pool led. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_node_not_leader_counter` | counter |  | Slots checked that the pool did not lead. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_forged_counter` | counter |  | Blocks forged. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_adopted_counter` | counter |  | Forged blocks that became the tip of our chain. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_didnt_adopt_counter` | counter |  | Forged blocks that did not stay on our chain (lost slot battles, replaced later). | same name; from the binary's trace name `Forge.didnt-adopt`, not scraped (no such event in the run) |
| `cardano_node_metrics_blocksForged_int` | gauge |  | Blocks forged since the start. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_nodeIsLeader_int` | gauge |  | Slots the pool led since the start. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_nodeCannotForge_int` | gauge |  | Slots the pool led but could not forge in, since the start. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_slotsMissed_int` | gauge |  | Slots the forger skipped because it was still busy with an earlier one, since the start. | same name; scraped from 11.1.3 (see "Differences in meaning") |
| `cardano_node_metrics_forgedSlotLast_int` | gauge |  | Slot of the last block forged. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_forging_enabled_int` | gauge |  | 1 when the node has forging credentials. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_currentKESPeriod_int` | gauge |  | The KES period of the last block forged or of the last key evolution. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_remainingKESPeriods_int` | gauge |  | KES periods left before the operational certificate expires. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_operationalCertificateStartKESPeriod_int` | gauge |  | The first KES period of the operational certificate. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_operationalCertificateExpiryKESPeriod_int` | gauge |  | The KES period at which the operational certificate expires. | same name; scraped from 11.1.3 |
| `scarab_opcert_counter` | gauge |  | The issue counter of the operational certificate in use. | none |
| `scarab_kes_reloads_total` | counter | `outcome` | Reloads of the KES key and operational certificate (SIGHUP), by outcome. | none |
| `scarab_kes_persist_failures_total` | counter |  | Evolved KES keys that could not be written back to the key file. | none |
| `scarab_forge_skipped_total` | counter | `reason` | Slots the forger did not check, by reason. | none |
| `scarab_forge_adoption_seconds` | histogram |  | Time from the start of the slot to the adoption of the block forged in it. | none |

#### Peers and connections

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `cardano_node_metrics_connectionManager_inboundConns_int` | gauge |  | Connections the peer opened to us. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_connectionManager_outboundConns_int` | gauge |  | Connections we opened to a peer. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_connectionManager_duplexConns_int` | gauge |  | Connections on which both sides run the protocols. | same name; scraped from 11.1.3 |
| `scarab_connections` | gauge | `state` | Connections the connection manager knows, by state. | none |
| `scarab_connection_events_total` | counter | `event` | What the connection manager did. | none |
| `scarab_inbound_events_total` | counter | `event` | What the inbound governor did on accepted connections. | none |
| `scarab_chainsync_rounds_total` | counter |  | Pipelined ChainSync rounds. | none |
| `scarab_chainsync_headers_total` | counter |  | Headers (and rollbacks) received over ChainSync. | none |
| `scarab_chainsync_round_seconds` | histogram |  | Time of a pipelined ChainSync round, from the first request to the last reply. | none |
| `scarab_blockfetch_seconds` | histogram |  | Time to fetch a block on its own, from the request to the block. | none |
| `scarab_headers_validated_total` | counter |  | Headers that passed every header rule before their block was fetched. | none |
| `scarab_headers_unchecked_total` | counter |  | Headers left to the validation of their block. | none |
| `scarab_headers_waited_total` | counter |  | Headers that waited for the chain to advance before they could be checked. | none |

#### Peer selection (G1)

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `scarab_peer_selection_transitions_total` | counter | `transition` | What the peer governor did to peers, and what became of it. | none |
| `scarab_peer_selection_targets` | gauge | `target` | The targets the peer governor works towards, for the ordinary peers and for the big ledger peers. | none |
| `scarab_peer_selection_peers` | gauge | `origin` | The known peers by where they came from: a local root, a public root, a bootstrap peer, the ledger, a big ledger pool, or no longer a root. | none |
| `scarab_ledger_peers_pools` | gauge | `map` | The pools of the ledger-peers worker's stake maps: the ordinary map, and the big ledger peers' (the first 90% of the stake). | none |
| `scarab_peer_selection_backoff_seconds` | histogram |  | The wait before a peer whose promotion failed is tried again. | none |
| `scarab_peer_selection_churn_rounds_total` | counter |  | Rounds of the churn governor begun (`TraceChurnMode`). | none |
| `scarab_peer_selection_churn_steps_total` | counter | `step`, `result` | Steps of the churn governor's rounds, by step and by whether the node met the targets or the step timed out. | none |
| `scarab_peer_selection_churn_wait_seconds` | gauge |  | How long the churn governor sleeps before its next round, as last decided. | none |
| `scarab_peer_selection_peer_metrics_board_size` | gauge | `board` | Slots held by the peer metrics boards (at most 180 each): the headers and the blocks whose first arrival is recorded. | none |
| `scarab_peer_selection_sensitive` | gauge |  | 1 while the node uses only trusted peers because its ledger is too old and bootstrap peers are configured, else 0. | none |
| `scarab_outbound_connections_trusted` | gauge |  | 1 while the node is connected only to peers it trusts (the Honest Availability Assumption of the Genesis State Machine), else 0. | none |
| `scarab_gsm_state` | gauge | `state` | 1 for the state of the Genesis State Machine, 0 for the others. | none |
| `scarab_fetch_mode` | gauge | `mode` | 1 for the fetch mode the node is in, 0 for the other. | none |
| `cardano_node_metrics_peerSelection_Cold_int` | gauge |  | Number of cold peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_Warm_int` | gauge |  | Number of warm peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_Hot_int` | gauge |  | Number of hot peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdBigLedgerPeers_int` | gauge |  | Number of cold big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBigLedgerPeers_int` | gauge |  | Number of warm big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_HotBigLedgerPeers_int` | gauge |  | Number of hot big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmLocalRoots_int` | gauge |  | Deprecated; as the reference computes it: the active local roots. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_HotLocalRoots_int` | gauge |  | Deprecated; as the reference computes it: the local roots established beyond the active. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_RootPeers_int` | gauge |  | Number of root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownPeers_int` | gauge |  | Number of known peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdPeersPromotions_int` | gauge |  | Number of cold peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedPeers_int` | gauge |  | Number of established peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmPeersDemotions_int` | gauge |  | Number of warm peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmPeersPromotions_int` | gauge |  | Number of warm peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActivePeers_int` | gauge |  | Number of active peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActivePeersDemotions_int` | gauge |  | Number of active peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownBigLedgerPeers_int` | gauge |  | Number of known big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdBigLedgerPeersPromotions_int` | gauge |  | Number of cold big ledger peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedBigLedgerPeers_int` | gauge |  | Number of established big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBigLedgerPeersDemotions_int` | gauge |  | Number of warm big ledger peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBigLedgerPeersPromotions_int` | gauge |  | Number of warm big ledger peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveBigLedgerPeers_int` | gauge |  | Number of active big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveBigLedgerPeersDemotions_int` | gauge |  | Number of active big ledger peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownLocalRootPeers_int` | gauge |  | Number of known local root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedLocalRootPeers_int` | gauge |  | Number of established local root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmLocalRootPeersPromotions_int` | gauge |  | Number of warm local root peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveLocalRootPeers_int` | gauge |  | Number of active local root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveLocalRootPeersDemotions_int` | gauge |  | Number of active local root peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownNonRootPeers_int` | gauge |  | Number of known peers that are not roots. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdNonRootPeersPromotions_int` | gauge |  | Number of cold non-root peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedNonRootPeers_int` | gauge |  | Number of established non-root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmNonRootPeersDemotions_int` | gauge |  | Number of warm non-root peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmNonRootPeersPromotions_int` | gauge |  | Number of warm non-root peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveNonRootPeers_int` | gauge |  | Number of active non-root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveNonRootPeersDemotions_int` | gauge |  | Number of active non-root peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownBootstrapPeers_int` | gauge |  | Number of known bootstrap peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdBootstrapPeersPromotions_int` | gauge |  | Number of cold bootstrap peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedBootstrapPeers_int` | gauge |  | Number of established bootstrap peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBootstrapPeersDemotions_int` | gauge |  | Number of warm bootstrap peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBootstrapPeersPromotions_int` | gauge |  | Number of warm bootstrap peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveBootstrapPeers_int` | gauge |  | Number of active bootstrap peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveBootstrapPeersDemotions_int` | gauge |  | Number of active bootstrap peers being demoted. | same name; in the source of 11.1.3, not scraped |

#### Storage

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `scarab_snapshots_written_total` | counter |  | Ledger snapshots written. | none |
| `scarab_snapshots_restored_total` | counter |  | Ledger snapshots the node started from. | none |
| `scarab_snapshots_migrated_total` | counter |  | Files rewritten from an older storage format when the database was opened. | none |
| `scarab_snapshot_problems_total` | counter | `kind` | Snapshots skipped, not written or refused by the format, by kind. | none |
| `scarab_snapshot_last_write_seconds` | gauge |  | Duration of the last snapshot written. | none |
| `scarab_snapshot_last_bytes` | gauge |  | Size of the last snapshot written, in bytes. | none |
| `scarab_replay_blocks` | gauge |  | Stored blocks applied again at the start (so far, then in total). | none |
| `scarab_replay_seconds` | gauge |  | Time spent applying the stored blocks again at the start. | none |
| `scarab_node_phase` | gauge | `phase` | 1 for the phase the start has reached (Restoring, Replaying, Following), 0 for the others. | none |
| `scarab_replay_target_block` | gauge |  | Block number of the immutable tip, where the replay of the stored blocks ends. | none |
| `scarab_replay_target_slot` | gauge |  | Slot of the immutable tip, where the replay of the stored blocks ends. | none |
| `scarab_replay_block` | gauge |  | Block number of the last stored block reported as applied again (every progress interval). | none |
| `scarab_replay_slot` | gauge |  | Slot of the last stored block reported as applied again (every progress interval). | none |

#### Scheduling (D8)

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `scarab_scheduling_timer_samples_total` | counter |  | Timer samples taken by the scheduling monitor. | none |
| `scarab_scheduling_timer_lateness_max_seconds` | gauge |  | The latest a timer fired beyond its due time, since the start. | none |
| `scarab_scheduling_timers_late_total` | counter | `over_ms` | Timers that fired more than `over` milliseconds late. | none |
| `scarab_cpu_steps_total` | counter |  | CPU-bound steps run on the follower's pool. | none |
| `scarab_cpu_longest_step_seconds` | gauge |  | The longest CPU-bound step, since the start. | none |

#### The JVM and the process

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `jvm_memory_bytes_used` | gauge | `area` | Memory in use, by area. | `cardano_node_metrics_RTS_gcLiveBytes_int` (GHC) |
| `jvm_memory_bytes_committed` | gauge | `area` | Memory committed by the JVM, by area. | none |
| `jvm_memory_bytes_max` | gauge | `area` | Most memory the JVM may use, by area (-1 when unlimited). | none |
| `jvm_gc_collections_total` | counter | `gc` | Garbage collections, by collector. | `cardano_node_metrics_RTS_gcMajorNum_int` (GHC) |
| `jvm_gc_collection_seconds_total` | counter | `gc` | Time spent in garbage collection (approximate, as the JVM reports it), by collector. | none |
| `jvm_threads_current` | gauge |  | Live threads. | none |
| `jvm_classes_loaded` | gauge |  | Classes currently loaded. | none |
| `process_cpu_seconds_total` | counter |  | CPU time of the process (user and system), in seconds. | `cardano_node_metrics_Stat_cputicks_int` |
| `process_resident_memory_bytes` | gauge |  | Resident memory of the process, in bytes. | `cardano_node_metrics_Mem_resident_int` |
| `process_open_fds` | gauge |  | Open file descriptors. | none |
| `process_start_time_seconds` | gauge |  | Start of the process, in seconds since the epoch of 1970. | `cardano_node_metrics_node_start_time_int`, and `basicInfo` |

### Stall watchdog

A node that is up but no longer extends its chain looks healthy from outside (the process is alive and
idle, the metrics are served). The watchdog (`StallWatchdog`) compares three facts: when a block was
last extended, our tip, and the highest tip any peer announced (every ChainSync batch of every outbound
and inbound session reports its peer's tip). When no block has been extended for `--stall-minutes`
(default 10, `0` turns it off) while an announced tip is ahead of ours, it logs one `stalled` event
(`idleSeconds`, `ourBlock`, `peerBlock`, Warning), adds one to `scarab_follower_stalls_total` and sets
`scarab_follower_stalled` to 1 until the next block is extended. A node at the tip of every peer never
stalls however long the network takes to produce a block. Alert on
`scarab_follower_stalled == 1`, or on `increase(scarab_follower_stalls_total[1h]) > 0`.

A stalled process is alive, so a supervisor never restarts it. `--stall-exit-after 30m` (a number and `s`,
`m` or `h`; off by default) ends the node when the stall has lasted that long: one `stallExit` event, then the
same close-down as a signal (the stores close and the shutdown snapshot is taken), then exit code 4 (1 is an
error, 2 a bad command line; 3 was a halt on a rules divergence and is no longer used). With Docker's `unless-stopped` that is a clean
restart from the newest snapshot. It cannot loop on a healthy start: the watchdog starts counting only when the
node is following (not while it restores or replays), only a peer's announced tip ahead of ours counts, and
every block extended starts the count again. The time must be longer than `--stall-minutes`.

### Failures that stop the node (#154)

A peer session ends in one of a closed set of ways ([ADR 0017](adr/0017-fibers-end-with-values.md)), and the
node does something different for each. A peer that breaks the protocol, sends an invalid header, an invalid
block (a block our rules refuse) or more unread bytes on a mini-protocol than its ingress limit allows is
forgotten by the governor (`reconnecting` with a `protocol:` or `invalid block` reason, `peerSelectionDemote`
with `forgotten`). A lost connection, a timeout, a peer behind us and a block that another peer delivered and our
rules refuse are tried again after the exit policy's delay. Four outcomes stop the node without attributing a failure to the peer:

| Outcome | `fatal` kind | Exit code |
|---|---|---|
| the storage failed under the session (a write, a read, a corrupt file) | `storage` | 5 |
| the node failed itself (a local chain model that does not hold, a forging fault, ...) | `internal` | 6 |
| an exception nobody expected (a bug, a library that threw) | `defect` | 7 |
| this build cannot evaluate a script or its encoding, during replay or live adoption; no ledger verdict exists | `unavailable` | 8 |

The run closes down as it does for a stall (the stores close, the shutdown snapshot is taken), logs one `fatal`
event, and exits with the code; a supervisor that restarts the node (Docker `unless-stopped`) brings it back from
the database, where the storage layer recovers as the reference's does (`consensusRethrowPolicy` makes a storage
failure `ShutdownNode`). Exit code 4 stays the stall's, 1 an error, 2 a bad command line. Fatal outcomes release the link and do not
produce peer-loss observations or reconnects. Exit 8 requires an implementation or configuration that can
evaluate the script; a restart alone does not add that capability. Expected transport `IOException`s remain
recoverable connection closures; unexpected network exceptions stop with exit 7.

The multiplexer's ingress limits are the reference's and are not configurable
(`IngressLimits`, ouroboros-network 1.2.0.0, network-mux 0.11.0.0): ChainSync 462,000 bytes, BlockFetch
23,068,694, TxSubmission2 721,424, KeepAlive 1,408, Handshake/PeerSharing 5,760; each node-to-client queue has the reference local-interface ceiling of 4,294,967,295 bytes. A
`--chain-sync-high` above the reference's pipelining high mark of 300 raises the ChainSync ingress limit with it.

An established connection may wait indefinitely between mux SDUs. Once header bytes arrive, that SDU
must complete within 30 s; trickled bytes do not restart the timer. A write gets 30 s per SDU in its batch,
or 10 s for handshake SDUs. Expiry is a typed transport failure handled by the normal reconnect policy.
The existing 10 s handshake receive phase also bounds an entirely silent peer. The outbound queue holds
at most 64 messages and sending waits for room.

Message byte limits apply to complete messages too, including when multiple messages arrive in one
SDU. TxSubmission keeps at most 10 unacknowledged IDs; repeated nonblocking requests cannot grow it
without acknowledgement. Duplicate/unadvertised body requests and excess/duplicate offers are protocol
violations. Body replies have 10 s; blocking ID waits remain indefinite. The existing local profile still
limits each reply to eight items/524,400bytes and each original transaction to 65,536 bytes. The
[network-limit matrix](design/network-limits.md) gives every protocol state, the reference pin, queue
budgets and existing stricter local timeout policies.


## Monitoring long runs

Grafana dashboards and log search for the runs on the workers: Prometheus, Loki and Grafana on one
server, and an agent on each worker that pushes the `scarab-*` containers' logs and the host and
container metrics. Setup and deployment: [ops/monitoring](../ops/monitoring/README.md).

### Pool monitoring (#117)

For a node started with `--forge`, in the same stack ([ops/monitoring](../ops/monitoring/README.md)): the
The **Scarab overview**, **Scarab network** and **Scarab chain tip and ledger** dashboards provide
live activity at the tip, governor temperatures and targets (ordinary plus big-ledger), discovery,
connection lifecycle, failures/churn, validation, rollback and persistence views without changing
the node. Detailed rows are collapsed, missing telemetry remains missing, and each measurement's
scope is documented in the panel. See [the monitoring guide](../ops/monitoring/README.md#dashboards)
for dashboard UIDs, interpretation, source generation and live query verification. These views do
not supply unexported received-block sizes, producer histories or a complete fork tree.

**Scarab pool** Grafana dashboard (`scarab-pool`, generated by `ops/monitoring/server/grafana/build_dashboards.py`) and
the Prometheus alert rules `ops/monitoring/server/prometheus/rules/pool.yml`. The node's series come from its
`/metrics` (see "Metrics" above: the forging, peers and phase tables) with the labels `host` and `container`; the
rotation driver's come from a textfile (below) with `host` only.

**Dashboard rows.** Alerts and state (alerts firing, node up, forging enabled, phase, stalled, uptime); Forging
(`Forge_forged_counter`, `Forge_adopted_counter`, `Forge_didnt_adopt_counter` as forged, adopted and lost; `nodeCannotForge_int` as
forge failures; `Forge_about_to_lead_counter` and `Forge_node_is_leader_counter` as leader slots checked and won; blocks per
epoch against the expectation: the `expected` box, 14 by default, and the `epoch` box, 5d on Preprod); KES
and certificate (`remainingKESPeriods_int`, `currentKESPeriod_int`, `operationalCertificate{Start,Expiry}KESPeriod_int`,
`scarab_opcert_counter`, `scarab_kes_reloads_total{outcome}`, `scarab_kes_persist_failures_total`); Rotation driver; Sync and
peers (`scarab_peer_tip_slot` minus `cardano_node_metrics_slotNum_int` as the slot lag, `peerSelection_{Hot,Warm,Cold}_int`,
`scarab_node_phase`, `scarab_follower_stalled`, `process_start_time_seconds` for restarts). No Scala was needed: the
node already exports every one of these. `forged`, `adopted`, `lost` and `forgeFailed` events are counted from the
forger's totals, not from the log, so the dashboard reads Prometheus only.

**The rotation driver's metrics.** `rotate_kes.py --metrics-file <path>` (or `ROTATE_KES_METRICS_FILE`) rewrites a
Prometheus text file atomically after every run (a run that finds another holding the lock writes nothing):

| Metric (gauge) | Meaning |
|---|---|
| `scarab_kes_rotation_last_run_timestamp_seconds` | Unix time the last run finished. |
| `scarab_kes_rotation_last_success_timestamp_seconds` | Unix time of the last run that ended with exit status 0 (kept across failed runs). |
| `scarab_kes_rotation_last_rotation_timestamp_seconds` | Unix time the node last confirmed credentials the driver installed (kept). |
| `scarab_kes_rotation_last_exit_status` | Exit status of the last run: 0 ok, 1 refused, 2 not confirmed, 3 node unreachable, 4 setup wrong. |
| `scarab_kes_rotation_periods_remaining` | KES periods the certificate had left when the driver last looked, before any rotation. |

To scrape it, point `--metrics-file` at `*.prom` in a directory the host's node exporter reads as its textfile
collector. With the agent of this stack that directory is `TEXTFILE_DIR` in the agent's `.env` (default `./textfile`
next to its `compose.yaml`; mounted at `/host/textfile`, read through `prometheus.exporter.unix`'s `textfile` block), and the
series then carry the agent's `host` label. Any other node exporter works with `--collector.textfile.directory`.
Without the file `PoolKesRotationMetricsMissing` fires after an hour.

**Alerts.** Labels are `severity` (`info`, `warning`, `critical`) and the series' own `host` and `container`; the rules name
no receiver. Thresholds are first guesses for a single Preprod pool with a 1 s slot and an hourly driver timer; tune
them in the file (`promtool test rules` covers them: `ops/monitoring/server/prometheus/tests/pool.test.yml`).

| Alert | Expression, for | Severity | Why |
|---|---|---|---|
| `PoolKesPeriodsLow` | `remainingKESPeriods_int` in [3, 10), 5 m | warning | The driver rotates at 10 or fewer; below 10 it has not managed to. |
| `PoolKesPeriodsCritical` | `remainingKESPeriods_int` < 3, 1 m | critical | The certificate is about to expire: no blocks. Rotate by hand. |
| `PoolKesRotationStale` | now minus `last_success_timestamp_seconds` > 3 h, 5 m | critical | Three hourly runs without a success. |
| `PoolKesRotationMetricsMissing` | `absent(last_success_timestamp_seconds)`, 1 h | warning | The two alerts above cannot fire without the file. |
| `PoolKesRotationFailing` | `last_exit_status != 0`, 10 m | warning | The last run failed; the exit status says why. |
| `PoolKesPersistFailed` | `increase(kes_persist_failures_total[15m]) > 0` | critical | Key persistence failed (`kesPersistFailed`); signing stopped. Repair the cause and restart. |
| `PoolForgedBlockNotAdopted` | forged minus adopted minus didnt_adopt over 30 m > 0, 10 m | critical | A block was forged and neither adopted nor lost. |
| `PoolBlockLost` | `increase(Forge_didnt_adopt_counter[15m]) > 0` | warning | A slot battle lost or a block replaced. |
| `PoolForgeFailed` | `delta(nodeCannotForge_int[15m]) > 0` | critical | `forgeFailed`: the pool led a slot and could not forge. |
| `PoolForgingDisabled` | `forging_enabled_int == 0`, 5 m | critical | No credentials. |
| `PoolNotCheckingSlots` | no `about_to_lead` increase in 15 m while Following, 5 m | warning | The forger is not running. |
| `PoolNoBlocksFor48h` | no adopted block in 48 h and uptime above 48 h, 1 h | info | 14 blocks in 5 days leave 48 h empty under 1 percent of the time. Fires before the stake is active (epoch 321 on Preprod): silence it until then. |
| `PoolNodeDown` | `up{job="scarab"} == 0`, 2 m | critical | `/metrics` does not answer. |
| `PoolRestarted` | `changes(process_start_time_seconds[15m]) > 0` | warning | Uptime was reset. |
| `PoolBehindPeers` | peers' tip slot minus ours > 120, 10 m | warning | Not synced: a block forged now is on a stale chain. 120 slots is about six blocks at Preprod's density. |
| `PoolNoHotPeers` | `peerSelection_Hot_int == 0`, 5 m | critical | The node follows no one. |
| `PoolFollowerStalled` | `scarab_follower_stalled == 1`, 10 m | critical | The stall watchdog fired and did not clear. |

Notifications are the operator's: point an Alertmanager (or Grafana alerting on the same expressions) at the Prometheus of
this stack and route on `severity`. The stack ships none, so no address or credential is in the repository.
Checks: `promtool check rules` and `promtool test rules` in the `prom/prometheus` image, and
`python3 -m unittest scripts/tests/test_dashboards.py` (the generated JSON is the committed JSON, and every metric a
panel or a rule reads exists in the catalogue or is written by the driver).

## Fuzzing the decoders (roadmap K3)

The `fuzz` project (`fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz`) throws mutated and generated bytes at every decoder that reads
data from a peer, a client or a block: raw CBOR, the node-to-node and node-to-client handshakes, the mux (header,
frame, stream), ChainSync, BlockFetch, TxSubmission2, KeepAlive, LocalStateQuery, LocalTxSubmission and
LocalTxMonitor in every state and for both roles, headers and blocks (Byron and each later era), transaction bodies,
witness sets and whole submitted transactions per era, addresses and outputs, scripts, Plutus data, node-to-client
queries and protocol-parameter updates. `Targets.all` is the maintained target registry.

Inputs (`Mutate.scala`, `Corpus.scala`): ScalaCheck generators that take a seed item and apply one to four
mutations (bit flips, truncation, extension, a head rewritten to declare 2^60 elements, a fresh head with a huge
length, nesting of up to 100,000 levels, an indefinite item without its break or a break without its item, extra
tags, a whole item replaced by a deeply nested one, splicing two items), random bytes, and random heads with random
tails. The seed items are the committed fixtures (`fixtures/*/*.hex`, every era) and valid items sampled from the
pinned CDDL by the `models-gen` sampler (messages of each mini-protocol, headers, blocks, transaction bodies and
witness sets of each era, Plutus data). [Native-serialized early-era transactions](../fixtures/fuzz/early-era-transactions/README.md)
exercise nonempty input/output decoding without asserting ledger admission. Whole-transaction envelopes have
three fields before Alonzo and four from Alonzo; the harness preserves that era boundary.

Properties (`Harness.scala`, `Targets.scala`): the decoder never throws (a result or a typed error), finishes within
2 s (a decode stuck for 30 s kills the fork after printing the input), allocates at most 24 MB plus 400 bytes per
input byte (so a declared length cannot buy memory), and what decodes re-encodes and decodes to the same message
(mini-protocols: also the consumed prefix decodes alone and a shorter prefix does not). `GuardSuite` adds the fixed
cases: every registered target accepts at least one seed in that run, without exemptions, and a short input that declares 2^60 or 2^32 elements or bytes
(alone, nested, behind tags) fails within 4 MB of allocation in every decoder.

The block target compares the Shelley-only block decoder with the block retained by node
preparation. Both accept the same block envelopes; transaction decoding failures remain inside
`Prepared` rather than rejecting the block envelope. General block decoding additionally accepts
Byron with its epoch geometry. These entry points share their parser, so the comparison checks
their integration contract rather than providing an independent reference oracle.

```sh
scripts/sbtw check                    # includes fuzz/Test/test: 20,000 cases per target, seed 1
scripts/sbtw "fuzz"                   # the same suite with 2,000 cases, seed 1
scripts/sbtw "fuzz 100000 1000 8"     # fuzz <cases per target and seed> <first seed> <number of seeds> [target]
scripts/sbtw "fuzz 100000 1000 8 block-header"   # only targets whose name contains the text
```

The suite runs in a forked JVM (`-Xmx1536m`) from the repository root, so a hang or an out-of-memory ends the fork
and not the build server. The cases are deterministic functions of (target name, seed, case number): the same
command repeats the run.

### Early-era positive seeds (#179)

The default campaign on the audit baseline `c9f0ba99` accepted 434 Shelley and 160 Allegra
whole transactions in 20,000 cases each, but no Mary transactions. All three targets were
exempted from the seed guard. The repaired corpus adds native-serialized transactions for each
era, and every registered target now needs a positive seed in that guard's own run.

On 2026-10-20, `fuzz 100000 1 4 tx-whole` passes with the following decoded-input counts;
these are decoder results, not ledger-admission verdicts:

| Era | Generated inputs | Decoded inputs |
|---|---:|---:|
| Shelley | 400,000 | 10,843 |
| Allegra | 400,000 | 5,851 |
| Mary | 400,000 | 3,050 |
| Alonzo | 400,000 | 11,009 |
| Babbage | 400,000 | 16,911 |
| Conway | 400,000 | 49,362 |

The same four-seed campaign passes the block/preparation agreement target with 44,634 decoded
inputs and the Plutus Data target with 50,814, each out of 400,000. The block selector also runs
the existing Leios block target (15,378 decoded inputs). No target is exempted from the default
suite; targeted command filters select the extended campaign's scope.

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
start it by hand with other values from the Actions tab (`cases`, `first-seed`, `seeds`). The nightly keeps its
rotating seeds. A pull request that changes a decoder or a fuzz input also runs the fixed-seed gate
`fuzz 100000 1 4` in `.github/workflows/fuzz-gate.yml` (changed paths under `fuzz`, `codec`, `crypto`, `protocol`,
`ledger`, `plutus`, `models`, `models-gen`, `fixtures`, the node-to-client and mempool packages of `node`, `build.sbt`,
`project`, `scripts/sbtw` and the workflow itself). The job always reports; when none of those paths changed it
skips the fuzz steps and passes. It can be started by hand from the Actions tab, where it always fuzzes.

## `capture`: recording blocks

`xyz.kd5ujc.scarab.node.tools.CaptureMain` records raw blocks (one hex-encoded block per line) for offline replay tests:

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.tools.CaptureMain capture \
  --peer <host>:<port> --magic M --blocks N|all --out <file> [--from <slot>.<hashhex> --from-block-no N]
```

A non-genesis `--from` requires its exact `--from-block-no` for the native ChainSync policy.
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
| `soak [--seconds 600] [--every 20] [--seed 1] [--governance-extended] [--oracle]` | `up` + Scala follower + ADA, staking and Conway governance/Plutus V3 load + periodic comparison; `--governance-extended` adds the scenarios of [`govextra.py`](../scripts/devnet/govextra.py) (below; needs `--seconds 900`) | exit status fails on any mismatched comparison or follower error; with `--governance-extended` also on a transaction the reference was expected to refuse but did not refuse for the named predicate failure, and on an extended stage that was not reached. Compares at matching tips: UTxO, fees, deposits, treasury, reserves, accounts, pools, DReps, snapshots, reward update, Praos nonces, governance state. At the end, while the follower is up, scrapes `/metrics` on the query server and on the `--metrics-listen` listener (`127.0.0.1:12798` in the devnet) and fails unless every series of the documented list is there, the text is well formed, `cardano_node_metrics_blockNum_int` and `_epoch_int` lie between the last `extended` event of the log read before and after the scrape, `scarab_blocks_applied_total` equals the number of `extended` events within the same bracket, every applied block was timed, and `scarab_validation_errors_total` is 0 (`metrics` in the soak's `summary.json`) |
| `e2e [--timeout 1500] [--until-epoch N] [--keep] [--no-load] [--no-compare] [--min-comparisons 3] [--oracle]` | `up-eras` (Byron, Shelley, Allegra, Mary, Alonzo, Babbage, Conway) + follower from Origin + era-specific load + per-era ledger comparison; in Conway the chain is driven by governance to the highest protocol version the reference supports ("Protocol versions in the e2e", below) | the `eraTransition` lines are exactly the seven eras at the configured epochs; every era applied blocks; all Byron signatures verified; no validation or fatal errors; every comparison matched, at least `--min-comparisons` per Shelley-based era; every stage of the light extended set (see below) done, no transaction the reference must refuse accepted or refused for another reason; the chain reached the highest protocol version the reference supports; every version-specific check ran at a version it applies to; no protocol version the reference supports lacks a registered check (the coverage guard). Writes `e2e-summary.json` (with `extended`, `extended.versionChecks` and `protocol` blocks) and `compare.jsonl` |
| `peercut [--backlog-blocks 150] [--cut-after-blocks 30] [--delay 0.1] [--sync-timeout 240] [--follow-seconds 30]` | `up`, the pools grow a backlog, then the Scala node (no database) follows pool 1 through `scripts/devnet/lagproxy.py` (a TCP proxy that holds every chunk `--delay` seconds) and pool 2 directly; after `--cut-after-blocks` applied blocks the proxy goes silent (its connections stay open, nothing is forwarded), which the node sees only as ChainSync timeouts (issue 130) | the node reaches the reference tip through pool 2 and keeps extending, the lost peer is demoted with the closed `Transient` policy (timeout or the chain-window detachment tracked in #152), the governor made both peers hot (`peerSelectionPromote`) and demoted the silent one (`peerSelectionDemote`), no `Submarine` and no `stalled` and no `error` event, no unparsed output |
| `relay [--seconds 300] [--peers 3] [--relay-mode ...] [--max-ntn-version 15\|16] [--keep]` | the Scala node follows the pools and serves a non-producing Haskell relay (`--listen`, `--db`, `--local-socket`); load is submitted through the relay | the Haskell relay syncs the whole chain from the Scala node and stays at the tip (lag samples every `--every` seconds), transactions travel relay, Scala mempool, pools, block, and one is submitted with `cardano-cli` to the Scala socket. G2: the Haskell relay connects to the Scala node (it is in the relay's topology; the Scala node has no `--peer` for it) and, with the default `--relay-mode InitiatorAndResponder`, the Scala node's own ChainSync client follows the relay over that same inbound duplex connection: `GET /connections` shows an established inbound duplex connection and an inbound session with an intersection that keeps receiving headers and whose peer tip is within 3 blocks of the pools', and the log has the `inbound` events `duplex` and `following`. G4: the relay's handshake trace (`relayHandshake` in the summary) shows what it proposed and that the Scala node accepted `NodeToNodeV_15` by default, or `NodeToNodeV_16` with `--max-ntn-version 16`, which sets `ExperimentalProtocolsEnabled` to true in both configurations (`cardano-testnet` writes true, so the default run turns it off for the two nodes under test; the pools keep theirs) |
| `ntc [--seconds 240] [--warmup 60] [--every 10] [--chain-sync-blocks 60] [--monitor-burst 4]` | a fresh cluster, the Scala node following pool 1 with `--local-socket` and `--db` (and no `--mempool-bytes`, so both nodes use the default of two blocks), and the ordinary and governance load running. Rounds of LocalStateQuery: the Scala node's volatile tip is acquired on the reference node by point, and every query of the battery (`scripts/devnet/ntcqueries.py`, about 55 queries built from the live state) goes to both. Then `cardano-cli conway query ...` (24 commands) against both sockets, each pair accepted only when the tip read before and after is the same block on both nodes. Then 21 refused transactions (`scripts/devnet/ntccases.py`: fee too small, inputs spent, value not conserved, validity interval, missing witness, small and wrong-network outputs, treasury value, registration, delegation, DRep, pool retirement, proposal, vote, size, size with an input that does not exist, withdrawal network) and one valid transaction through both sockets. In parallel with the query rounds, local ChainSync on both sockets (`scripts/devnet/ntclocal.py`): `--chain-sync-blocks` blocks from Origin (the Scala node reads its immutable store and then its volatile chain), 12 from the tip, 4 as a backlog from a recent block. Last, LocalTxMonitor: snapshots of both mempools walked step for step while bursts of `--monitor-burst` chained transactions go through the Scala socket (which forwards them to the reference node), `cardano-cli conway query tx-mempool info | next-tx | tx-exists` on both sockets at the same time, and `AwaitAcquire` on both | every query that is implemented gives equal bytes in every round (matched / total per query); every `cardano-cli` pair equal; every refusal equal as raw `ApplyTxErr` bytes and as the text `cardano-cli transaction submit` prints; the valid transaction is accepted by the Scala node and reaches a block; every block of local ChainSync equal as the raw item and the same first instruction (a roll-back to the point found); every pair of mempool snapshots with the same slot and transactions has equal raw answers (at least 3 rounds with transactions), the bracketed `tx-mempool` outputs are equal (one with transactions) and `AwaitAcquire` wakes on both. Queries not answered yet are listed separately as missing implementations; they are not conformance passes. The four ledger-state wrappers and bracketed CLI ledger-state output must match without field exclusions. Writes `ntc-summary.json` |
| `pool [--seconds 480] [--pool 1\|2\|3] [--keep]` | the Scala node is one of three pools: pool 1's Haskell node is never started and the Scala node forges with its keys; `slotsPerKESPeriod = 250` | blocks forged, adopted and on the final chain; the Haskell ledger's blocks-made count for the pool equals the Scala-forged blocks on the chain; every slot the reference node's `query leadership-schedule --current` gives the pool (asked once per epoch) is either forged or taken by another pool's block, and a missed one is reported with the `forgeSkipped` reason; load transactions in Scala blocks; at least one KES period crossed; no rollback deeper than `k`; Haskell pools agree on the tip; comparisons match; no error event; the forger's own counters (`GET /forging`, per epoch: slots checked, leader slots, forged, adopted, lost slot battles, skips by reason, forge latency) agree with the log and, scraped from `GET /metrics` between two readings of `/forging`, the Haskell-named series `Forge_about_to_lead_counter`, `node_is_leader_counter`, `forged_counter`, `adopted_counter` and `didnt_adopt_counter` lie between the two readings and the KES gauges exist and no block took more than five slots from its slot's start to adoption. `--byron-start-offset N` writes the Byron genesis' `startTime` N seconds off the Shelley `systemStart` (the reference's clock follows the Byron one; the check that the Scala node does too). Records the host load; a run whose only misses are busy slots on a host with load above its CPU count exits 2 (inconclusive). Writes `pool-summary.json` (`forgerStats`, `slotClock`, `oracle`, `host`) |
| `kes-rotate [--seconds 300] [--max-kes-evolutions 24] [--rotate-threshold 21] [--rotate-every 10] [--min-rotations 2]` | `pool` with unattended KES rotation (H2a): `slotsPerKESPeriod = 250`, `maxKESEvolutions` 24, the Scala node reads its key and certificate through a `current` link and serves a local socket; `scripts/pool/rotate_kes.py` runs on a loop with the pool's cold key (never mounted into the node) and rotates every three periods | everything `pool` asserts, and: at least `--min-rotations` `kesReloaded` events with strictly increasing counters; one `forgeStarted` (no restart) and no `kesReloadRefused` or `kesPersistFailed`; every driver run exits 0; blocks forged with every certificate counter but perhaps the last, none with an older counter after a newer one and none before its certificate was reloaded; the pool's counter as a Haskell node reports it (`query kes-period-info`) equals the last counter on the final chain, so the Haskell pools took the blocks of every certificate; only the newest generation directory remains and no temporary key file; the key file was rewritten by the node after the last certificate was installed. Writes `pool-summary.json` with a `kesRotation` block |
| `restart [--seconds 20]` | a fresh cluster, the Scala node follows with `--db`, is stopped with SIGTERM and started again on the same V5 database | the stop ends the JVM within 25 s and writes a shutdown snapshot (issue #89); the second start logs `snapshotRestored`; every snapshot file is current (`SCRBSNP5`). The retired `--migrate-from` old-writer round is no longer accepted; historical conversion evidence uses pinned source and an offline recipe |
| `v1-context [--eras] [--no-up]` | a fresh Conway cluster (or, with `--eras`, the cluster that walks Byron to Conway) and a set of transactions that each run one Plutus V1 script (some also V2 and V3 as controls): reference inputs, reference scripts, inline datums and Byron addresses in inputs, reference inputs and outputs, with the validity flag both ways; Alonzo 5 and 6, Babbage 7 and 8, Conway 9 (`--eras`) and the genesis protocol version of the Conway-only cluster | exit status fails on a case the reference decided otherwise than the case expects; writes `v1-context-cases.json` (the shape of `fixtures/reference/v1-context`, see [its README](../fixtures/reference/README.md)); `follow` against the same cluster validates the blocks of the accepted cases |

`--scala-cpus N` (`follow`, `soak`, `relay`, `pool`, `e2e`; default 2) sets the CPUs of the Scala node's
container, and `--max-starvation-warnings N` makes a scenario fail when the node logged more cats-effect
CPU-starvation warnings than that (otherwise they are only reported: `cpuStarvationWarnings` in
`e2e-summary.json`, and the `scheduling` block of `pool-summary.json` with the worst timer lateness and
the longest CPU-bound step the node measured). `pool --scala-cpus 1` is the check that the forger's slot
clock holds with one CPU (roadmap D8).

`--consensus-mode GenesisMode` on `follow`, `restart`, `relay`, `e2e` or `soak` selects Genesis
for Scarab using a separate run-local configuration and a topology naming only that
run's controlled reference pools as trustable local roots. The reference pools keep
their original configuration. Restart retains the explicit mode on both starts;
soak passes it to its follower. `PraosMode` explicitly selects Praos; omitting the
option retains the harness's existing configuration. See the [Genesis design](design/genesis.md)
for the reference contracts and acceptance gates.

`--oracle` (on `e2e` and `soak`) makes the follower write a state digest at every epoch boundary and ends the run with the db-analyser comparison described under "Ledger state against the Haskell node"; `devnet.py oracle` repeats that comparison on a cluster that is still up.

Building blocks (also runnable on their own): `up` (start the 3-pool cluster), `up-eras [--epoch-length 500 --active-slots-coeff 0.2 --byron-epochs 1 --byron-slot-ms 1000 --stall-seconds 90 --shelley-epochs 1 --min-utxo-value 0 --pool-margin 0 --cross-rewards]`, `capture-eras [--out fixtures/eras --conway-blocks 3]`, `sample-chain-dep --out FILE [--interval 0.8 --spacing 100 --until-epoch 9]` (run next to `up-eras`: acquires the reference node's volatile tip once per poll and records its DebugChainDepState answer with the era, epoch and tip point of that same acquire; `assemble-chain-dep --points FILE --out DIR` then keeps the points whose block is in a `capture-eras --out DIR` chain, as `fixtures/chain-dep`), `tip [--node N]`, `follow [--seconds N] [--node N]`, `load [--seconds N --wallets 10 --stakers 4 ...]`, `govload [--seconds N]`, `compare`, `down`.

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
| B5 protocol 11 | reference inputs that are inputs (before 11: refused; at 11: accepted without scripts, refused with a Plutus V3 script); votes of committee hot keys that are authorised but not elected (refused at 11, accepted once elected); ten pool registrations around a VRF key hash another pool holds, or has scheduled. The first, second and last are registry checks run by `e2e` at the version the chain is at (`protocolversions.VERSION_CHECKS`); the unelected-voter case needs the full set (`soak --governance-extended`) | the refusals above |

`e2e` starts the light subset (script credentials, reward accounts, the reference-input cases and the VRF cases) in its
Conway epochs and judges it (`govextra.extended_outcome`). A stage whose last attempt reported `ok: false`, an applicable
stage that is not done, and any case marked "refused" that the reference did not refuse as expected all fail the run. The
`cardano-cli` answer to a refused case is classified (`eraload.submission_verdict`): `refused`, `wrongReason`, `accepted` (a
divergence: the stage ends failed and is not retried, since the accepted transaction changed the state the case depends on)
or `noVerdict` (the CLI or `docker exec` failed before the ledger answered: submitted again up to three times, each attempt
logged with the exit code and the raw output). `soak --governance-extended` applies the same refusal accounting. A stage of a
version the chain never reached is reported under `extended.notApplicable` of `e2e-summary.json` with its reason (the VRF
rule applies from version 11: `hardforkConwayDisallowDuplicatedVRFKeys`, `Shelley/Rules/Pool.hs:265`); a stage whose version was
reached gets up to ten minutes past the target epoch (`EXTENDED_WAIT`) and fails the run if it is still not done.

#### Protocol versions in the e2e

Protocol versions up to Babbage's 8 come from the update mechanism, one per epoch (`protocolMajorByEpoch` of `eras.json`,
Conway starts at 9). The Conway versions are governance: a HardFork action per version, ratified one per epoch boundary.
`e2e` drives them dynamically (`scripts/devnet/protocolwalk.py`, run inside the extended-governance thread with its wallets):

* **The target.** Once the tip is in Conway the run asks the reference node `GetMaxMajorProtocolVersion` (LocalStateQuery,
  Shelley-based query 38, node-to-client 21 and later) through `scripts/devnet/ntcquery.py`. That is the
  `MaxMajorProtVer` of the reference's consensus layer: the highest version the node accepts in a block header, a property
  of the pinned image (11 for cardano-node 11.1.3; the recorded answer is in `fixtures/ntc-golden`). The configuration key
  `MaxKnownMajorProtocolVersion` is not it (that is the Byron-era setting) and the ledger maximum cannot be read from outside.
  A reference that does not answer, answers with something that is not a version or negotiates an older node-to-client
  version fails the run (`MaxProtocolUnknown`); there is no literal fallback.
* **The walk.** While the version is below the target, one HardFork action to version + 1 is proposed, with the previous
  hop (pending or enacted) as `prev`, and voted yes by every body that may vote: from the first Conway epoch by the pools
  alone (version 9 is the bootstrap phase, where DReps and the committee cannot vote and the DRep thresholds are zero), and
  from version 10 by the DReps, the committee and the pools, as soon as the base governance round has elected and
  authorised the committee (`gcommittee`) and the DRep delegations count in the distribution. `EraLoad.dynamic_forks` removes
  the scripted hops to 10 and 11 from the base rounds, so the walk is the only source of HardForks (`soak
  --governance-extended` keeps its scripted competing forks to 11, which B2 depends on). The devnet's `govActionLifetime` is
  1, so a hop is proposed and voted within one epoch, in its first 60 %, and the next hop one epoch after it (never in the
  same epoch: the enactment of a HardFork delays the rest of its ratification run past the lifetime of the later hop).
  A hop that is no longer pending while the chain has not reached its version fails the walk (`HardForkLost`).
* **How long it takes.** The run waits past its target epoch (`--until-epoch`, default Conway start + 7) for the walk and the
  checks of the versions reached. The walk's own limit is derived from the governance parameters:
  `(hops left + 1) * (govActionLifetime + 2) + 2` epochs of the run's epoch length and slot length
  (`protocolwalk.walk_bound_epochs`); exceeding it fails the run (`WalkStalled`). Governance cannot start before Conway, so
  the pre-Conway epochs cannot overlap with it; the earliest hop is proposed in the first Conway epoch, as soon as the
  extended thread has funded its wallets and registered the pools' reward accounts. Each later hop is bound by the rules above
  (committee election, one hop per epoch): with the pinned reference, Conway starting in epoch 10, the hop to 10 is in force from epoch 12
  and the hop to 11 (proposed in epoch 14) from epoch 16; the scripted hops had the chain at protocol 10, not 11, at the default target epoch 17
  (so the VRF stage was not applicable).
* **Checks by version.** `scripts/devnet/protocolversions.py` holds `VERSION_CHECKS`: each check names the extended stage
  it is, the versions it applies to (`since`..`until`, or open-ended) and the highest version it was verified through. A check
  runs when the chain is at an applicable version (`xrefoverlap-pre11` at 9 and 10, `xrefoverlap-11` and `xvrf` from 11;
  `xunelected` belongs to the full set and is not counted for the e2e). `e2e-summary.json` has `extended.versionChecks`
  (per check: `applies`, `status` of `done`, `notApplicable`, `failed` or `missed`, `ranAtProtocol`, `tries`) and `protocol`
  (the plan, the hops with their epochs, the first epoch each version was in force, the failure if any).
* **The coverage guard.** The run fails when the reference supports a Conway protocol version that no registered check covers
  (from `since` to `verified_through`) and that `NO_VERSION_CHECKS` does not list with a reason. A new cardano-node that raises the
  maximum therefore fails `e2e` until someone reads what changed and either extends a check's `verified_through` (the rules
  still hold) or adds a check or a `NO_VERSION_CHECKS` entry. The failure is found when the maximum is read, logged at once, and
  reported at the end; the chain is driven only up to the highest version the registry knows. A new era (Dijkstra) is out of
  scope: it needs its own builders in `eratx.py`, and the guard fails there until the registry and the builders are extended.
  The unit tests of the registry, the guard and the walk are `scripts/tests/test_protocol_versions.py` (no Docker); one of them keeps the
  registry's highest verified version equal to the Scala node's own `HardForkConfig.MaxMajorProtVer`.

Recorded results and the commits they were obtained on are in
[compatibility.md](compatibility.md) (evidence log).

BlockFetch coordination retains bounded candidate histories. ChainSync uses the pinned reference's
rolling low/high policy, collecting and refilling after handled replies rather than waiting for a
fixed batch. Its default watermarks are 200/300. `--read-ahead` bounds ordered body-publication
chunks independently and retains its default of 128.
