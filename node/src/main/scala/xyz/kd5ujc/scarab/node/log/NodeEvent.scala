// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.*
import com.comcast.ip4s.{Host, SocketAddress}
import xyz.kd5ujc.scarab.blst.{BlstReport, CpuSupport, LibraryBuild}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.{NodeError, Points, ShelleyGenesis}
import xyz.kd5ujc.scarab.node.{BuildInfo, InboundEvent, MirrorCheck, NodeConfig, Topology}
import xyz.kd5ujc.scarab.node.differential.{Divergence, Stats, Timing, Verdict}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** What the node itself reports, apart from the event types of its parts (the follower, the chain
  * database, the mempool, the forger, the connections, the differential rules), which are logged as
  * they are. Closed: every line the node writes is one of these or one of those.
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

  /** One result of `--mirror-check`: the state turned into the Haskell state mirror and back. */
  case MirrorChecked(
      slot: BigInt,
      hash: Bytes,
      epoch: BigInt,
      outcome: MirrorCheck.Outcome,
      millis: Long
  )

  /** The run stops at a disagreement of the old and the new rules (`--on-divergence halt`). */
  case RulesHalted(divergence: Divergence)

  /** The harness' account of the run: blocks, samples, what the conversion cost, what is not
    * ported.
    */
  case DifferentialSummary(stats: Stats)

  /** What the inbound governor (G2) or the node-to-client listener did on a connection. */
  case Inbound(remote: String, event: InboundEvent)

  /** An outbound peer is served by the inbound duplex connection it opened to us (G2). */
  case Reused(remote: String, outbound: String)

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

  private def verdict(v: Verdict): Json =
    v.fold(f => s"${f.rule.name}: ${f.reason}", _ => "accepted").asJson

  /** The `rulesDiverged` and `rulesHalted` lines: what the disagreement was, and for the former
    * where its repro was kept.
    */
  private[log] def diverged(
      kind: EventKind,
      d: Divergence,
      bundle: Option[Option[java.nio.file.Path]]
  ): Line =
    val fields = d match
      case Divergence.StateDiffers(b, era, stage, difference) =>
        List(
          "kind" -> "StateDiffers".asJson,
          "block" -> Traced.block(b),
          "era" -> era.toString.asJson,
          "stage" -> stage.toString.asJson,
          "path" -> difference.path.render.asJson,
          "old" -> difference.left.asJson,
          "new" -> difference.right.asJson
        )
      case Divergence.VerdictDiffers(b, stage, old, neu) =>
        List(
          "kind" -> "VerdictDiffers".asJson,
          "block" -> Traced.block(b),
          "stage" -> stage.toString.asJson,
          "old" -> verdict(old),
          "new" -> verdict(neu)
        )
      case Divergence.Unconvertible(b, stage, error) =>
        List(
          "kind" -> "Unconvertible".asJson,
          "block" -> Traced.block(b),
          "stage" -> stage.toString.asJson,
          "error" -> error.show.asJson
        )
      case Divergence.NotPorted(era, stage) =>
        List(
          "kind" -> "NotPorted".asJson,
          "era" -> era.toString.asJson,
          "stage" -> stage.toString.asJson
        )
    kind.line(
      (fields ++ bundle.map(b => "bundle" -> b.map(_.toString).asJson))*
    )

  private def inbound(remote: String, state: String, reason: Option[String]): Line =
    EventKind.Inbound.line(
      "remote" -> remote.asJson,
      "state" -> state.asJson,
      "reason" -> reason.asJson
    )

  private def millis(xs: Vector[Long]): Json =
    val sorted = xs.sorted
    Json.obj(
      "total" -> (xs.sum / 1e6).asJson,
      "median" -> (Stats.quantile(sorted, 0.5) / 1e6).asJson,
      "p99" -> (Stats.quantile(sorted, 0.99) / 1e6).asJson,
      "max" -> (sorted.lastOption.getOrElse(0L) / 1e6).asJson
    )

  given Traced[NodeEvent] =
    case Started(build, peers, genesis, config, topology) =>
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
        // where slot 0 starts: the Byron genesis' startTime when there is one (the reference's
        // clock), else the Shelley genesis' systemStart
        "systemStart" -> genesis.geometry.systemStart.toString.asJson,
        "epochLength" -> genesis.geometry.epochLength.asJson,
        "securityParam" -> genesis.geometry.securityParam.asJson,
        // null while the Byron ledger has still to decide when the chain enters Shelley
        "byronEpochs" -> Option
          .unless(genesis.geometry.byron.derived)(genesis.geometry.byron.epochs)
          .asJson,
        "byronEpochSlots" -> genesis.geometry.byron.epochSlots.asJson,
        "hardForkEpochs" -> config.hardForkEpochs.map((e, n) => e.toString -> n).asJson,
        // the genesis hashes the configuration lists; start-up refused a mismatch (the Dijkstra and
        // checkpoints files are compared only when they are there)
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
              "useLedgerAfterSlot" -> t.useLedgerAfterSlot.toString.asJson
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
    case MirrorChecked(slot, hash, epoch, outcome, millis) =>
      val where = List(
        "slot" -> slot.asJson,
        "hash" -> hash.hex.asJson,
        "epoch" -> epoch.asJson,
        "era" -> outcome.era.toString.asJson,
        "millis" -> millis.asJson
      )
      outcome match
        case MirrorCheck.Outcome.Checked(_, gaps, residueEmpty, retracts) =>
          EventKind.MirrorChecked
            .line(
              where ++ List(
                "gaps" -> gaps.toList.map(_.toString).sorted.asJson,
                "residueEmpty" -> residueEmpty.asJson,
                "retracts" -> retracts.asJson,
                "ok" -> outcome.ok.asJson
              )*
            )
            .pipe(line => if outcome.ok then line else line.at(Severity.Warning))
        case MirrorCheck.Outcome.Refused(_, stage, error) =>
          EventKind.MirrorCheckFailed.line(
            where ++ List("stage" -> stage.toString.asJson, "error" -> error.show.asJson)*
          )
        case MirrorCheck.Outcome.Crashed(_, message) =>
          EventKind.MirrorCheckFailed.line(
            where ++ List("stage" -> "Crashed".asJson, "error" -> message.asJson)*
          )
    case RulesHalted(d)             => diverged(EventKind.RulesHalted, d, None)
    case DifferentialSummary(stats) =>
      // the blocks both sides applied (the old side's whole block; the new side's ledger part, with
      // the signatures and the block's decoding it does for itself), p50 and p99 to the power of two
      def timing(t: Timing): Json = Json.obj(
        "count" -> t.count.asJson,
        "meanMillis" -> (t.meanNanos / 1e6).asJson,
        "p50Millis" -> (t.quantileNanos(0.5) / 1e6).asJson,
        "p99Millis" -> (t.quantileNanos(0.99) / 1e6).asJson,
        "maxMillis" -> (t.maxNanos / 1e6).asJson
      )
      EventKind.DifferentialSummary.line(
        "blocks" -> stats.blocks.asJson,
        "blockOld" -> timing(stats.oldBlocks),
        "blockNew" -> timing(stats.newBlocks),
        "sampled" -> stats.sampled.asJson,
        "failures" -> stats.failures.asJson,
        "toMirrorMillis" -> millis(stats.samples.map(_.toMirrorNanos)),
        "diffMillis" -> millis(stats.samples.map(_.diffNanos)),
        "ticksCompared" -> stats.ticks.size.asJson,
        "tickOldMillis" -> millis(stats.ticks.map(_.oldNanos)),
        "tickNewMillis" -> millis(stats.ticks.map(_.newNanos)),
        "notPorted" -> stats.notPorted.toList
          .sortBy((k, _) => (k._1.ordinal, k._2.toString))
          .map((k, n) => s"${k._1}/${k._2}" -> n.asJson)
          .pipe(Json.obj(_*))
      )
    case Inbound(remote, event) =>
      event match
        case InboundEvent.Accepted       => inbound(remote, "accepted", None)
        case InboundEvent.Duplex         => inbound(remote, "duplex", None)
        case InboundEvent.Unidirectional => inbound(remote, "unidirectional", None)
        case InboundEvent.Following(p)   => inbound(remote, "following", Some(Points.show(p)))
        case InboundEvent.SessionEnded(reason, dropped) =>
          inbound(remote, if dropped then "sessionDropped" else "sessionEnded", Some(reason))
        case InboundEvent.Closed(reason) => inbound(remote, "closed", Some(reason))
        case InboundEvent.Refused(reason) =>
          inbound(remote, "refused", Some(reason)).at(Severity.Warning)
    case Reused(remote, outbound) =>
      inbound(remote, "reused", Some(s"outbound $outbound served by the inbound duplex connection"))
    case Library(logger, severity, message, thrown) =>
      EventKind.Slf4j
        .line(
          "logger" -> logger.asJson,
          "level" -> severity.toString.asJson,
          "message" -> message.asJson,
          "thrown" -> thrown.asJson
        )
        .at(severity)
