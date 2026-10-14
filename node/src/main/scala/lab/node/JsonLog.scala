// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import com.comcast.ip4s.{Host, SocketAddress}
import lab.network.ChainSync
import lab.network.runtime.ConnectionEvent
import lab.node.forge.{ForgeEvent, ForgeEvents}
import lab.node.mempool.{MempoolEvent, MempoolEvents}
import lab.node.storage.{StorageEvent, StorageEvents}

/** One JSON object per line on stdout, for the soak harness to consume. */
object JsonLog:
  private def line(kind: String, fields: (String, Json)*): IO[Unit] =
    IO.realTimeInstant.flatMap { now =>
      IO.println(
        Json.obj((("event" -> kind.asJson) +: ("at" -> now.toString.asJson) +: fields)*).noSpaces
      )
    }

  private def point(p: ChainSync.Point): Json = p match
    case ChainSync.Point.Origin => Json.obj("origin" -> true.asJson)
    case ChainSync.Point.Block(slot, hash) =>
      Json.obj("slot" -> slot.value.asJson, "hash" -> hash.hex.asJson)
  private def block(b: BlockRef): Json =
    Json.obj("slot" -> b.slot.asJson, "blockNo" -> b.blockNo.asJson, "hash" -> b.hash.hex.asJson)

  def started(
      peers: List[SocketAddress[Host]],
      genesis: ShelleyGenesis,
      config: NodeConfig = NodeConfig.empty
  ): IO[Unit] =
    line(
      "started",
      "peer" -> peers.headOption.map(_.toString).asJson,
      "peers" -> peers.map(_.toString).asJson,
      "networkMagic" -> genesis.networkMagic.asJson,
      "epochLength" -> genesis.geometry.epochLength.asJson,
      "securityParam" -> genesis.geometry.securityParam.asJson,
      // null while the Byron ledger has still to decide when the chain enters Shelley
      "byronEpochs" -> Option
        .unless(genesis.geometry.byron.derived)(genesis.geometry.byron.epochs)
        .asJson,
      "byronEpochSlots" -> genesis.geometry.byron.epochSlots.asJson,
      "hardForkEpochs" -> config.hardForkEpochs.map((e, n) => e.toString -> n).asJson
    )

  def finished[S](chain: VolatileChain[S]): IO[Unit] =
    line("finished", "tip" -> point(chain.tip), "retained" -> chain.length.asJson)

  def error(e: NodeError): IO[Unit] =
    line("error", "kind" -> e.getClass.getSimpleName.asJson, "message" -> e.message.asJson)

  /** The node accepts inbound connections on `address`. */
  def listening(address: String): IO[Unit] = line("listening", "address" -> address.asJson)

  /** Header-first validation so far: headers that passed every header rule, headers left to their
    * block's validation, and headers that waited for the chain to advance (G6).
    */
  def headersChecked(validated: Long, unchecked: Long, waited: Long): IO[Unit] =
    line(
      "headersChecked",
      "validated" -> validated.asJson,
      "unchecked" -> unchecked.asJson,
      "waited" -> waited.asJson
    )

  def inbound(remote: String, what: String, reason: Option[String] = None): IO[Unit] =
    line("inbound", "remote" -> remote.asJson, "state" -> what.asJson, "reason" -> reason.asJson)

  /** What the inbound governor did on a connection (G2). */
  def inboundEvent(remote: String, event: InboundEvent): IO[Unit] = event match
    case InboundEvent.Accepted       => inbound(remote, "accepted")
    case InboundEvent.Duplex         => inbound(remote, "duplex")
    case InboundEvent.Unidirectional => inbound(remote, "unidirectional")
    case InboundEvent.Following(p)   => inbound(remote, "following", Some(Points.show(p)))
    case InboundEvent.SessionEnded(reason, dropped) =>
      inbound(remote, if dropped then "sessionDropped" else "sessionEnded", Some(reason))
    case InboundEvent.Closed(reason)  => inbound(remote, "closed", Some(reason))
    case InboundEvent.Refused(reason) => inbound(remote, "refused", Some(reason))

  /** What the connection manager did (G2): a connection opened, moved to another state, was refused
    * or closed.
    */
  def connection(event: ConnectionEvent): IO[Unit] = event match
    case ConnectionEvent.Opened(remote, provenance) =>
      line(
        "connection",
        "remote" -> remote.asJson,
        "what" -> "opened".asJson,
        "provenance" -> provenance.toString.asJson
      )
    case ConnectionEvent.Changed(remote, from, to) =>
      line(
        "connection",
        "remote" -> remote.asJson,
        "what" -> "changed".asJson,
        "from" -> from.toString.asJson,
        "to" -> to.toString.asJson
      )
    case ConnectionEvent.Refused(error) =>
      line("connection", "what" -> "refused".asJson, "reason" -> error.message.asJson)
    case ConnectionEvent.Closed(remote, provenance, reason) =>
      line(
        "connection",
        "remote" -> remote.asJson,
        "what" -> "closed".asJson,
        "provenance" -> provenance.toString.asJson,
        "reason" -> reason.asJson
      )

  val mempool: MempoolEvents[IO] = new MempoolEvents[IO]:
    def emit(event: MempoolEvent): IO[Unit] = event match
      case MempoolEvent.Added(id, size, count, bytes) =>
        line(
          "mempoolAdded",
          "tx" -> id.hex.asJson,
          "size" -> size.asJson,
          "count" -> count.asJson,
          "bytes" -> bytes.asJson
        )
      case MempoolEvent.Rejected(id, reason) =>
        line("mempoolRejected", "tx" -> id.map(_.hex).asJson, "reason" -> reason.message.asJson)
      case MempoolEvent.Adopted(id) => line("mempoolAdopted", "tx" -> id.hex.asJson)
      case MempoolEvent.Dropped(id, reason) =>
        line("mempoolDropped", "tx" -> id.hex.asJson, "reason" -> reason.message.asJson)
      case MempoolEvent.Synced(count, bytes, dropped) =>
        if dropped > 0 then
          line(
            "mempoolSynced",
            "count" -> count.asJson,
            "bytes" -> bytes.asJson,
            "dropped" -> dropped.asJson
          )
        else IO.unit

  val forge: ForgeEvents[IO] = new ForgeEvents[IO]:
    def emit(event: ForgeEvent): IO[Unit] = event match
      case ForgeEvent.Started(pool, vrfHash, start, evolutions) =>
        line(
          "forgeStarted",
          "pool" -> pool.hex.asJson,
          "vrfKeyHash" -> vrfHash.hex.asJson,
          "kesStart" -> start.asJson,
          "kesEvolutions" -> evolutions.asJson
        )
      case ForgeEvent.Forged(slot, epoch, no, hash, prev, txs, size, kes, rel, stake, total) =>
        line(
          "forged",
          "slot" -> slot.asJson,
          "epoch" -> epoch.asJson,
          "blockNo" -> no.asJson,
          "hash" -> hash.hex.asJson,
          "prev" -> prev.map(_.hex).asJson,
          "txs" -> txs.map(_.hex).asJson,
          "bodySize" -> size.asJson,
          "kesPeriod" -> kes.asJson,
          "relativeKesPeriod" -> rel.asJson,
          "stake" -> stake.asJson,
          "totalStake" -> total.asJson
        )
      case ForgeEvent.Adopted(b) => line("adopted", "block" -> block(b))
      case ForgeEvent.Lost(slot, hash, reason) =>
        line("lost", "slot" -> slot.asJson, "hash" -> hash.hex.asJson, "reason" -> reason.asJson)
      case ForgeEvent.KesEvolved(period, rel) =>
        line("kesEvolved", "kesPeriod" -> period.asJson, "relativeKesPeriod" -> rel.asJson)
      case ForgeEvent.Skipped(slot, reason) =>
        line("forgeSkipped", "slot" -> slot.asJson, "reason" -> reason.asJson)
      case ForgeEvent.Failed(slot, error) =>
        line(
          "forgeFailed",
          "slot" -> slot.asJson,
          "kind" -> error.getClass.getSimpleName.asJson,
          "message" -> error.message.asJson
        )

  /** The scheduling measurements of a window (one epoch of the log), see [[SchedulingReport]]. */
  def scheduling(r: SchedulingReport): Json =
    val longest = r.longest
    Json.obj(
      "timerSamples" -> r.samples.asJson,
      "maxTimerLatenessMs" -> r.maxTimerLatenessMs.asJson,
      "timersLateOver50Ms" -> r.timersLateOver50.asJson,
      "timersLateOver100Ms" -> r.timersLateOver100.asJson,
      "cpuSteps" -> r.stepCount.asJson,
      "longestStepMs" -> longest.fold(0L)(_._2.maxMs).asJson,
      "longestStep" -> longest.map(_._1).asJson
    )

  /** The measurements of the whole run, with every step label, for the query server. */
  def schedulingTotal(r: SchedulingReport): Json =
    scheduling(r).deepMerge(
      Json.obj(
        "steps" -> Json.obj(r.steps.toList.sortBy(_._1).map { (label, step) =>
          label -> Json.obj(
            "count" -> step.count.asJson,
            "maxMs" -> step.maxMs.asJson,
            "totalMs" -> step.totalMs.asJson
          )
        }*)
      )
    )

  /** The follower's events as log lines. With `sched`, the lines that close an epoch carry the
    * scheduling measurements of that epoch (`maxTimerLatenessMs`, `longestStepMs`, ...).
    */
  def events(sched: Option[SchedulingStats[IO]] = None): FollowerEvents[IO] =
    new FollowerEvents[IO]:
      private def epochLine(kind: String, fields: (String, Json)*): IO[Unit] =
        sched
          .traverse(_.window.map(r => "scheduling" -> scheduling(r)))
          .flatMap(extra => line(kind, (fields ++ extra.toList)*))
      def emit(event: FollowerEvent): IO[Unit] = event match
        case FollowerEvent.Connected(p) => line("connected", "intersection" -> point(p))
        case FollowerEvent.Extended(b, epoch, tip, era) =>
          line(
            "extended",
            "block" -> block(b),
            "epoch" -> epoch.asJson,
            "era" -> era.toString.asJson,
            "peerTip" -> point(tip.point)
          )
        case FollowerEvent.RolledBack(from, to, depth) =>
          line("rolledBack", "from" -> point(from), "to" -> point(to), "depth" -> depth.asJson)
        case FollowerEvent.EpochEntered(epoch, first) =>
          line("epoch", "epoch" -> epoch.asJson, "first" -> block(first))
        case FollowerEvent.Immutable(_) => IO.unit
        case FollowerEvent.EraEntered(from, to, first, epoch) =>
          line(
            "eraTransition",
            "from" -> from.map(_.toString).asJson,
            "to" -> to.toString.asJson,
            "epoch" -> epoch.asJson,
            "slot" -> first.slot.asJson,
            "block" -> block(first)
          )
        case FollowerEvent.ByronEpochValidated(epoch, boundary, verified, offSchedule, txs) =>
          epochLine(
            "epochValidated",
            "era" -> "Byron".asJson,
            "epoch" -> epoch.asJson,
            "headers" -> (boundary + verified).asJson,
            "boundary" -> boundary.asJson,
            "signatureVerified" -> verified.asJson,
            "offSchedule" -> offSchedule.asJson,
            "transactions" -> txs.asJson
          )
        case FollowerEvent.EpochValidated(epoch, era, threshold, overlay, unchecked) =>
          epochLine(
            "epochValidated",
            "era" -> era.toString.asJson,
            "epoch" -> epoch.asJson,
            "headers" -> (threshold + overlay + unchecked).asJson,
            "thresholdChecked" -> threshold.asJson,
            "overlay" -> overlay.asJson,
            "leaderUnchecked" -> unchecked.asJson
          )
        case FollowerEvent.LedgerEpoch(epoch, utxo, accounts, pools, treasury, reserves, pv, u) =>
          epochLine(
            "ledgerEpoch",
            "epoch" -> epoch.asJson,
            "utxo" -> utxo.asJson,
            "accounts" -> accounts.asJson,
            "pools" -> pools.asJson,
            "treasury" -> treasury.asJson,
            "reserves" -> reserves.asJson,
            "protocolVersion" -> s"${pv._1}.${pv._2}".asJson,
            "phase2" -> Json.obj(
              "evaluatedSuccess" -> u.succeeded.asJson,
              "evaluatedFailure" -> u.failed.asJson,
              "scripts" -> u.scripts.asJson
            ),
            "scriptIntegrityHashChecked" -> u.integrityChecked.asJson
          )
        case FollowerEvent.Reconnecting(reason, attempt) =>
          line("reconnecting", "reason" -> reason.message.asJson, "attempt" -> attempt.asJson)

  val storage: StorageEvents[IO] = new StorageEvents[IO]:
    private def anchor(b: Option[BlockRef]): Json = b.fold(Json.Null)(block)
    def emit(event: StorageEvent): IO[Unit] = event match
      case StorageEvent.ImmutableOpened(r) =>
        line(
          "immutableOpened",
          "tip" -> anchor(r.tip),
          "blocks" -> r.blocks.asJson,
          "chunks" -> r.chunks.asJson,
          "truncatedEntries" -> r.truncatedEntries.asJson,
          "truncatedBytes" -> r.truncatedBytes.asJson
        )
      case StorageEvent.SnapshotSkipped(name, error) =>
        line("snapshotSkipped", "file" -> name.asJson, "reason" -> error.message.asJson)
      case StorageEvent.SnapshotRestored(name, a) =>
        line("snapshotRestored", "file" -> name.asJson, "anchor" -> anchor(a))
      case StorageEvent.SnapshotWritten(name, a, bytes, millis) =>
        line(
          "snapshotWritten",
          "file" -> name.asJson,
          "anchor" -> anchor(a),
          "bytes" -> bytes.asJson,
          "millis" -> millis.asJson
        )
      case StorageEvent.SnapshotFailed(reason) => line("snapshotFailed", "reason" -> reason.asJson)
      case StorageEvent.ReplayStarted(from, tip) =>
        line("replayStarted", "from" -> anchor(from), "immutableTip" -> anchor(tip))
      case StorageEvent.ReplayProgress(at, replayed, millis) =>
        line(
          "replayProgress",
          "at" -> block(at),
          "replayed" -> replayed.asJson,
          "millis" -> millis.asJson
        )
      case StorageEvent.ReplayFinished(at, replayed, millis) =>
        line(
          "replayFinished",
          "at" -> anchor(at),
          "replayed" -> replayed.asJson,
          "millis" -> millis.asJson
        )
      case StorageEvent.NetworkRecorded(id, adopted) =>
        line(
          "networkRecorded",
          "magic" -> id.magic.asJson,
          "shelleyGenesis" -> id.shelleyGenesis.hex.asJson,
          "byronGenesis" -> id.byronGenesis.map(_.hex).asJson,
          "adoptedExisting" -> adopted.asJson
        )
      case StorageEvent.VolatileOpened(r) =>
        line(
          "volatileOpened",
          "blocks" -> r.blocks.asJson,
          "files" -> r.files.asJson,
          "truncatedBytes" -> r.truncatedBytes.asJson,
          "removedFiles" -> r.removedFiles.asJson
        )
      case StorageEvent.VolatileResumed(resumed, dropped, millis) =>
        line(
          "volatileResumed",
          "resumed" -> resumed.asJson,
          "dropped" -> dropped.asJson,
          "millis" -> millis.asJson
        )
      case StorageEvent.ShutdownSnapshot(a, written) =>
        line("shutdownSnapshot", "anchor" -> anchor(a), "written" -> written.asJson)
