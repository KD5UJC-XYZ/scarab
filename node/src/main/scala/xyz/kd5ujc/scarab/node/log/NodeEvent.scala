// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import com.comcast.ip4s.{Host, SocketAddress}
import xyz.kd5ujc.scarab.blst.{BlstReport, CpuSupport, LibraryBuild}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.{NodeError, Points, ShelleyGenesis}
import xyz.kd5ujc.scarab.node.{
  BuildInfo,
  Fatal,
  InboundEvent,
  LedgerPeersTrace,
  Main,
  NodeConfig,
  Topology
}
import xyz.kd5ujc.scarab.network.selection.{
  AfterSlot,
  Churn,
  FetchMode,
  Gsm,
  GovernorState,
  OutboundConnectionsState,
  Trace,
  UseLedgerPeers
}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** What the node itself reports, apart from the event types of its parts (the follower, the chain
  * database, the mempool, the forger, the connections), which are logged as they are. Closed: every
  * line the node writes is one of these or one of those.
  */
enum NodeEvent:
  /** The node is running: what it is and what it was told. */
  case Started(
      build: NodeEvent.Build,
      peers: List[SocketAddress[Host]],
      genesis: ShelleyGenesis,
      config: NodeConfig,
      topology: Option[Topology]
  )

  /** The BLS12-381 library in use and the processor it runs on (checked at start-up, ADR 0012). */
  case Blst(report: BlstReport)

  /** A listener is open (`--listen`, `--local-socket`). */
  case Listening(address: String)

  /** A [[xyz.kd5ujc.scarab.node.MemoryReport]] sample (`--memory-report-seconds`). */
  case Memory(sample: Json)

  /** The cats-effect runtime's starvation check fired: its timer ran `clockDrift` late, more than
    * `threshold` of its `interval` (`IOApp.onCpuStarvationWarn`, which
    * [[xyz.kd5ujc.scarab.node.Main]] turns into this line instead of the runtime's plain-text
    * warning).
    */
  case CpuStarvation(clockDrift: FiniteDuration, threshold: Double, interval: FiniteDuration)

  /** No block was extended for `idle` while a peer announced a longer chain: the follower is at
    * block `ours`, a peer's tip is block `peers` (issue 130,
    * [[xyz.kd5ujc.scarab.node.StallWatchdog]]).
    */
  case Stalled(idle: FiniteDuration, ours: BigInt, peers: BigInt)

  /** The stall went on for `--stall-exit-after`: the node ends (after its shutdown snapshot, exit
    * code 4) so that its supervisor restarts it from the newest snapshot.
    */
  case StallExit(idle: FiniteDuration, ours: BigInt, peers: BigInt)

  /** A ledger-state digest was written (`--dump-state`). */
  case StateDumped(slot: BigInt, hash: Bytes, epoch: BigInt, millis: Long)

  /** A ledger-state digest was not written: the state is one the mirror cannot hold (a value
    * outside the range of its Haskell type), so the projection cannot be read from it.
    */
  case StateDumpRefused(slot: BigInt, hash: Bytes, epoch: BigInt, error: String)

  /** The verification verdicts found ahead of the rules so far (every 20,000 blocks). */
  case VerificationCache(summary: String)

  /** The run stops on an error. */
  case Failed(error: NodeError)

  /** A session ended in a way no peer policy handles, and the node stops with its own exit code
    * ([[xyz.kd5ujc.scarab.node.FailurePolicy.disposition]], issue 154).
    */
  case Faulted(fault: Fatal)

  /** The run is over: the tip, and how many blocks the volatile chain held. */
  case Finished(tip: ChainSync.Point, retained: Int)

  /** Header-first validation so far: headers that passed every header rule, headers left to their
    * block's validation, and headers that waited for the chain to advance (G6).
    */
  case HeadersChecked(validated: Long, unchecked: Long, waited: Long)

  /** The ChainSync pipeline so far: rounds, instructions, and how long a round took from the first
    * request to the last reply (D3).
    */
  case ChainSyncRounds(rounds: Long, headers: Long, avgMs: Long, maxMs: Long, maxDepth: Int)

  /** A block fetched on its own took this long from the request to the block. */
  case SlowFetch(millis: Long)

  /** What the inbound governor (G2) or the node-to-client listener did on a connection. */
  case Inbound(remote: String, event: InboundEvent)

  /** A decision of the peer governor or what became of one (G1). */
  case PeerSelection(trace: Trace[SocketAddress[Host]])
  case PeerChurn(trace: Churn.Trace)

  /** The ledger-peers worker (G1.3): the snapshot, the stake maps it rebuilt, the pools it skipped.
    */
  case LedgerPeers(trace: LedgerPeersTrace)

  /** The counters of the peer governor changed. */
  case PeerCounters(counters: GovernorState.Counters)

  /** The governor's outbound connections state changed (trusted, or untrusted). */
  case OutboundConnections(state: OutboundConnectionsState)

  /** A transition of the Genesis State Machine; `phase` is the state it is in after it. */
  case GsmTransition(phase: Gsm.Phase, event: Gsm.Event)

  /** The fetch mode changed. */
  case FetchModeChanged(mode: FetchMode)

  /** A line a library logged through SLF4J. */
  case Library(logger: String, severity: Severity, message: String, thrown: Option[String])

object NodeEvent:
  /** What is running: the build of this jar (sbt-buildinfo). */
  final case class Build(
      version: String,
      scalaVersion: String,
      sbtVersion: String,
      gitCommit: String
  )

  object Build:
    val current: Build =
      Build(BuildInfo.version, BuildInfo.scalaVersion, BuildInfo.sbtVersion, BuildInfo.gitCommit)

  def started(
      peers: List[SocketAddress[Host]],
      genesis: ShelleyGenesis,
      config: NodeConfig = NodeConfig.empty,
      topology: Option[Topology] = None
  ): NodeEvent = Started(Build.current, peers, genesis, config, topology)

  def finished[S](chain: VolatileChain[S]): NodeEvent = Finished(chain.tip, chain.length)

  private def inbound(remote: String, state: String, reason: Option[String]): Line =
    EventKind.Inbound.line(
      "remote" -> remote.asJson,
      "state" -> state.asJson,
      "reason" -> reason.asJson
    )

  given Traced[NodeEvent] =
    case Started(build, peers, genesis, config, topology) =>
      // systemStart uses the Byron startTime when present, otherwise the Shelley systemStart.
      // byronEpochs stays null until a derived Byron/Shelley boundary is known.
      // genesisHashes lists configured hashes checked at startup; optional Dijkstra and
      // checkpoints inputs are compared only when present.
      EventKind.Started.line(
        "build" -> Json.obj(
          "version" -> build.version.asJson,
          "scalaVersion" -> build.scalaVersion.asJson,
          "sbtVersion" -> build.sbtVersion.asJson,
          "gitCommit" -> build.gitCommit.asJson
        ),
        "peer" -> peers.headOption.map(_.toString).asJson,
        "peers" -> peers.map(_.toString).asJson,
        "networkMagic" -> genesis.networkMagic.asJson,
        "systemStart" -> genesis.geometry.systemStart.toString.asJson,
        "epochLength" -> genesis.geometry.epochLength.asJson,
        "securityParam" -> genesis.geometry.securityParam.asJson,
        "byronEpochs" -> Option
          .unless(genesis.geometry.byron.derived)(genesis.geometry.byron.epochs)
          .asJson,
        "byronEpochSlots" -> genesis.geometry.byron.epochSlots.asJson,
        "hardForkEpochs" -> config.hardForkEpochs.map((e, n) => e.toString -> n).asJson,
        "genesisHashes" -> List(
          "Byron" -> config.hashes.byron,
          "Shelley" -> config.hashes.shelley,
          "Alonzo" -> config.hashes.alonzo,
          "Conway" -> config.hashes.conway,
          "Dijkstra" -> config.hashes.dijkstra,
          "Checkpoints" -> config.hashes.checkpoints
        ).collect { case (role, Some(hash)) => role -> hash.hex }.toMap.asJson,
        "configUnsupported" -> config.settings.unsupported.asJson,
        "topology" -> topology
          .map(t =>
            Json.obj(
              "localRoots" -> t.localRoots.map(_.accessPoints.map(_.toString)).asJson,
              "bootstrapPeers" -> t.bootstrapPeers.map(_.map(_.toString)).asJson,
              "publicRoots" -> t.publicRoots.map(_.accessPoints.map(_.toString)).asJson,
              "useLedgerAfterSlot" -> (t.useLedgerAfterSlot match
                case UseLedgerPeers.Disabled                   => "Disabled"
                case UseLedgerPeers.Use(AfterSlot.Always)      => "Always"
                case UseLedgerPeers.Use(AfterSlot.After(slot)) => s"After($slot)"
              ).asJson
            )
          )
          .asJson
      )
    case Blst(report) =>
      EventKind.Blst.line(
        "platform" -> report.platform.toString.asJson,
        "cpu" -> (report.cpu match
          case CpuSupport.Known(features) =>
            Json.obj("features" -> features.toList.map(_.flag).sorted.asJson)
          case CpuSupport.Unknown => Json.obj("features" -> Json.Null)),
        "library" -> (report.build match
          case LibraryBuild.Portable => Json.obj("build" -> "portable".asJson)
          case LibraryBuild.Other(sha256) =>
            Json.obj("build" -> "other".asJson, "sha256" -> sha256.asJson)
          case LibraryBuild.OnLibraryPath => Json.obj("build" -> "library-path".asJson))
      )
    case Listening(address) => EventKind.Listening.line("address" -> address.asJson)
    case Memory(sample)     => EventKind.Memory.line("sample" -> sample)
    case CpuStarvation(drift, threshold, interval) =>
      EventKind.CpuStarvation.line(
        "clockDriftMs" -> drift.toMillis.asJson,
        "threshold" -> threshold.asJson,
        "intervalMs" -> interval.toMillis.asJson
      )
    case Stalled(idle, ours, peers) =>
      EventKind.Stalled.line(
        "idleSeconds" -> idle.toSeconds.asJson,
        "ourBlock" -> ours.asJson,
        "peerBlock" -> peers.asJson
      )
    case StallExit(idle, ours, peers) =>
      EventKind.StallExit.line(
        "idleSeconds" -> idle.toSeconds.asJson,
        "ourBlock" -> ours.asJson,
        "peerBlock" -> peers.asJson
      )
    case StateDumped(slot, hash, epoch, millis) =>
      EventKind.StateDump.line(
        "slot" -> slot.asJson,
        "hash" -> hash.hex.asJson,
        "epoch" -> epoch.asJson,
        "millis" -> millis.asJson
      )
    case StateDumpRefused(slot, hash, epoch, error) =>
      EventKind.StateDumpRefused.line(
        "slot" -> slot.asJson,
        "hash" -> hash.hex.asJson,
        "epoch" -> epoch.asJson,
        "error" -> error.asJson
      )
    case VerificationCache(summary) =>
      EventKind.VerificationCache.line("summary" -> summary.asJson)
    case Failed(e) =>
      EventKind.Error.line("kind" -> e.productPrefix.asJson, "message" -> e.message.asJson)
    case Faulted(fault) =>
      EventKind.Fatal.line(
        "kind" -> (fault match
          case _: Fatal.Unavailable => "unavailable"
          case _: Fatal.Storage     => "storage"
          case _: Fatal.Internal    => "internal"
          case _: Fatal.Defect      => "defect"
        ).asJson,
        "message" -> fault.message.asJson,
        "exitCode" -> Main.FaultExitCode(fault).code.asJson
      )
    case Finished(tip, retained) =>
      EventKind.Finished.line("tip" -> Traced.point(tip), "retained" -> retained.asJson)
    case HeadersChecked(validated, unchecked, waited) =>
      EventKind.HeadersChecked.line(
        "validated" -> validated.asJson,
        "unchecked" -> unchecked.asJson,
        "waited" -> waited.asJson
      )
    case ChainSyncRounds(rounds, headers, avgMs, maxMs, maxDepth) =>
      EventKind.ChainSyncRounds.line(
        "rounds" -> rounds.asJson,
        "headers" -> headers.asJson,
        "avgMs" -> avgMs.asJson,
        "maxMs" -> maxMs.asJson,
        "maxDepth" -> maxDepth.asJson
      )
    case SlowFetch(millis) => EventKind.SlowFetch.line("millis" -> millis.asJson)
    case Inbound(remote, event) =>
      event match
        case InboundEvent.Accepted       => inbound(remote, "accepted", None)
        case InboundEvent.Duplex         => inbound(remote, "duplex", None)
        case InboundEvent.Unidirectional => inbound(remote, "unidirectional", None)
        case InboundEvent.Following(p)   => inbound(remote, "following", Some(Points.show(p)))
        case InboundEvent.SessionEnded(reason, dropped) =>
          inbound(
            remote,
            if dropped then "sessionDropped" else "sessionEnded",
            Some(reason.message)
          )
        case InboundEvent.SessionReturned(reason, delay) =>
          inbound(
            remote,
            "sessionReturned",
            Some(s"${reason.message}; repromote after ${delay.toSeconds}s")
          )
        case InboundEvent.CachedBlockRejected(refusal, generation, sourceLive) =>
          inbound(
            remote,
            "cachedBlockRejected",
            Some(s"${refusal.message}; source generation $generation; live=$sourceLive")
          ).at(Severity.Warning)
        case InboundEvent.Closed(reason) => inbound(remote, "closed", Some(reason.message))
        case InboundEvent.Refused(reason) =>
          inbound(remote, "refused", Some(reason.message)).at(Severity.Warning)
    case PeerSelection(trace)        => PeerSelectionLines.trace(trace)
    case PeerChurn(trace)            => PeerSelectionLines.churn(trace)
    case LedgerPeers(trace)          => PeerSelectionLines.ledger(trace)
    case PeerCounters(counters)      => PeerSelectionLines.counters(counters)
    case OutboundConnections(state)  => PeerSelectionLines.outbound(state)
    case GsmTransition(phase, event) => PeerSelectionLines.gsm(phase, event)
    case FetchModeChanged(mode)      => PeerSelectionLines.fetchMode(mode)
    case Library(logger, severity, message, thrown) =>
      EventKind.Slf4j
        .line(
          "logger" -> logger.asJson,
          "level" -> severity.toString.asJson,
          "message" -> message.asJson,
          "thrown" -> thrown.asJson
        )
        .at(severity)
