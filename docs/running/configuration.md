<!-- SPDX-License-Identifier: Apache-2.0 -->
# Configuration, topology, peers and networks

Part of [Running the node and the devnet scenarios](../running.md).

## Configuration and topology files

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
| `LowLevelGenesisOptions` | the reference's `EnableCSJ`, `EnableLoEAndGDD`, `EnableLoP`, `BlockFetchGracePeriod`, `BucketCapacity`, `BucketRate`, `CSJJumpSize` and `GDDRateLimit`; [defaults and resource policy](../design/genesis.md#mechanisms-and-resource-policy) follow the pinned production configuration |
| `PeerSharing` | used by both handshakes and the governor; absent means on for a relay and off with `--forge`, an explicit Boolean wins. Both peers must enable it. Eligible connected peers are asked below the known target; only advertised, previously connected peers with no failures are shared (G1.4) |
| `ExperimentalProtocolsEnabled` (also `TestEnableDevelopmentNetworkProtocols`, which must agree) | the highest node-to-node version the node offers and accepts (G4): absent or `false`, **15**, the latest release of cardano-node 11.1.3 (14 and 15 are offered, as the reference does, `Cardano/Node.hs` 456 and `limitToLatestReleasedVersion`); `true`, **16**, which adds the Peras flag to the version data (always sent as false: the node has no Peras mini-protocols). A relay or pool of the reference needs the same key to agree on 16. The public Leios testnet sets it; its relay accepts 14 to 16. Node-to-client versions 16 to 23 are always accepted. The devnet `relay` scenario takes `--max-ntn-version 16` to turn the key on for the Scala node and the Haskell relay |
| `SocketPath`, `DatabasePath`, `MempoolCapacityBytesOverride` (a byte count; `NoOverride` is the default of two blocks) | the defaults of `--local-socket`, `--db` and `--mempool-bytes`, with `--config` only; a flag wins |
| `AcceptedConnectionsLimit` | an object with `hardLimit`, `softLimit` (non-negative integers) and `delay` (seconds), all three required, as the reference's `FromJSON AcceptedConnectionsLimit` (`POM.hs` 373-375, cardano-node 11.1.3); default `512`, `384`, `5`. `--max-inbound` overrides `hardLimit` |
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

### Choosing the consensus mode

Without a `ConsensusMode` in the configuration the node runs in `PraosMode`, as cardano-node 11.1.3
does; a `--config` that sets it is honoured as written ([ADR 0022](../adr/0022-defaults-follow-the-reference-binary.md)).
The configuration files published for mainnet, preprod and preview set `GenesisMode`. To sync from an
empty database, use `GenesisMode` (`--config` with `"ConsensusMode": "GenesisMode"`, no bootstrap peers), or in
Praos mode give the topology `bootstrapPeers` or a local root with `"trustable": true`. A node that
starts in Praos mode with an empty or far-behind chain and neither logs the `praosSyncUntrusted`
warning once (`consensusMode`, `start`, `slotsBehind`); it changes nothing else.

## Peer selection

The outbound peers are chosen by the peer governor (roadmap G1.1, [design](../design/peer-selection.md),
[ADR 0016](../adr/0016-peer-states-and-the-governor.md)), which follows the reference
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
  `peerChurnGovernor`) is the one writer of requested targets. Praos starts with a 3 s head start for local roots;
  Genesis starts with its complete selected targets. It then sleeps 3300 s plus up to 600 s in Genesis or Praos deadline
  mode, or 900 s plus up to 60 s in Praos bulk sync, and runs a
  round. A round changes one target at a time, in the reference's order, and waits for the counters or a timeout: it lowers
  the active target by 20% (at least one), so that the governor demotes the least useful hot peers, restores it so that
  others are promoted, then does the same for the established and the known sets (steps 7 to 12; steps 1 to 6 do the same for
  the big ledger peers). All twelve steps run even when ledger querying is disabled, and configured big-ledger targets
  remain intact. In Praos sync regimes the active targets follow the local roots' valency (`Regime`, design section 1.10);
  Genesis always uses the default churn regime. A regime change leaves the current sleep and targets untouched until
  the next native update point. The log events `peerSelectionChurn` and the `scarab_peer_selection_churn_*`
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
  See the [as-built contract](../design/block-fetch-coordination.md).
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
  120- or 180-second repromotion delay. [Design and acceptance](../design/genesis.md).
  Topology reload, SRV/TTL resolution and duplex outbound service are implemented in [#142](https://github.com/KD5UJC-XYZ/scarab/issues/142).
  The Genesis LocalRootsOnly relay on `3819a43af` passed sync, duplex following and transaction
  inclusion (2026-10-21); earlier failed relay runs remain historical evidence. See the
  [compatibility evidence](../compatibility.md).

## Preview

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
genesis-to-tip numbers are in [performance.md](../performance.md). The run needs about 14 GB of disk
for the immutable blocks, RocksDB and checkpoints.

**Memory.** The resident anonymous memory of the process is the heap limit plus about 0.8 GB: with
`-Xmx3g` it was 3.86 GB after 45 minutes of a Preview sync (heap 3.0, the JVM's own native memory 0.3,
RocksDB 0.26, malloc 0.23), and the page cache of the files the node writes comes on top and is given
back by the kernel under a limit. **Set `MALLOC_ARENA_MAX=2`** (the `scarab-jdk` image and the
devnet do): with glibc's default of one arena per thread the same sync grew by 0.9 GB in 30 minutes and
had reached 5.66 GB in the uninterrupted run (roadmap K6, [performance.md](../performance.md) section 8).
A container limit of `-Xmx` plus 2 GiB (5 GiB for `-Xmx3g`) is enough; `--utxo-cache-mb` is the other
number that moves native memory. The page cache shows in `docker stats` only partly (as the cgroup's
active file pages) and in `memory.current` fully: bound `RssAnon`, not `memory.current`.

## Preprod

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
The recorded run is in [performance.md](../performance.md).

**With one or two CPUs** (`docker run --cpus 1`, a small VM) start the JVM with
`-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`. A JVM that finds one CPU picks the serial
collector, whose pauses stop every thread: on Preview it made 7,595 collections in 420 s (11 full, the
longest 396 ms) and the node's timers fired late (cats-effect starvation warnings, worst 674 ms); with
the flags above a 31 min sync had none, at the same blocks per second as the base build
([performance.md](../performance.md) section 6). A fixed heap avoids the full collections that come from
growing it. The node keeps its follower on its own pool (`--cpu-threads`, default one worker per CPU)
and reports the worst timer lateness and the longest CPU-bound step of every epoch in the log.

Stopping: SIGTERM or SIGINT (`docker stop`, Ctrl-C) cancels the node, which closes its servers and
writes a snapshot at the immutable tip before it exits (a second or two on Preview; allow
`docker stop -t 60`). `kill -9` is recovered from, at the cost of replaying the stored blocks since the
newest periodic snapshot.
