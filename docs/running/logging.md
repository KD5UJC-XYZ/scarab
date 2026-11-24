<!-- SPDX-License-Identifier: Apache-2.0 -->
# Logging

Part of [Running the node and the devnet scenarios](../running.md).

## Logging

Every line the node writes is a typed event (roadmap J3a, [#92](https://github.com/KD5UJC-XYZ/scarab/issues/92);
[ADR 0014](../adr/0014-typed-log.md)): it has a kind, a **namespace** `Area.Kind` (`Chain.Extended`) and a
**severity** (`Debug`, `Info`, `Notice`, `Warning`, `Error`, `Critical`, `Alert`, `Emergency`, the levels of the
Haskell node's trace-dispatcher). The JSON line is `{"event": ..., "at": ..., <fields>}`, the `event` being the
kind's name (`extended`); the catalogue below lists every kind. `at` is when the line is written, except for the
lines of a slot the forger leads, which it holds until the slot's outcome is final (#1101): each keeps the time it
was made, so a block forged ahead of its slot shows a `forged` time before the slot's onset, and a slot's lines can
follow lines of later times. **Without a `--log-*` flag** every line of the node
goes, as JSON, to stdout, except that the libraries' lines (`Library`) and the forging loop's (`Forge`, as the reference's default `Forge.Loop` setting) go from `Info` up, and nothing goes to stderr. The scenarios of `devnet.py` and the run watchers parse
these lines; the field names and values are fixed by a golden (`fixtures/log/golden.jsonl`, `LogGoldenSuite`).

| Flag | Default | Meaning |
|---|---|---|
| `--log-stdout json\|human\|off` | `json` | the stdout sink and its format |
| `--log-stderr json\|human\|off` | `off` | an error stream on stderr; it lets through `Warning` and above unless a `--log-severity stderr:...` says otherwise |
| `--log-file <path>` | off | a log file, appended to (a restart continues it) and rotated by size: at `--log-file-size` it becomes `<path>.1`, the older ones move up to `<path>.<keep>`, and a new `<path>` starts |
| `--log-file-format json\|human` | `json` | with `--log-file` |
| `--log-file-size N[K\|M\|G]` | `100M` | with `--log-file`: the size at which the file turns over (powers of 1024, at least `1K`); a line is never split |
| `--log-file-keep N` | `10` | with `--log-file`: rotated files kept |
| `--log-severity [<sink>:]<namespace>=<Severity\|Silence>` | everything but `Library` and `Forge`, which are `Info` (stderr: `Warning`) | repeatable: the least severity `<sink>` (`stdout`, `stderr` or `file`; every sink without the prefix) lets through for `<namespace>`; `Silence` lets nothing through |
| `--log-every [<sink>:]<namespace>=N` | `1` | repeatable: `<sink>` keeps the first line of every `N` of `<namespace>` (one count for the namespace named) |

A namespace is `*` (everything), an area (`Node`, `Chain`, `Ledger`, `Rules`, `Net`, `Mempool`, `Forge`, `Storage`,
`Library`) or `Area.Kind` as in the catalogue. A setting applies to a namespace and everything below it unless a
longer one sets the same thing: `--log-severity Chain=Warning --log-severity Chain.EraTransition=Info` keeps the era
transitions and the warnings of `Chain`. This is how trace-dispatcher looks its options up (the namespace, then each
shorter prefix). A namespace that does not exist, a sink or severity that does not exist, or a file option without
`--log-file` is refused at start with a `ConfigError`. A setting for a sink that is off does nothing.

A sink that cannot take a line (a full disk, a log file that cannot be turned over) drops it, and the node goes on:
logging never stops what logged. The first failure of a streak is written to stderr directly, with its cause, as
`scarab: the log's file sink ...`, and when the sink takes a line again (or the log closes) another line there says
how many lines it dropped. These reports are written by a writer of their own, so a stderr that blocks holds neither
the node nor its shutdown: reports that cannot wait are counted, and the count is written after the next one. The file
is opened again for the next line, so it recovers once the disk has room or the rotation can move its files.

The human format is `[time][namespace](Severity) event key=value ...` (trace-dispatcher's without the host and the
thread):

```
[2026-10-16T14:48:18.570284Z][Chain.Reconnecting](Warning) reconnecting reason=unavailable: connect 127.0.0.1:9: Connection refused attempt=2
```

The libraries' own logs (http4s, fs2) go through SLF4J to the node's SLF4J backend (`LibraryLogs`, slf4j-api 2.0)
and from there to the same sinks as `slf4j` lines, namespace `Library.Slf4j`, with the library's level as the
severity and the logger name in `logger`. There is no "no SLF4J providers" (formerly `StaticLoggerBinder`) warning.
Without a setting for them, these lines go through from `Info` up ([#112](https://github.com/KD5UJC-XYZ/scarab/issues/112)):
a library's `Debug` (dnsjava's lookups, the native loader's search) is not formatted at all. This is the fallback when
no `--log-severity` covers `Library.Slf4j`, as the Haskell node's default configuration keeps its root at `Notice`
and lowers chosen namespaces only (cardano-node 11.1.3, `Cardano/Node/Tracing/DefaultTraceConfig.hs`). Any setting that covers them
replaces it: `--log-severity Library=Debug` (or `Library.Slf4j=Debug`, or `*=Debug`) lets them through on every
sink, `--log-severity file:Library=Debug` on the file only.

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

`docker logs` (and the monitoring agent, [ops/monitoring](../../ops/monitoring/README.md)) then sees every line but
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

### Log events

The catalogue (`node/src/main/scala/xyz/kd5ujc/scarab/node/log/EventKind.scala`; `LogDocsSuite` fails when this
table and the code differ). The severity is the kind's; the rows marked so are raised for some events.

| Event | Namespace | Severity | Fields |
|---|---|---|---|
| `started` | `Node.Started` | Notice | `build`, `peer`, `peers`, `networkMagic`, `systemStart`, `epochLength`, `securityParam`, `byronEpochs`, `byronEpochSlots`, `hardForkEpochs`, `genesisHashes`, `configUnsupported`, `topology` |
| `blst` | `Node.Blst` | Info | `platform`, `cpu`, `library` |
| `praosSyncUntrusted` | `Node.PraosSyncUntrusted` | Warning | `consensusMode`, `start` (`origin` or `behind`), `slotsBehind` |
| `referenceBuild` | `Node.ReferenceBuild` | Notice | `build` (the `--reference-build` value), `stability` (`stable`, `pre-release` for a release line whose pinned release upstream publishes as a pre-release, or `unstable`), `node` (the pinned release tag, or the node commit of an unstable build), `ledger`, `ledgerBinary`, `consensus` (pinned commits); logged at start, before any store opens; see [flags](flags.md) |
| `unstableReferenceBuild` | `Node.UnstableReferenceBuild` | Warning | `build`, `experimentalHardForksEnabled` (`false`): an unstable build runs without `ExperimentalHardForksEnabled`, which it is meant for |
| `configKeysIgnored` | `Node.ConfigKeysIgnored` | Warning | `keys`, in the file's order: the top-level keys of a `--genesis-dir` configuration the node does not read, so their settings do not apply (tracing keys among them: the log is set with the `--log-*` flags); a `--config` file is read as cardano-node 11.1.3 reads it, which reports none. See [configuration](configuration.md#configuration-and-topology-files) |
| `listening` | `Node.Listening` | Notice | `address` |
| `createSystemdSocketForSnocketPath` | `Node.CreateSystemdSocketForSnocketPath` | Info | `path`; the node-to-client socket is about to be created. Like the rest of these lines, the reference's `Startup.DiffusionInit` trace of that name: `path` is the reference's `show` of the address, `LocalAddress "<path>"`, the path as configured; see [node-to-client](node-to-client.md#the-socket-path) |
| `createdLocalSocket` | `Node.CreatedLocalSocket` | Info | `path` |
| `configuringLocalSocket` | `Node.ConfiguringLocalSocket` | Info | `path`, `socket` (`FileDescriptor <n>`, the socket's descriptor); the bind follows |
| `insecureLocalSocketPermissions` | `Node.InsecureLocalSocketPermissions` | Warning | `path`, `mode` (the socket's whole `st_mode` in octal after `0o`, file type included); others may read or write the socket, checked once after the bind as the reference's `InsecureLocalSocketPermissions` |
| `insecureLocalSocketDirectory` | `Node.InsecureLocalSocketDirectory` | Warning | `path` (the socket's), `mode` (its directory's, as above); the group or others may write the directory that holds the socket, as the reference's `InsecureLocalSocketDirectory` |
| `configuredLocalSocket` | `Node.ConfiguredLocalSocket` | Info | `path`, `socket` |
| `listeningLocalSocket` | `Node.ListeningLocalSocket` | Info | `path`, `socket` |
| `localSocketUp` | `Node.LocalSocketUp` | Info | `path`, `socket` |
| `runLocalServer` | `Node.RunLocalServer` | Info | `localAddress` (as `path`) |
| `diffusionErrored` | `Node.DiffusionErrored` | Critical | `error`, the reference's text of the failure; the node-to-client socket could not be set up (its bind, or the status of the socket or its directory, failed), the node-to-node listener could not be set up (its socket, an option or its bind failed), or the accept loop of either stopped, and the node exits with 1 |
| `memory` | `Node.Memory` | Info | `sample` |
| `cpuStarvation` | `Node.CpuStarvation` | Warning | `clockDriftMs`, `threshold`, `intervalMs` |
| `stateDump` | `Node.StateDump` | Info | `slot`, `hash`, `epoch`, `millis` |
| `stateDumpRefused` | `Node.StateDumpRefused` | Warning | `slot`, `hash`, `epoch`, `error` |
| `plutusCaseNotWritten` | `Node.PlutusCaseNotWritten` | Warning | `file` (absent where the recorder itself failed), `error`; a case of `--plutus-cases` was not written, said after the next block the node applies, and the node goes on, every block judged as without the recorder |
| `verificationCache` | `Node.VerificationCache` | Debug | `summary` |
| `error` | `Node.Error` | Error | `kind`, `message`; the run stops on the error, except for `kind` `Unwritable`: the file of `--assertions-file` could not be opened or written, which is said once, and the node goes on without its assertions |
| `socketConfigError` | `Node.SocketConfigError` | Error | `error`, the reference's `show` of its `LocalSocketError`: what was at the `--local-socket` path could not be removed (a directory, a directory the node may not write, ...); the node does not start and exits with 1 |
| `networkConfigUpdateInfo` | `Node.NetworkConfigUpdateInfo` | Info | `message`, the reference's text: what the reading of the topology says of it, at start-up and at each SIGHUP reload, as the reference's `NetworkConfigUpdateInfo`: a block producer whose topology names a `peerSnapshotFile` with ledger peers off; see [configuration](configuration.md#configuration-and-topology-files) |
| `networkConfigUpdateWarning` | `Node.NetworkConfigUpdateWarning` | Warning | `message`, as above, as the reference's `NetworkConfigUpdateWarning`: a block producer with ledger peers on; in Praos mode a `peerSnapshotFile` with ledger peers off (the file is not read); in Genesis mode a relay without ledger peers, or with them and no `peerSnapshotFile`; and in Genesis mode `bootstrapPeers`, which the node then goes on without; and, at start-up only, a block producer (`--forge`) with `PeerSharing` on, in the reference's words without the `readTopologyFile` prefix (cardano-node 11.1.3 `Run.hs` 360-366) |
| `fatal` | `Node.Fatal` | Error | `kind` (`storage`, `internal`, `defect`, `unavailable`, `unverifiable` or `leiosVoting`), `message`, `exitCode` |
| `finished` | `Node.Finished` | Notice | `tip`, `retained` |
| `connected` | `Chain.Connected` | Info | `intersection` |
| `extended` | `Chain.Extended` | Info | `block`, `epoch`, `era`, `peerTip`: the point of the tip a peer announced most recently, with the last ChainSync reply any of the node's sessions received, or null before any peer has announced one |
| `rolledBack` | `Chain.RolledBack` | Notice | `from`, `to`, `depth` |
| `epoch` | `Chain.Epoch` | Notice | `epoch`, `first` |
| `eraTransition` | `Chain.EraTransition` | Notice | `from`, `to`, `epoch`, `slot`, `block` |
| `epochValidated` | `Chain.EpochValidated` | Info | `era`, `epoch`, `headers`, `thresholdChecked`, `overlay`, `leaderUnchecked`, `scheduling`, `boundary`, `signatureVerified`, `offSchedule`, `transactions` |
| `reconnecting` | `Chain.Reconnecting` | Warning | `reason`, `attempt` |
| `blockNoVerdict` | `Chain.BlockNoVerdict` | Error | `block`, `kind` (`evaluation`, `verification`, `stateOutOfRange` or `defect`), `reason`, `attempt` (1 for the first judgement), `retryInMs` (when the block is judged again, from the end of this attempt: 1 s doubled after each attempt up to 30 s; null for a cause that does not pass on its own, which is judged once, and for a block of our own) |
| `blockNoVerdictRetry` | `Chain.BlockNoVerdictRetry` | Notice | `block`, `attempt`: the node judges again a block it had no verdict on (issue 693) |
| `stalled` | `Chain.Stalled` | Warning | `idleSeconds`, `ourBlock`, `peerBlock` |
| `stallState` | `Chain.StallState` | Warning | `sessions` (per session: `session`, `busy`, `pendingOffers`, `pendingHeaders`, `active`, `candidateHeaders`, `reserving` (`phase`: `LockWait`, `LockHeld` or `GrantWait`, `runningMs`, or null), `pipeline`: `peer`, `permitsFree`, `permits`, `queued`, `candidateHeaders`, `unpublishedHeaders`, `capacityRoom`, `candidateBase`, `candidateTip`, `publishedBase`, `publishedTip`, `candidateTipSlot`, `publishedTipSlot`, `lastReplyCollectedAgoMs`, `lastReplyAcceptedAgoMs`, `lastReplyHandledAgoMs`), `admissions` (`capacity`, `held`, `queued`, `worker`: `work`, `stage`, `inStageMs`, `runningMs`, or null; `publishWaiting`, `oldestPublishWaitMs`), `decisionLock` (`heldBy`, `runningMs`, or null), `droppedReports` (stall reports replaced before they were logged because the log was stuck, so far), `withheld` (the blocks withheld for want of a verdict, by slot: `hash`, `slot`, `kind`, `attempts`, `retryInMs` or null when judged once), `fiberDump` (`requested`, `skipped`, or null) |
| `stallDump` | `Chain.StallDump` | Warning | `stateFile` and `fiberDump` (each `path`, or `failed` and `message`), logged when the files `--stall-dump` asked for have ended |
| `stallExit` | `Chain.StallExit` | Error | `idleSeconds`, `ourBlock`, `peerBlock` |
| `headersChecked` | `Chain.HeadersChecked` | Info | `validated`, `unchecked`, `waited` |
| `chainSyncRounds` | `Chain.ChainSyncRounds` | Info | `rounds`, `headers`, `avgMs`, `maxMs`, `maxDepth`; legacy batch facade only; production rolling sessions retain the per-reply header counter and do not emit round aggregates |
| `slowFetch` | `Chain.SlowFetch` | Info | `millis` |
| `leiosNotifyAnnouncement` | `Chain.LeiosNotifyAnnouncement` | Debug | `connection`, `slot`, `ebHash`, `relayed`, `ageMillis`: an announcement a peer relayed over LeiosNotify, new from that peer and processed (it may already be counted from another); `relayed` is whether its age lets the node relay it (Leios build) |
| `leiosNotifyOffer` | `Chain.LeiosNotifyOffer` | Debug | `connection`, `slot`, `ebHash`, `part` (`body` or `closure`), `ebSize` (a body offer only): an offer a peer made over LeiosNotify (Leios build) |
| `leiosVoteAcquired` | `Chain.LeiosVoteAcquired` | Info | `connection`, `rbHash`, `seat`, `weight`, `tally`, `threshold` (each a rational as `numerator/denominator`): a vote the vote state counted, a peer's or, with `connection` null, one of this node's own (Leios build) |
| `leiosCertified` | `Chain.LeiosCertified` | Info | `rbHash`: a counted vote completed the certificate of the endorser block that block announced (Leios build) |
| `ledgerEpoch` | `Ledger.LedgerEpoch` | Info | `epoch`, `utxo`, `accounts`, `pools`, `treasury`, `reserves`, `protocolVersion`, `phase2`, `scriptIntegrityHashChecked`, `scheduling` |
| `connection` | `Net.Connection` | Info (Warning when `what` is `refused` or `acceptHardLimit`) | `remote`, `what`, `provenance`, `from`, `to`, `reason`; the accept loop's `acceptRateLimited` (`delaySeconds`, exact, and `connections`), `acceptHardLimit` (`limit`) and `acceptResumed` (`connections`) are the reference's `AcceptConnectionsPolicyTrace` |
| `inbound` | `Net.Inbound` | Info (Warning when `state` is `refused` or `cachedBlockRejected`) | `remote`, `state`, `reason` |
| `serverStarted` | `Net.ServerStarted` | Notice | `addresses` (each `path`); the node-to-client server accepts clients. Like the next four, the reference's `Net.Server.Local` trace (`Started`, `AcceptConnection`, `AcceptError`, `Stopped`, `Error`) |
| `acceptConnection` | `Net.AcceptConnection` | Debug | `address` (`localAddress`, the socket's path, and `remoteAddress`, `<path>@<n>` for the `n`th client accepted) |
| `acceptError` | `Net.AcceptError` | Error | `reason`; an accept failed: after `ECONNABORTED` the server accepts again half a second later, any other failure stops it |
| `serverStopped` | `Net.ServerStopped` | Notice | none |
| `serverError` | `Net.ServerError` | Critical | `reason`; the accept loop stopped on a failure (a `diffusionErrored` line follows) |
| `remoteAcceptError` | `Net.RemoteAcceptError` | Error | `reason`; an accept of the node-to-node server failed. Like the next two, the reference's `Net.Server.Remote` trace (`AcceptError`, `Stopped`, `Error`): after `ECONNABORTED` the server accepts again half a second later (the JDK retries that one itself, so it is not seen: an approved deviation, #944), any other failure stops the node |
| `remoteServerStopped` | `Net.RemoteServerStopped` | Notice | none; the node-to-node server stopped accepting |
| `remoteServerError` | `Net.RemoteServerError` | Critical | `reason`; its accept loop stopped on a failure (a `diffusionErrored` line follows) |
| `peerSelectionTargets` | `Net.PeerSelectionTargets` | Notice | `root`, `known`, `established`, `active`, `knownBigLedger`, `establishedBigLedger`, `activeBigLedger` |
| `peerSelectionRoots` | `Net.PeerSelectionRoots` | Info (Warning when `action` is `failure`) | `action`, `bigLedger`, `before`, `after`, `target`, `current`, `found`, `backoffs`, `retryInSeconds`, `defect` (a `failure` whose request raised: the exception; absent otherwise); sharing actions `pickInbound`, `peerShareRequests`, `peerShareResults` add `peers`, `amount`, `results`, `added` |
| `peerSelectionPromote` | `Net.PeerSelectionPromote` | Info (Warning when `action` is `failed`) | `action`, `bigLedger`, `from`, `to`, `peers`, `peer`, `local`, `target`, `current`, `failure`, `retryInSeconds`, `forgotten`, `reason`, `operation` |
| `peerSelectionDemote` | `Net.PeerSelectionDemote` | Info (Warning when `action` is `failed`, or `asynchronous` with a `failure`) | `action`, `bigLedger`, `from`, `to`, `peers`, `peer`, `local`, `target`, `current`, `timedOut`, `failure`, `reason`, `returned` (an `asynchronous` demotion of a hot peer whose ChainSync client returned: `Returned`, `NoMoreIntersection`, `RolledBackPastIntersection` or `AskedToTerminate`, the type of its `reason`; otherwise null), `operation`, `repromoteDelaySeconds` |
| `peerSelectionSource` | `Net.PeerSelectionSource` | Warning | `action`, `peer`, `reason`, `sourceGeneration`, `sourceLive` |
| `peerSelectionForget` | `Net.PeerSelectionForget` | Info | `action`, `bigLedger`, `target`, `known`, `peers` |
| `peerSelectionSensitive` | `Net.PeerSelectionSensitive` | Notice | `action`, `judgement` |
| `peerSelectionCounters` | `Net.PeerSelectionCounters` | Info | `root`, `cold`, `warm`, `hot`, `known`, `established`, `active`, `availableToConnect`, `coldPromotions`, `warmPromotions`, `warmDemotions`, `activeDemotions`, `localRoots`, `bootstrap`, `bigLedger`, `origins` |
| `peerSelectionChurn` | `Net.PeerSelectionChurn` | Info | `action` (`round`, `step`, `timeout`, `wait`), `mode` (the churn mode of a round: `Deadline`, `BulkSync` or `Genesis`), `step`, `number`, `tookSeconds`, `change`, `delaySeconds` |
| `ledgerPeers` | `Net.LedgerPeers` | Info (Warning when `action` is `snapshotIgnored`, `poolSkipped` or `unreadable`; Error when it is `snapshotVerificationFailed`) | `action` (`snapshotLoaded`, `snapshotIgnored`, `snapshotVerified`, `snapshotVerificationFailed`, `refreshed`, `poolSkipped`, `unreadable`), `matchesImmutablePoint`, `slot`, `pools`, `source`, `bigLedgerPools`, `ledgerSlot`, `pool`, `reason` |
| `localRoots` | `Net.LocalRoots` | Info | `action` (`error`), `domainAddress`, `reason`; a local root's DNS lookup threw. As the reference's `LocalRootError` (Info, ouroboros-network `c45735a5` `RootPeersDNS/LocalRootPeers.hs`), the node goes on and the lookups start again at once |
| `outboundConnections` | `Net.OutboundConnections` | Info | `state`, `trusted` |
| `gsmTransition` | `Net.GsmTransition` | Notice | `transition`, `state`, `peers`, `tipTooOld` |
| `fetchModeChanged` | `Net.FetchModeChanged` | Info | `mode` |
| `mempoolAdded` | `Mempool.MempoolAdded` | Info | `tx`, `size`, `count`, `bytes` |
| `mempoolRejected` | `Mempool.MempoolRejected` | Info | `tx`, `reason` (not for a local client's transaction its decoder refuses: that one never reaches the mempool, and its connection's `inbound` `closed` line gives the reason) |
| `mempoolWaiting` | `Mempool.MempoolWaiting` | Info | `tx`, `reason`, and, when the held transactions took longer than the pool's capacity of time to validate (`MempoolTimeoutCapacity`, [configuration](configuration.md)), `usedSeconds` and `capacitySeconds` |
| `mempoolDropped` | `Mempool.MempoolDropped` | Info | `tx`, `reason`, `cause`: `Revalidation` (the pool, validated again at a new tip or after a removal, no longer admits it) or `Withdrawal` (the forger had no verdict on it while it built a block) |
| `mempoolAdopted` | `Mempool.MempoolAdopted` | Info | `tx` |
| `mempoolRemoved` | `Mempool.MempoolRemoved` | Info | `tx` |
| `mempoolSynced` | `Mempool.MempoolSynced` | Info | `count`, `bytes`, `dropped` |
| `forgeStarted` | `Forge.ForgeStarted` | Notice | `pool`, `vrfKeyHash`, `kesStart`, `kesEvolutions`, `opCertCounter`, `kesPeriod`, `remainingKesPeriods` |
| `blockForgingUpdate` | `Forge.BlockForgingUpdate` | Info | `enabled` |
| `startLeadershipCheck` | `Forge.StartLeadershipCheck` | Info | `slot`; the first trace of a slot the pool leads. This and the rows down to `adoptedBlock` are cardano-node 11.1.3 `Forge.Loop.*` (one level shallower) with its severities and machine fields (`kind` is the event name), in the order `forkBlockForging` traces them, all of them for a led slot; a slot not led logs only the line that ends it (the first of `slotIsImmutable`, `blockFromFuture`, `noLedgerView`, `forgeFailed` and `forgeStateUpdateError` that applies, else `nodeNotLeader`), and at a KES period change `startLeadershipCheck` before it, once in the period, with the period's `stateInfo` where the key's update succeeded; it is counted all the same (an approved Deviation, [#1101](https://github.com/KD5UJC-XYZ/scarab/issues/1101)); see [block production](../architecture/block-production.md#the-slots-a-pool-leads) |
| `slotIsImmutable` | `Forge.SlotIsImmutable` | Error | `slot`, `tip` (`<hash>@<slot>`), `tipBlockNo`: no volatile block, and the immutable tip is in the slot or a later one |
| `blockFromFuture` | `Forge.BlockFromFuture` | Error | `current slot` (the key has a space, as in the reference; quoted in the human format), `tip` (the tip's slot): the chain's tip is in a later slot |
| `blockContext` | `Forge.BlockContext` | Debug | `current slot`, `tip` (the predecessor of the block to forge, `<hash>@<slot>` or `genesis (origin)`), `tipBlockNo` (the number of the block to forge, as the reference fills it) |
| `ledgerState` | `Forge.LedgerState` | Debug | `slot` |
| `noLedgerView` | `Forge.NoLedgerView` | Error | `slot`: the slot is outside the forecast range of the predecessor's ledger state (the stability window after its successor) |
| `ledgerView` | `Forge.LedgerView` | Debug | `slot` |
| `forgeStateUpdateError` | `Forge.ForgeStateUpdateError` | Critical | `slot`, `reason` (`kind` `HardForkForgeStateUpdateError`, `forgeStateUpdateError`: `kind` `KESCouldNotEvolve` or `KESKeyAlreadyPoisoned`, `kesInfo` as in `stateInfo`, `targetPeriod`): the KES key's window has passed and the key is poisoned |
| `nodeCannotForge` | `Forge.NodeCannotForge` | Error | `slot`, `reason` (`kind` `PraosCannotForgeKeyNotUsableYet`, `currentKesPeriod`, `opCertStartingKesPeriod`): the pool leads the slot but its certificate's window has not begun |
| `nodeNotLeader` | `Forge.NodeNotLeader` | Info | `slot`: the pool does not lead the slot, and no check before the leader check refused it; one line per slot not led |
| `nodeIsLeader` | `Forge.NodeIsLeader` | Info | `slot` |
| `forgeTickedLedgerState` | `Forge.ForgeTickedLedgerState` | Debug | `slot`, `prev` |
| `forgingMempoolSnapshot` | `Forge.ForgingMempoolSnapshot` | Debug | `slot`, `prev`, `mempoolHash` (the tip the mempool was last synchronised with, or `GenesisHash`), `mempoolSlot` |
| `forgedBlock` | `Forge.ForgedBlock` | Info | `slot`, `block`, `blockNo`, `blockPrev` (or `GenesisHash`) |
| `didntAdoptBlock` | `Forge.DidntAdoptBlock` | Error | `slot`: the block, not refused by our rules, did not become the tip (a slot battle lost at adoption; as in the reference, a block chain selection does not take is not validated) |
| `forgedInvalidBlock` | `Forge.ForgedInvalidBlock` | Error | `slot`, `reason` (`rule`, `reason`: Scarab's rendering of its own rules' refusal, not the reference's `ExtValidationError`): its transactions leave the mempool |
| `adoptedBlock` | `Forge.AdoptedBlock` | Info | `slot`, `blockHash`, `blockSize` (`estimateBlockSize`: header bytes, body size and 10) |
| `startTimeInTheFuture` | `BlockchainTime.StartTimeInTheFuture` | Warning | `systemStart`, `toWait`: the forger's clock waits for the system start. This and the next two rows are cardano-node 11.1.3 `BlockchainTime.*` with its severities and machine fields |
| `currentSlotUnknown` | `BlockchainTime.CurrentSlotUnknown` | Warning | `time`: the wall clock is past the horizon of the era history at the tip, so the slot is unknown; the forger's clock asks again after 60 s |
| `systemClockMovedBack` | `BlockchainTime.SystemClockMovedBack` | Warning | `prevTime`, `newTime`: the wall clock moved back by 20 s or less, so the slot is kept; further back the node stops (`fatal`, `ClockProblem.SystemClockMovedBack`) |
| `stateInfo` | `Forge.StateInfo` | Info (Warning with seven KES periods left or fewer, Alert with one or none) | `credentials` (`Cardano`), `kind` (`KESInfo`, or `ExpiryLogMessage` with `keyExpiresIn` when seven periods or fewer are left), `startPeriod`, `endPeriod` (the key's current period), `evolution` (the end of the certificate's window): the reference's labels. Logged in a led slot's sequence, at the first slot of each KES period (the update of the key at the period's start; on a slot not led after its `startLeadershipCheck`), and, at Warning or Alert, in every slot whose checks reach the key |
| `forged` | `Forge.Forged` | Info | `slot`, `epoch`, `blockNo`, `hash`, `prev`, `txs`, `bodySize`, `kesPeriod`, `relativeKesPeriod`, `stake`, `totalStake`, `opCertCounter`; Scarab's account of a block, after `forgedBlock` |
| `adopted` | `Forge.Adopted` | Info | `block`, `latencyMs`; after `adoptedBlock` |
| `lost` | `Forge.Lost` | Notice | `slot`, `hash`, `reason`; after `didntAdoptBlock`, or when an adopted block is replaced later |
| `notKept` | `Forge.NotKept` | Warning | `slot`, `hash`, `reason`: the forged block could not be kept for a later chain selection, a failure of the node's own that does not stop it (a storage failure does); what selection made of the block is logged as it is otherwise; an approved deviation ([decisions](../decisions.md#what-stops-the-node)) |
| `kesEvolved` | `Forge.KesEvolved` | Info | `kesPeriod`, `relativeKesPeriod` |
| `kesReloaded` | `Forge.KesReloaded` | Notice | `opCertCounter`, `kesStart`, `kesEvolutions`, `kesPeriod`, `remainingKesPeriods` |
| `kesReloadRefused` | `Forge.KesReloadRefused` | Error | `kind`, `message` |
| `kesPersistFailed` | `Forge.KesPersistFailed` | Error | `kesPeriod`, `kind`, `message` |
| `kesFileAbsent` | `Forge.KesFileAbsent` | Warning | `kesPeriod`, `file` |
| `kesWrittenByRename` | `Forge.KesWrittenByRename` | Warning | `reason`, `errno` |
| `kesKeyAhead` | `Forge.KesKeyAhead` | Warning | `keyPeriod`, `kesPeriod`, `message`: at start-up, the KES key read from its file is at a later period than the current one by the wall clock, so it cannot sign before then; restore the unevolved key, or issue a new KES key and operational certificate |
| `keyFileGroupAccess` | `Forge.KeyFileGroupAccess` | Warning | `file`, `mode` |
| `forgeFailed` | `Forge.ForgeFailed` | Error | `slot`, `kind`, `message`: a slot given up for a reason of Scarab's own, which the reference does not have |
| `forgeDefect` | `Forge.ForgeDefect` | Critical | `slot`, `kind` (`BodySize` with `bodySize` and `maxBodySize`, `EndorserBlockSize` with `endorserBlockSize` and `maxEndorserBlockSize`, or `BlockDecode` with `cause`), `evicted` (the ids of the transactions the block held, which left the mempool): the block built in the slot failed a check only a bug of Scarab's can fail; no block, and the node goes on (#987) |
| `forgeEpoch` | `Forge.ForgeEpoch` | Notice | `epoch`, `slotsChecked`, `leaderSlots`, `notLeader`, `cannotForge`, `stateUpdateError`, `forged`, `adopted`, `lostSlotBattles`, `forgedInvalid`, `replaced`, `failed`, `defects`, `skipped`, `skippedByReason`, `forgeLatencyMs`; after every slot of the epoch has been published |
| `leaderSchedule` | `Forge.LeaderSchedule` | Info | `epoch`, `slots` (the slots the pool leads in it), `expected` (the number a pool of its stake leads on average, for comparison), `settled` (whether a block at or after the epoch's freeze slot is immutable, so that no fork can change its nonce): an epoch's leader schedule is complete, or a recomputation changed it. `slots` are the led slots of the range the schedule covers: from the epoch's first slot or, for an epoch computed after it began (the node started, forging was switched on, or a new VRF key or a fork changed its inputs, during it) or a computation that fell behind the clock, from the clock's slot where its computation started or resumed; `expected` is the whole epoch's. Whoever reads the log learns when the pool will lead ([#1101](https://github.com/KD5UJC-XYZ/scarab/issues/1101), decision 7) |
| `forgeDiscarded` | `Forge.ForgeDiscarded` | Debug | `slot`, `reason` (`blockContext`, `inputs` or `credentials`: a block forged ahead of its slot that the chain, the slot's inputs or a reload made stale by the onset, and forged again; `credentials` also for a block whose credentials a reload replaced, or whose forging it disabled, after the block was signed: it is not handed over and the slot ends, as the reference's forging thread of replaced credentials ends; `refused`: an attempt refused before the onset and made again; `notLeader`: the slot is not led under the inputs in force; `certificate`: built without a certificate the node holds at the onset, forged again to certify), `hash` (the block not handed over, or null): an attempt that does not count, its lines dropped, so the log shows one attempt per slot (#1101, Q4) |
| `formatMigrated` | `Storage.FormatMigrated` | Notice | `kind`, `from`, `to`, `count` |
| `immutableOpened` | `Storage.ImmutableOpened` | Info | `tip`, `blocks`, `chunks`, `truncatedEntries`, `truncatedBytes` |
| `volatileOpened` | `Storage.VolatileOpened` | Info | `blocks`, `files`, `truncatedBytes`, `removedFiles` |
| `receivedOpened` | `Storage.ReceivedOpened` | Info | `blocks`, `files`, `truncatedBytes`: the received-block log opened, as `volatileOpened` reports the selected-chain log |
| `receivedTruncated` | `Storage.ReceivedTruncated` | Debug | `file`, `offset`, `cause`: `readFailed`, with `failure` (`unreadable`: the record there cannot be read; `undecodable`, with `reason`: its block does not decode), as the reference's `BlockReadErr`; `corrupted`, with `hash`: its block decodes and fails the start-up check, as `BlockCorruptedErr`, and `hash` is the decoded block's hash, as the reference's is, not the hash the record names when the two differ; `duplicated`, with `hash`, `first` (the file that holds the block) and `second` (this file), as `DuplicatedBlock`. A cut of a file of the received-block log when the log is opened, one line per truncation, logged just before it is attempted (two for a file cut where its parse stopped and then at an earlier repeat; a truncation that then fails fails the open), as the reference's VolatileDB traces `Truncate`, at Debug as it does |
| `receivedInvalidFileNames` | `Storage.ReceivedInvalidFileNames` | Debug | `files`: the files of the received-block log's directory whose names the open does not read as log files, which it leaves alone, as the reference's VolatileDB traces `InvalidFileNames`, at Debug as it does |
| `receivedBlockAlreadyHere` | `Storage.ReceivedBlockAlreadyHere` | Debug | `hash`: a block put in the received-block log that it holds already, not logged again, as the reference's VolatileDB traces `BlockAlreadyHere`, at Debug as it does |
| `scheduledGC` | `Storage.ScheduledGC` | Debug | `slot`, `dueMillis` (the node's monotonic clock): a collection of the received-block log scheduled after a copy to the immutable store, as the reference's ChainDB traces `ScheduledGC`, at Debug as it does |
| `performedGC` | `Storage.PerformedGC` | Debug | `slot`: a collection of the received-block log done, as the reference's ChainDB traces `PerformedGC`, at Debug as it does |
| `receivedBlockDiscarded` | `Storage.ReceivedBlockDiscarded` | Warning | `hash`, `reason`: a block the start leaves out. Either a block of the selected chain that the received log lacks and that fails the log's start-up check, which is not added to the log; or a block of the received log whose header (or, for a Dijkstra block, body) does not decode when the block pool is seeded, which is not seeded (the log's open has already cut every record that fails its check, so this is not expected) |
| `volatileResumed` | `Storage.VolatileResumed` | Info | `resumed`, `dropped`, `millis` |
| `networkRecorded` | `Storage.NetworkRecorded` | Info | `magic`, `shelleyGenesis`, `byronGenesis`, `alonzoGenesis`, `conwayGenesis`, `dijkstraGenesis` (null without one), `adoptedExisting`, `adoptedDijkstraGenesis` (the identity recorded no Dijkstra genesis and adopted the configured one) |
| `snapshotSkipped` | `Storage.SnapshotSkipped` | Warning | `file`, `reason` |
| `snapshotDeleted` | `Storage.SnapshotDeleted` | Info | `file`: a skipped snapshot deleted with its checkpoint, as the reference deletes a temporary snapshot it cannot use (`DeletedSnapshot`); every native snapshot is temporary |
| `snapshotNotDeleted` | `Storage.SnapshotNotDeleted` | Warning | `file`, `reason`: a skipped snapshot that could not be deleted; the restore goes on, as the reference's best-effort deletion does |
| `checkpointNotDeleted` | `Storage.CheckpointNotDeleted` | Warning | `checkpoint`, `reason`: a UTxO checkpoint that no snapshot lists could not be deleted at the start; the start goes on, as the reference's best-effort deletion of a snapshot's tables does |
| `snapshotRestored` | `Storage.SnapshotRestored` | Info | `file`, `anchor` |
| `snapshotUnbound` | `Storage.SnapshotUnbound` | Warning | `file`, `anchor`: the restored snapshot predates checkpoint binding (V5 migrated through V7, V8 and V9 to V10) and its checkpoint was admitted by directory name. |
| `snapshotWritten` | `Storage.SnapshotWritten` | Info | `file`, `anchor`, `bytes`, `millis` |
| `snapshotFailed` | `Storage.SnapshotFailed` | Error | `reason` |
| `snapshotRefused` | `Storage.SnapshotRefused` | Error | `file`, `anchor`, `reason` |
| `replayStarted` | `Storage.ReplayStarted` | Info | `from`, `immutableTip` |
| `replayProgress` | `Storage.ReplayProgress` | Info | `tip`, `replayed`, `millis` |
| `replayFinished` | `Storage.ReplayFinished` | Info | `tip`, `replayed`, `millis` |
| `shutdownSnapshot` | `Storage.ShutdownSnapshot` | Info | `anchor`, `written` |
| `leiosKernelMsg` | `Chain.LeiosKernelMsg` | Info | `message`: the reference's `LeiosKernelMsg` lines of the voting thread, under the Leios build: `runLeiosVoting: disabled because no topLevelConfigVotingKeys` at start without `--shelley-bls-key`, and `newVoteTimers: no wall-clock onset for <slot>@<ebHash>`; and of the forger: `EB not yet downloaded: <slot>@<ebHash>` and `EB downloaded but no certificate: <slot>@<ebHash>`, for a block it forges on a parent whose announcement it does not certify for that reason |
| `leiosVoteScheduled` | `Chain.LeiosVoteScheduled` | Info | `ebHash`, `ebSlot`, `voteIn`, `deadlineIn` (seconds, negative when past): an endorser block's closure was acquired and its vote timer armed for the moment its window opens |
| `leiosEbValidated` | `Chain.LeiosEbValidated` | Info | `ebHash`, `ebSlot`, `reapplied`, `applied`, `duration` (seconds): an endorser block's closure checked valid before a vote, with how many of its transactions were reapplied because the transaction cache held them validated, how many were validated in full, and how long the check took |
| `leiosTxsPrevalidated` | `Chain.LeiosTxsPrevalidated` | Info | `ebHash`, `ebSlot`, `offered`, `independent`, `validated`, `duration` (seconds): a peer's transactions of an endorser block's closure pre-validated before its vote (a voting node only, #79): of the offered ones, those whose every input is on the chain, and those of them validated in full and so tagged in the transaction cache, which the closure check then reapplies |
| `leiosPrevalidationSkipped` | `Chain.LeiosPrevalidationSkipped` | Info | `ebHash`, `ebSlot`, `reason` (`noDijkstraTip`, `otherEpoch`, `tick`, `store`), `detail`: none of a reply's transactions pre-validated, and why; the closure check validates them in full at the vote |
| `leiosVoted` | `Chain.LeiosVoted` | Info | `rbHash`, `seat`, `weight` (a rational as `numerator/denominator`): a vote of this node's, one per committee seat its keys hold, counted in its vote state and relayed by its LeiosNotify servers; `leiosVoteAcquired` (with `connection` null) and, when it completes the certificate, `leiosCertified` follow it |
| `leiosNotVoted` | `Chain.LeiosNotVoted` | Info | `ebHash`, `ebSlot`, `reason` (`chainTipDoesNotAnnounce`, `tooLate`, `notOnCommittee`, `ebTxsInvalid: <refusal>`, `voteRejected: <answer>` for one seat, `voteRejected: all <n> votes rejected`, `closureUnavailable: <error>`; the refusal in Scarab's rendering): no vote was cast for the block, or one seat's was refused |
| `leiosBlockForged` | `Chain.LeiosBlockForged` | Info | `slot`, `hash`, `numTxs`, `ebSize`, `closureSize` (bytes of its transactions), `mempoolRestSize` (0, as the reference writes it): an endorser block forged with a ranking block |
| `leiosBlockAnnounced` | `Chain.LeiosBlockAnnounced` | Info | `rbHash`, `ebSlot`, `ebHash`: the forged ranking block announces the endorser block |
| `leiosBlockCertified` | `Chain.LeiosBlockCertified` | Info | `atSlot`, `ebSlot`, `ebHash`: the block forged in the slot certifies the endorser block its parent announced |
| `leiosCertifiedAndAnnounced` | `Chain.LeiosCertifiedAndAnnounced` | Info | `slotNo`, `rbHash`: the forged ranking block both certifies an endorser block and announces one |
| `slf4j` | `Library.Slf4j` | Info (the library's level: TRACE and DEBUG are Debug, WARN is Warning) | `logger`, `level`, `message`, `thrown` |
