<!-- SPDX-License-Identifier: Apache-2.0 -->
# Logging

Part of [Running the node and the devnet scenarios](../running.md).

## Logging

Every line the node writes is a typed event (roadmap J3a, [#92](https://github.com/KD5UJC-XYZ/scarab/issues/92);
[ADR 0014](../adr/0014-typed-log.md)): it has a kind, a **namespace** `Area.Kind` (`Chain.Extended`) and a
**severity** (`Debug`, `Info`, `Notice`, `Warning`, `Error`, `Critical`, `Alert`, `Emergency`, the levels of the
Haskell node's trace-dispatcher). The JSON line is `{"event": ..., "at": ..., <fields>}`, the `event` being the
kind's name (`extended`); the catalogue below lists every kind. **Without a `--log-*` flag** every line of the node
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
| `plutusCrypto` | `Node.PlutusCrypto` | Info | `platform`, `library` (`source`, `path`, `sha256`) |
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
| `diffusionErrored` | `Node.DiffusionErrored` | Critical | `error`, the reference's text of the failure; the node-to-client socket could not be set up (its bind, or the status of the socket or its directory, failed) or its accept loop stopped, and the node exits with 1 |
| `memory` | `Node.Memory` | Info | `sample` |
| `cpuStarvation` | `Node.CpuStarvation` | Warning | `clockDriftMs`, `threshold`, `intervalMs` |
| `stateDump` | `Node.StateDump` | Info | `slot`, `hash`, `epoch`, `millis` |
| `stateDumpRefused` | `Node.StateDumpRefused` | Warning | `slot`, `hash`, `epoch`, `error` |
| `verificationCache` | `Node.VerificationCache` | Debug | `summary` |
| `error` | `Node.Error` | Error | `kind`, `message` |
| `socketConfigError` | `Node.SocketConfigError` | Error | `error`, the reference's `show` of its `LocalSocketError`: what was at the `--local-socket` path could not be removed (a directory, a directory the node may not write, ...); the node does not start and exits with 1 |
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
| `stallState` | `Chain.StallState` | Warning | `sessions` (per session: `session`, `busy`, `pendingOffers`, `pendingHeaders`, `active`, `candidateHeaders`, `reserving` (`phase`: `LockWait`, `LockHeld` or `GrantWait`, `runningMs`, or null), `pipeline`: `peer`, `permitsFree`, `permits`, `queued`, `candidateHeaders`, `unpublishedHeaders`, `capacityRoom`, `candidateBase`, `candidateTip`, `publishedBase`, `publishedTip`, `candidateTipSlot`, `publishedTipSlot`, `lastReplyCollectedAgoMs`, `lastReplyAcceptedAgoMs`, `lastReplyHandledAgoMs`), `admissions` (`capacity`, `held`, `queued`, `worker`: `work`, `stage`, `inStageMs`, `runningMs`, or null; `publishWaiting`, `oldestPublishWaitMs`), `decisionLock` (`heldBy`, `runningMs`, or null), `droppedReports` (stall reports replaced before they were logged because the log was stuck, so far), `fiberDump` (`requested`, `skipped`, or null) |
| `stallDump` | `Chain.StallDump` | Warning | `stateFile` and `fiberDump` (each `path`, or `failed` and `message`), logged when the files `--stall-dump` asked for have ended |
| `stallExit` | `Chain.StallExit` | Error | `idleSeconds`, `ourBlock`, `peerBlock` |
| `headersChecked` | `Chain.HeadersChecked` | Info | `validated`, `unchecked`, `waited` |
| `chainSyncRounds` | `Chain.ChainSyncRounds` | Info | `rounds`, `headers`, `avgMs`, `maxMs`, `maxDepth`; legacy batch facade only; production rolling sessions retain the per-reply header counter and do not emit round aggregates |
| `slowFetch` | `Chain.SlowFetch` | Info | `millis` |
| `ledgerEpoch` | `Ledger.LedgerEpoch` | Info | `epoch`, `utxo`, `accounts`, `pools`, `treasury`, `reserves`, `protocolVersion`, `phase2`, `scriptIntegrityHashChecked`, `scheduling` |
| `connection` | `Net.Connection` | Info (Warning when `what` is `refused` or `acceptHardLimit`) | `remote`, `what`, `provenance`, `from`, `to`, `reason`; the accept loop's `acceptRateLimited` (`delaySeconds`, exact, and `connections`), `acceptHardLimit` (`limit`) and `acceptResumed` (`connections`) are the reference's `AcceptConnectionsPolicyTrace` |
| `inbound` | `Net.Inbound` | Info (Warning when `state` is `refused` or `cachedBlockRejected`) | `remote`, `state`, `reason` |
| `serverStarted` | `Net.ServerStarted` | Notice | `addresses` (each `path`); the node-to-client server accepts clients. Like the next four, the reference's `Net.Server.Local` trace (`Started`, `AcceptConnection`, `AcceptError`, `Stopped`, `Error`) |
| `acceptConnection` | `Net.AcceptConnection` | Debug | `address` (`localAddress`, the socket's path, and `remoteAddress`, `<path>@<n>` for the `n`th client accepted) |
| `acceptError` | `Net.AcceptError` | Error | `reason`; an accept failed: after `ECONNABORTED` the server accepts again half a second later, any other failure stops it |
| `serverStopped` | `Net.ServerStopped` | Notice | none |
| `serverError` | `Net.ServerError` | Critical | `reason`; the accept loop stopped on a failure (a `diffusionErrored` line follows) |
| `peerSelectionTargets` | `Net.PeerSelectionTargets` | Notice | `root`, `known`, `established`, `active`, `knownBigLedger`, `establishedBigLedger`, `activeBigLedger` |
| `peerSelectionRoots` | `Net.PeerSelectionRoots` | Info (Warning when `action` is `failure`) | `action`, `bigLedger`, `before`, `after`, `target`, `current`, `found`, `backoffs`, `retryInSeconds`; sharing actions `pickInbound`, `peerShareRequests`, `peerShareResults` add `peers`, `amount`, `results`, `added` |
| `peerSelectionPromote` | `Net.PeerSelectionPromote` | Info (Warning when `action` is `failed`) | `action`, `bigLedger`, `from`, `to`, `peers`, `peer`, `local`, `target`, `current`, `failure`, `retryInSeconds`, `forgotten`, `reason`, `operation` |
| `peerSelectionDemote` | `Net.PeerSelectionDemote` | Info (Warning when `action` is `failed`, or `asynchronous` with a `failure`) | `action`, `bigLedger`, `from`, `to`, `peers`, `peer`, `local`, `target`, `current`, `timedOut`, `failure`, `reason`, `operation`, `repromoteDelaySeconds` |
| `peerSelectionSource` | `Net.PeerSelectionSource` | Warning | `action`, `peer`, `reason`, `sourceGeneration`, `sourceLive` |
| `peerSelectionForget` | `Net.PeerSelectionForget` | Info | `action`, `bigLedger`, `target`, `known`, `peers` |
| `peerSelectionSensitive` | `Net.PeerSelectionSensitive` | Notice | `action`, `judgement` |
| `peerSelectionCounters` | `Net.PeerSelectionCounters` | Info | `root`, `cold`, `warm`, `hot`, `known`, `established`, `active`, `availableToConnect`, `coldPromotions`, `warmPromotions`, `warmDemotions`, `activeDemotions`, `localRoots`, `bootstrap`, `bigLedger`, `origins` |
| `peerSelectionChurn` | `Net.PeerSelectionChurn` | Info | `action` (`round`, `step`, `timeout`, `wait`), `mode`, `step`, `number`, `tookSeconds`, `change`, `delaySeconds` |
| `ledgerPeers` | `Net.LedgerPeers` | Info (Warning when `action` is `snapshotIgnored`, `poolSkipped` or `unreadable`) | `action` (`snapshotLoaded`, `snapshotIgnored`, `snapshotVerified`, `refreshed`, `poolSkipped`, `unreadable`), `matchesImmutablePoint`, `slot`, `pools`, `source`, `bigLedgerPools`, `ledgerSlot`, `pool`, `reason` |
| `localRoots` | `Net.LocalRoots` | Info | `action` (`error`), `domainAddress`, `reason`; a local root's DNS lookup threw. As the reference's `LocalRootError` (Info, ouroboros-network `2fb69829` `RootPeersDNS/LocalRootPeers.hs`), the node goes on and the lookups start again at once |
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
| `startLeadershipCheck` | `Forge.StartLeadershipCheck` | Info | `slot`; the first trace of every slot the forging loop runs. This and the rows down to `adoptedBlock` are cardano-node 11.1.3 `Forge.Loop.*` (one level shallower) with its severities and machine fields (`kind` is the event name), in the order `forkBlockForging` traces them; see [block production](../architecture/block-production.md#the-forging-loop) |
| `slotIsImmutable` | `Forge.SlotIsImmutable` | Error | `slot`, `tip` (`<hash>@<slot>`), `tipBlockNo`: no volatile block, and the immutable tip is in the slot or a later one |
| `blockFromFuture` | `Forge.BlockFromFuture` | Error | `current slot` (the key has a space, as in the reference; quoted in the human format), `tip` (the tip's slot): the chain's tip is in a later slot |
| `blockContext` | `Forge.BlockContext` | Debug | `current slot`, `tip` (the predecessor of the block to forge, `<hash>@<slot>` or `genesis (origin)`), `tipBlockNo` (the number of the block to forge, as the reference fills it) |
| `ledgerState` | `Forge.LedgerState` | Debug | `slot` |
| `noLedgerView` | `Forge.NoLedgerView` | Error | `slot`: the slot is outside the forecast range of the predecessor's ledger state (the stability window after its successor) |
| `ledgerView` | `Forge.LedgerView` | Debug | `slot` |
| `forgeStateUpdateError` | `Forge.ForgeStateUpdateError` | Critical | `slot`, `reason` (`kind` `HardForkForgeStateUpdateError`, `forgeStateUpdateError`: `kind` `KESCouldNotEvolve` or `KESKeyAlreadyPoisoned`, `kesInfo` as in `stateInfo`, `targetPeriod`): the KES key's window has passed and the key is poisoned |
| `nodeCannotForge` | `Forge.NodeCannotForge` | Error | `slot`, `reason` (`kind` `PraosCannotForgeKeyNotUsableYet`, `currentKesPeriod`, `opCertStartingKesPeriod`): the pool leads the slot but its certificate's window has not begun |
| `nodeNotLeader` | `Forge.NodeNotLeader` | Info | `slot` |
| `nodeIsLeader` | `Forge.NodeIsLeader` | Info | `slot` |
| `forgeTickedLedgerState` | `Forge.ForgeTickedLedgerState` | Debug | `slot`, `prev` |
| `forgingMempoolSnapshot` | `Forge.ForgingMempoolSnapshot` | Debug | `slot`, `prev`, `mempoolHash` (the tip the mempool was last synchronised with, or `GenesisHash`), `mempoolSlot` |
| `forgedBlock` | `Forge.ForgedBlock` | Info | `slot`, `block`, `blockNo`, `blockPrev` (or `GenesisHash`) |
| `didntAdoptBlock` | `Forge.DidntAdoptBlock` | Error | `slot`: the block, not refused by our rules, did not become the tip (a slot battle lost at adoption; as in the reference, a block chain selection does not take is not validated) |
| `forgedInvalidBlock` | `Forge.ForgedInvalidBlock` | Error | `slot`, `reason` (`rule`, `reason`: Scarab's rendering of its own rules' refusal, not the reference's `ExtValidationError`): its transactions leave the mempool |
| `adoptedBlock` | `Forge.AdoptedBlock` | Info | `slot`, `blockHash`, `blockSize` (`estimateBlockSize`: header bytes, body size and 10) |
| `startTimeInTheFuture` | `BlockchainTime.StartTimeInTheFuture` | Warning | `systemStart`, `toWait`: the forging loop waits for the system start. This and the next two rows are cardano-node 11.1.3 `BlockchainTime.*` with its severities and machine fields |
| `currentSlotUnknown` | `BlockchainTime.CurrentSlotUnknown` | Warning | `time`: the wall clock is past the horizon of the era history at the tip, so the slot is unknown; the forging loop asks again after 60 s |
| `systemClockMovedBack` | `BlockchainTime.SystemClockMovedBack` | Warning | `prevTime`, `newTime`: the wall clock moved back by 20 s or less, so the slot is kept; further back the node stops (`fatal`, `ClockProblem.SystemClockMovedBack`) |
| `stateInfo` | `Forge.StateInfo` | Info (Warning with seven KES periods left or fewer, Alert with one or none) | `credentials` (`Cardano`), `kind` (`KESInfo`, or `ExpiryLogMessage` with `keyExpiresIn` when seven periods or fewer are left), `startPeriod`, `endPeriod` (the key's current period), `evolution` (the end of the certificate's window): the reference's labels, after each update of the forge state |
| `forged` | `Forge.Forged` | Info | `slot`, `epoch`, `blockNo`, `hash`, `prev`, `txs`, `bodySize`, `kesPeriod`, `relativeKesPeriod`, `stake`, `totalStake`, `opCertCounter`; Scarab's account of a block, after `forgedBlock` |
| `adopted` | `Forge.Adopted` | Info | `block`, `latencyMs`; after `adoptedBlock` |
| `lost` | `Forge.Lost` | Notice | `slot`, `hash`, `reason`; after `didntAdoptBlock`, or when an adopted block is replaced later |
| `kesEvolved` | `Forge.KesEvolved` | Info | `kesPeriod`, `relativeKesPeriod` |
| `kesReloaded` | `Forge.KesReloaded` | Notice | `opCertCounter`, `kesStart`, `kesEvolutions`, `kesPeriod`, `remainingKesPeriods` |
| `kesReloadRefused` | `Forge.KesReloadRefused` | Error | `kind`, `message` |
| `kesPersistFailed` | `Forge.KesPersistFailed` | Error | `kesPeriod`, `kind`, `message` |
| `keyFileGroupAccess` | `Forge.KeyFileGroupAccess` | Warning | `file`, `mode` |
| `forgeFailed` | `Forge.ForgeFailed` | Error | `slot`, `kind`, `message`: a slot given up for a reason of Scarab's own, which the reference does not have |
| `forgeEpoch` | `Forge.ForgeEpoch` | Notice | `epoch`, `slotsChecked`, `leaderSlots`, `notLeader`, `cannotForge`, `stateUpdateError`, `forged`, `adopted`, `lostSlotBattles`, `forgedInvalid`, `replaced`, `failed`, `skipped`, `skippedByReason`, `forgeLatencyMs` |
| `formatMigrated` | `Storage.FormatMigrated` | Notice | `kind`, `from`, `to`, `count` |
| `immutableOpened` | `Storage.ImmutableOpened` | Info | `tip`, `blocks`, `chunks`, `truncatedEntries`, `truncatedBytes` |
| `volatileOpened` | `Storage.VolatileOpened` | Info | `blocks`, `files`, `truncatedBytes`, `removedFiles` |
| `receivedBlockDiscarded` | `Storage.ReceivedBlockDiscarded` | Warning | Malformed unselected received cache entry discarded at restart. |
| `volatileResumed` | `Storage.VolatileResumed` | Info | `resumed`, `dropped`, `millis` |
| `networkRecorded` | `Storage.NetworkRecorded` | Info | `magic`, `shelleyGenesis`, `byronGenesis`, `alonzoGenesis`, `conwayGenesis`, `adoptedExisting` |
| `snapshotSkipped` | `Storage.SnapshotSkipped` | Warning | `file`, `reason` |
| `snapshotRestored` | `Storage.SnapshotRestored` | Info | `file`, `anchor` |
| `snapshotUnbound` | `Storage.SnapshotUnbound` | Warning | `file`, `anchor`: the restored snapshot predates checkpoint binding (V5 migrated to V7) and its checkpoint was admitted by directory name. |
| `snapshotWritten` | `Storage.SnapshotWritten` | Info | `file`, `anchor`, `bytes`, `millis` |
| `snapshotFailed` | `Storage.SnapshotFailed` | Error | `reason` |
| `snapshotRefused` | `Storage.SnapshotRefused` | Error | `file`, `anchor`, `reason` |
| `replayStarted` | `Storage.ReplayStarted` | Info | `from`, `immutableTip` |
| `replayProgress` | `Storage.ReplayProgress` | Info | `tip`, `replayed`, `millis` |
| `replayFinished` | `Storage.ReplayFinished` | Info | `tip`, `replayed`, `millis` |
| `shutdownSnapshot` | `Storage.ShutdownSnapshot` | Info | `anchor`, `written` |
| `slf4j` | `Library.Slf4j` | Info (the library's level: TRACE and DEBUG are Debug, WARN is Warning) | `logger`, `level`, `message`, `thrown` |
