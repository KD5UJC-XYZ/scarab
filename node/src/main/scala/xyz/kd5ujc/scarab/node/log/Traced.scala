// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent, SchedulingReport}
import xyz.kd5ujc.scarab.network.{ConnectionEvent, DiffTime}
import xyz.kd5ujc.scarab.ledger.{ForgeDefect, ForgeKeyProblem}
import xyz.kd5ujc.scarab.node.{LeiosPrevalidation, Reports}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.forge.{
  NoExchange,
  CannotForge,
  DiscardReason,
  ForgeEvent,
  ClockTrace,
  ForgeTrace,
  KesEvolutionError,
  KesInfo,
  LeiosForgeTrace
}
import xyz.kd5ujc.scarab.node.mempool.{MempoolEvent, Rejection}
import xyz.kd5ujc.scarab.node.LeiosKernelEvent
import xyz.kd5ujc.scarab.consensus.leios.{AddVoteResult, LeiosNotVotedReason}
import xyz.kd5ujc.scarab.crypto.bls.Weight
import xyz.kd5ujc.scarab.ledger.era.rules.LeiosClosureDefect
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.{StorageEvent, VolatileStore}

/** How an event of type `E` is logged: its [[Line]], that is its kind (and so its namespace), its
  * severity and its JSON fields. The node's `LogFormatting` and `MetaTrace` in one (hermod-tracing
  * `trace-dispatcher/src/Cardano/Logging/Types.hs` 149-179): an instance matches on the event, so
  * the compiler checks that every case of the event type has a line, and a [[Line]] cannot be built
  * without a kind, which carries the namespace and the usual severity.
  *
  * The instances for the node's own event types are in their companions ([[NodeEvent]]); the ones
  * for the event types of the other modules (which do not know about the log) are here.
  */
trait Traced[E]:
  def line(event: E): Line

object Traced:
  def apply[E](using traced: Traced[E]): Traced[E] = traced

  /** The JSON forms shared by several events. */
  private[log] def point(p: ChainSync.Point): Json = p match
    case ChainSync.Point.Origin => Json.obj("origin" -> true.asJson)
    case ChainSync.Point.Block(slot, hash) =>
      Json.obj("slot" -> slot.value.asJson, "hash" -> hash.hex.asJson)

  /** A point as the reference renders it at the default detail level (`DNormal`): the full hash,
    * `@`, the slot, or `genesis (origin)` (cardano-node 11.1.3 `Cardano/Node/Tracing/Render.hs`
    * 123-135 `renderPointForDetails`, 180-184 `trimHashTextForDetails`).
    */
  private[log] def pointForDetails(p: ChainSync.Point): String = p match
    case ChainSync.Point.Origin            => "genesis (origin)"
    case ChainSync.Point.Block(slot, hash) => s"${hash.hex}@${slot.value}"

  private[log] def block(b: BlockRef): Json =
    Json.obj("slot" -> b.slot.asJson, "blockNo" -> b.blockNo.asJson, "hash" -> b.hash.hex.asJson)
  private[log] def anchor(b: Option[BlockRef]): Json = b.fold(Json.Null)(block)

  /** An event with the scheduling measurements of the window it closes (the epoch lines). */
  final case class Scheduled[E](event: E, report: Option[SchedulingReport])

  given withScheduling[E](using traced: Traced[E]): Traced[Scheduled[E]] = scheduled =>
    val line = traced.line(scheduled.event)
    line.copy(fields =
      line.fields ++ scheduled.report.map(r => "scheduling" -> Reports.scheduling(r))
    )

  /** An event that happened at `at`, logged later: its line keeps that time as its `at`. */
  final case class Timed[E](event: E, at: java.time.Instant)

  given withTime[E](using traced: Traced[E]): Traced[Timed[E]] = timed =>
    traced.line(timed.event).copy(time = Some(timed.at))

  /** The follower's events that make a line: [[FollowerEvent.Immutable]] is bookkeeping for the
    * metrics (a block moved past `k`), one per block, and has none.
    */
  type FollowerLine = FollowerEvent.Connected | FollowerEvent.Extended | FollowerEvent.RolledBack |
    FollowerEvent.EpochEntered | FollowerEvent.Reconnecting | FollowerEvent.EraEntered |
    FollowerEvent.EpochValidated | FollowerEvent.ByronEpochValidated | FollowerEvent.LedgerEpoch |
    FollowerEvent.NoVerdict | FollowerEvent.NoVerdictRetried

  given Traced[FollowerLine] =
    case FollowerEvent.Connected(p) => EventKind.Connected.line("intersection" -> point(p))
    case FollowerEvent.Extended(b, epoch, tip, era) =>
      EventKind.Extended.line(
        "block" -> block(b),
        "epoch" -> epoch.asJson,
        "era" -> era.toString.asJson,
        "peerTip" -> tip.fold(Json.Null)(t => point(t.point))
      )
    case FollowerEvent.RolledBack(from, to, depth) =>
      EventKind.RolledBack.line("from" -> point(from), "to" -> point(to), "depth" -> depth.asJson)
    case FollowerEvent.EpochEntered(epoch, first) =>
      EventKind.Epoch.line("epoch" -> epoch.asJson, "first" -> block(first))
    case FollowerEvent.EraEntered(from, to, first, epoch) =>
      EventKind.EraTransition.line(
        "from" -> from.map(_.toString).asJson,
        "to" -> to.toString.asJson,
        "epoch" -> epoch.asJson,
        "slot" -> first.slot.asJson,
        "block" -> block(first)
      )
    case FollowerEvent.ByronEpochValidated(epoch, boundary, verified, offSchedule, txs) =>
      EventKind.EpochValidated.line(
        "era" -> "Byron".asJson,
        "epoch" -> epoch.asJson,
        "headers" -> (boundary + verified).asJson,
        "boundary" -> boundary.asJson,
        "signatureVerified" -> verified.asJson,
        "offSchedule" -> offSchedule.asJson,
        "transactions" -> txs.asJson
      )
    case FollowerEvent.EpochValidated(epoch, era, threshold, overlay, unchecked) =>
      EventKind.EpochValidated.line(
        "era" -> era.toString.asJson,
        "epoch" -> epoch.asJson,
        "headers" -> (threshold + overlay + unchecked).asJson,
        "thresholdChecked" -> threshold.asJson,
        "overlay" -> overlay.asJson,
        "leaderUnchecked" -> unchecked.asJson
      )
    case FollowerEvent.LedgerEpoch(epoch, utxo, accounts, pools, treasury, reserves, pv, u) =>
      EventKind.LedgerEpoch.line(
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
      EventKind.Reconnecting.line("reason" -> reason.message.asJson, "attempt" -> attempt.asJson)
    case FollowerEvent.NoVerdict(at, cause, attempt, retry) =>
      EventKind.BlockNoVerdict.line(
        "block" -> point(at),
        "kind" -> noVerdictKind(cause).asJson,
        "reason" -> cause.message.asJson,
        "attempt" -> attempt.asJson,
        "retryInMs" -> (retry match
          case xyz.kd5ujc.scarab.consensus.NoVerdictRetry.Never        => Json.Null
          case xyz.kd5ujc.scarab.consensus.NoVerdictRetry.After(delay) => delay.toMillis.asJson)
      )
    case FollowerEvent.NoVerdictRetried(at, attempt) =>
      EventKind.BlockNoVerdictRetry.line("block" -> point(at), "attempt" -> attempt.asJson)

  /** The `kind` field of a cause of no verdict. */
  private[log] def noVerdictKind(cause: xyz.kd5ujc.scarab.consensus.BlockNoVerdict): String =
    cause match
      case xyz.kd5ujc.scarab.consensus.BlockNoVerdict.Evaluation(_, _)     => "evaluation"
      case xyz.kd5ujc.scarab.consensus.BlockNoVerdict.ScriptUnavailable(_) => "evaluation"
      case xyz.kd5ujc.scarab.consensus.BlockNoVerdict.Projection(_)        => "defect"
      case xyz.kd5ujc.scarab.consensus.BlockNoVerdict.Verification(_)      => "verification"
      case xyz.kd5ujc.scarab.consensus.BlockNoVerdict.StateOutOfRange(_)   => "stateOutOfRange"
      case xyz.kd5ujc.scarab.consensus.BlockNoVerdict.ConwayRules(_)       => "defect"
      case xyz.kd5ujc.scarab.consensus.BlockNoVerdict.Defect(_)            => "defect"

  given Traced[StorageEvent] =
    case StorageEvent.FormatMigrated(m) =>
      EventKind.FormatMigrated.line(
        "kind" -> m.kind.toString.asJson,
        "from" -> m.from.toString.asJson,
        "to" -> m.to.toString.asJson,
        "count" -> m.count.asJson
      )
    case StorageEvent.ImmutableOpened(r) =>
      EventKind.ImmutableOpened.line(
        "tip" -> anchor(r.tip),
        "blocks" -> r.blocks.asJson,
        "chunks" -> r.chunks.asJson,
        "truncatedEntries" -> r.truncatedEntries.asJson,
        "truncatedBytes" -> r.truncatedBytes.asJson
      )
    case StorageEvent.SnapshotSkipped(name, error) =>
      EventKind.SnapshotSkipped.line("file" -> name.asJson, "reason" -> error.message.asJson)
    case StorageEvent.SnapshotRestored(name, a) =>
      EventKind.SnapshotRestored.line("file" -> name.asJson, "anchor" -> anchor(a))
    case StorageEvent.SnapshotDeleted(name) =>
      EventKind.SnapshotDeleted.line("file" -> name.asJson)
    case StorageEvent.SnapshotNotDeleted(name, error) =>
      EventKind.SnapshotNotDeleted.line("file" -> name.asJson, "reason" -> error.message.asJson)
    case StorageEvent.CheckpointNotDeleted(name, error) =>
      EventKind.CheckpointNotDeleted.line(
        "checkpoint" -> name.asJson,
        "reason" -> error.message.asJson
      )
    case StorageEvent.SnapshotUnbound(name, a) =>
      EventKind.SnapshotUnbound.line("file" -> name.asJson, "anchor" -> anchor(a))
    case StorageEvent.SnapshotWritten(name, a, bytes, millis) =>
      EventKind.SnapshotWritten.line(
        "file" -> name.asJson,
        "anchor" -> anchor(a),
        "bytes" -> bytes.asJson,
        "millis" -> millis.asJson
      )
    case StorageEvent.SnapshotFailed(failure) =>
      EventKind.SnapshotFailed.line("reason" -> failure.message.asJson)
    case StorageEvent.SnapshotRefused(name, a, refusal) =>
      EventKind.SnapshotRefused.line(
        "file" -> name.asJson,
        "anchor" -> anchor(a),
        "reason" -> refusal.show.asJson
      )
    case StorageEvent.ReplayStarted(from, tip) =>
      EventKind.ReplayStarted.line("from" -> anchor(from), "immutableTip" -> anchor(tip))
    case StorageEvent.ReplayProgress(at, replayed, millis) =>
      EventKind.ReplayProgress.line(
        "tip" -> block(at),
        "replayed" -> replayed.asJson,
        "millis" -> millis.asJson
      )
    case StorageEvent.ReplayFinished(at, replayed, millis) =>
      EventKind.ReplayFinished.line(
        "tip" -> anchor(at),
        "replayed" -> replayed.asJson,
        "millis" -> millis.asJson
      )
    case StorageEvent.NetworkRecorded(id, adopted, dijkstraAdopted) =>
      EventKind.NetworkRecorded.line(
        "magic" -> id.magic.asJson,
        "shelleyGenesis" -> id.shelleyGenesis.hex.asJson,
        "byronGenesis" -> id.byronGenesis.map(_.hex).asJson,
        "alonzoGenesis" -> xyz.kd5ujc.scarab.ledger.NetworkIdentity
          .renderGenesisHash(id.alonzoGenesis)
          .asJson,
        "conwayGenesis" -> xyz.kd5ujc.scarab.ledger.NetworkIdentity
          .renderGenesisHash(id.conwayGenesis)
          .asJson,
        "dijkstraGenesis" -> id.dijkstraGenesis
          .map(xyz.kd5ujc.scarab.ledger.NetworkIdentity.renderGenesisHash)
          .asJson,
        "adoptedExisting" -> adopted.asJson,
        "adoptedDijkstraGenesis" -> dijkstraAdopted.asJson
      )
    case StorageEvent.VolatileOpened(r) =>
      EventKind.VolatileOpened.line(
        "blocks" -> r.blocks.asJson,
        "files" -> r.files.asJson,
        "truncatedBytes" -> r.truncatedBytes.asJson,
        "removedFiles" -> r.removedFiles.asJson
      )
    case StorageEvent.ReceivedOpened(r) =>
      EventKind.ReceivedOpened.line(
        "blocks" -> r.blocks.asJson,
        "files" -> r.files.asJson,
        "truncatedBytes" -> r.truncatedBytes.asJson
      )
    case StorageEvent.ReceivedTruncated(cut) =>
      val where = List(
        "file" -> cut.file.asJson,
        "offset" -> cut.offset.asJson
      )
      val fields = cut.cause match
        case VolatileStore.CutCause.ReadFailed(VolatileStore.ReadFailure.Unreadable) =>
          where ++ List("cause" -> "readFailed".asJson, "failure" -> "unreadable".asJson)
        case VolatileStore.CutCause.ReadFailed(VolatileStore.ReadFailure.Undecodable(error)) =>
          where ++ List(
            "cause" -> "readFailed".asJson,
            "failure" -> "undecodable".asJson,
            "reason" -> error.message.asJson
          )
        case VolatileStore.CutCause.Corrupted(hash) =>
          where ++ List("cause" -> "corrupted".asJson, "hash" -> hash.hex.asJson)
        case VolatileStore.CutCause.Duplicated(hash, first, second) =>
          where ++ List(
            "cause" -> "duplicated".asJson,
            "hash" -> hash.hex.asJson,
            "first" -> first.asJson,
            "second" -> second.asJson
          )
      EventKind.ReceivedTruncated.line(fields*)
    case StorageEvent.ReceivedInvalidFileNames(names) =>
      EventKind.ReceivedInvalidFileNames.line("files" -> names.asJson)
    case StorageEvent.ReceivedBlockAlreadyHere(hash) =>
      EventKind.ReceivedBlockAlreadyHere.line("hash" -> hash.hex.asJson)
    case StorageEvent.ScheduledGC(slot, at) =>
      EventKind.ScheduledGC.line("slot" -> slot.asJson, "dueMillis" -> at.toMillis.asJson)
    case StorageEvent.PerformedGC(slot) =>
      EventKind.PerformedGC.line("slot" -> slot.asJson)
    case StorageEvent.VolatileResumed(resumed, dropped, millis) =>
      EventKind.VolatileResumed.line(
        "resumed" -> resumed.asJson,
        "dropped" -> dropped.asJson,
        "millis" -> millis.asJson
      )
    case StorageEvent.ShutdownSnapshot(a, written) =>
      EventKind.ShutdownSnapshot.line("anchor" -> anchor(a), "written" -> written.asJson)

  /** A `DiffTime` as an exact number of seconds. */
  private def seconds(delay: DiffTime): BigDecimal =
    BigDecimal(
      new java.math.BigDecimal(delay.toPicoseconds.bigInteger, 12).stripTrailingZeros.toPlainString
    )

  /** What the connection manager and the accept loop did (G2); a refusal and the hard limit are
    * warnings.
    */
  given Traced[ConnectionEvent] =
    case ConnectionEvent.Opened(remote, provenance) =>
      EventKind.Connection.line(
        "remote" -> remote.asJson,
        "what" -> "opened".asJson,
        "provenance" -> provenance.toString.asJson
      )
    case ConnectionEvent.Changed(remote, from, to) =>
      EventKind.Connection.line(
        "remote" -> remote.asJson,
        "what" -> "changed".asJson,
        "from" -> from.abstractName.asJson,
        "to" -> to.abstractName.asJson
      )
    case ConnectionEvent.Refused(error) =>
      EventKind.Connection
        .line("what" -> "refused".asJson, "reason" -> error.message.asJson)
        .at(Severity.Warning)
    case ConnectionEvent.Closed(remote, provenance, reason) =>
      EventKind.Connection.line(
        "remote" -> remote.asJson,
        "what" -> "closed".asJson,
        "provenance" -> provenance.toString.asJson,
        "reason" -> reason.message.asJson
      )
    case ConnectionEvent.Pruned(pruned, number, candidates) =>
      EventKind.Connection.line(
        "what" -> "pruned".asJson,
        "pruned" -> pruned.asJson,
        "number" -> number.asJson,
        "candidates" -> candidates.asJson
      )
    case ConnectionEvent.AcceptRateLimited(delay, connections) =>
      EventKind.Connection.line(
        "what" -> "acceptRateLimited".asJson,
        "delaySeconds" -> seconds(delay).asJson,
        "connections" -> connections.asJson
      )
    case ConnectionEvent.AcceptHardLimit(limit) =>
      EventKind.Connection
        .line("what" -> "acceptHardLimit".asJson, "limit" -> limit.toLong.asJson)
        .at(Severity.Warning)
    case ConnectionEvent.AcceptResumed(connections) =>
      EventKind.Connection.line(
        "what" -> "acceptResumed".asJson,
        "connections" -> connections.asJson
      )

  given Traced[MempoolEvent] =
    case MempoolEvent.Added(id, size, count, bytes) =>
      EventKind.MempoolAdded.line(
        "tx" -> id.hex.asJson,
        "size" -> size.asJson,
        "count" -> count.asJson,
        "bytes" -> bytes.asJson
      )
    case MempoolEvent.Rejected(id, reason) =>
      EventKind.MempoolRejected.line(
        "tx" -> id.map(_.hex).asJson,
        "reason" -> reason.message.asJson
      )
    case MempoolEvent.Waiting(id, needed, free) =>
      EventKind.MempoolWaiting.line(
        "tx" -> id.map(_.hex).asJson,
        "reason" -> Rejection.Full(needed, free).message.asJson
      )
    case MempoolEvent.WaitingForTime(id, used, capacity) =>
      EventKind.MempoolWaiting.line(
        "tx" -> id.map(_.hex).asJson,
        "reason" -> "validation time of the held transactions over the capacity".asJson,
        "usedSeconds" -> seconds(used).asJson,
        "capacitySeconds" -> seconds(capacity).asJson
      )
    case MempoolEvent.Adopted(id) => EventKind.MempoolAdopted.line("tx" -> id.hex.asJson)
    case MempoolEvent.Removed(id) => EventKind.MempoolRemoved.line("tx" -> id.hex.asJson)
    case MempoolEvent.Dropped(id, reason, cause) =>
      EventKind.MempoolDropped.line(
        "tx" -> id.hex.asJson,
        "reason" -> reason.message.asJson,
        "cause" -> cause.productPrefix.asJson
      )
    case MempoolEvent.Synced(count, bytes, dropped) =>
      EventKind.MempoolSynced.line(
        "count" -> count.asJson,
        "bytes" -> bytes.asJson,
        "dropped" -> dropped.asJson
      )

  /** A `ChainHash` as the reference renders it: the full hash, or `GenesisHash` (cardano-node
    * 11.1.3 `Cardano/Node/Tracing/Render.hs` 176-178).
    */
  private[log] def chainHash(h: Option[Bytes]): Json = h.fold("GenesisHash")(_.hex).asJson

  /** `HotKey.KESInfo` as cardano-node 11.1.3 renders it (`Tracers/KESInfo.hs` 60-86), its labels
    * included: `endPeriod` is the key's period and `evolution` the end of its window.
    */
  private[log] def kesInfo(i: KesInfo): List[(String, Json)] =
    val tail = List(
      "startPeriod" -> i.start.asJson,
      "endPeriod" -> i.period.asJson,
      "evolution" -> i.end.asJson
    )
    if i.remaining > 7 then ("kind" -> "KESInfo".asJson) :: tail
    else ("kind" -> "ExpiryLogMessage".asJson) :: ("keyExpiresIn" -> i.remaining.asJson) :: tail

  /** The severity of `Forge.StateInfo` (`Tracers/KESInfo.hs` 132-148). */
  private def kesSeverity(i: KesInfo): Severity =
    if i.remaining > 7 then Severity.Info
    else if i.remaining <= 1 then Severity.Alert
    else Severity.Warning

  /** The forging loop's traces with the reference's machine fields (cardano-node 11.1.3
    * `Tracers/Consensus.hs` 1277-1410, the `DNormal` forms); its `kind` is the event name. The
    * reasons are rendered as the reference renders them through the hard-fork combinator
    * (`Tracing/Era/HardFork.hs` 288-348, `Tracers/KESInfo.hs` 170-182, `Tracing/Era/Shelley.hs`
    * 1318-1323), except that of a block our rules refuse, which is Scarab's `ValidationError`
    * (`rule`, `reason`).
    */
  /** The blockchain time's traces with the reference's machine fields (cardano-node 11.1.3
    * `Tracers/Consensus.hs` 1810-1823).
    */
  given Traced[xyz.kd5ujc.scarab.node.GenesisTrace] = GenesisLines.line(_)

  given Traced[ClockTrace] =
    case ClockTrace.StartTimeInTheFuture(start, wait) =>
      EventKind.StartTimeInTheFuture.line(
        "systemStart" -> start.toString.asJson,
        "toWait" -> s"${wait.toMillis / 1000.0}s".asJson
      )
    case ClockTrace.CurrentSlotUnknown(time) =>
      EventKind.CurrentSlotUnknown.line("time" -> time.toString.asJson)
    case ClockTrace.SystemClockMovedBack(previous, now) =>
      EventKind.SystemClockMovedBack.line(
        "prevTime" -> previous.toString.asJson,
        "newTime" -> now.toString.asJson
      )

  given Traced[ForgeTrace] =
    case ForgeTrace.StartLeadershipCheck(slot) =>
      EventKind.StartLeadershipCheck.line("slot" -> slot.asJson)
    case ForgeTrace.SlotIsImmutable(slot, tip, tipBlockNo) =>
      EventKind.SlotIsImmutable.line(
        "slot" -> slot.asJson,
        "tip" -> pointForDetails(tip).asJson,
        "tipBlockNo" -> tipBlockNo.asJson
      )
    case ForgeTrace.BlockFromFuture(slot, tip) =>
      EventKind.BlockFromFuture.line("current slot" -> slot.asJson, "tip" -> tip.asJson)
    case ForgeTrace.BlockContext(slot, blockNo, prev) =>
      EventKind.BlockContext.line(
        "current slot" -> slot.asJson,
        "tip" -> pointForDetails(prev).asJson,
        "tipBlockNo" -> blockNo.asJson
      )
    case ForgeTrace.LedgerState(slot, _)  => EventKind.LedgerState.line("slot" -> slot.asJson)
    case ForgeTrace.NoLedgerView(slot, _) => EventKind.NoLedgerView.line("slot" -> slot.asJson)
    case ForgeTrace.LedgerView(slot)      => EventKind.LedgerView.line("slot" -> slot.asJson)
    case ForgeTrace.ForgeStateUpdateError(slot, error) =>
      val kind = error match
        case KesEvolutionError.CouldNotEvolve(_, _)     => "KESCouldNotEvolve"
        case KesEvolutionError.KeyAlreadyPoisoned(_, _) => "KESKeyAlreadyPoisoned"
      EventKind.ForgeStateUpdateError.line(
        "slot" -> slot.asJson,
        "reason" -> Json.obj(
          "kind" -> "HardForkForgeStateUpdateError".asJson,
          "forgeStateUpdateError" -> Json.obj(
            "kind" -> kind.asJson,
            "kesInfo" -> Json.obj(kesInfo(error.info)*),
            "targetPeriod" -> error.target.asJson
          )
        )
      )
    case ForgeTrace.NodeCannotForge(slot, CannotForge.KeyNotUsableYet(current, start)) =>
      EventKind.NodeCannotForge.line(
        "slot" -> slot.asJson,
        "reason" -> Json.obj(
          "kind" -> "PraosCannotForgeKeyNotUsableYet".asJson,
          "currentKesPeriod" -> current.asJson,
          "opCertStartingKesPeriod" -> start.asJson
        )
      )
    case ForgeTrace.NodeNotLeader(slot) => EventKind.NodeNotLeader.line("slot" -> slot.asJson)
    case ForgeTrace.NodeIsLeader(slot)  => EventKind.NodeIsLeader.line("slot" -> slot.asJson)
    case ForgeTrace.ForgeTickedLedgerState(slot, prev) =>
      EventKind.ForgeTickedLedgerState.line(
        "slot" -> slot.asJson,
        "prev" -> pointForDetails(prev).asJson
      )
    case ForgeTrace.ForgingMempoolSnapshot(slot, prev, mempoolHash, mempoolSlot) =>
      EventKind.ForgingMempoolSnapshot.line(
        "slot" -> slot.asJson,
        "prev" -> pointForDetails(prev).asJson,
        "mempoolHash" -> chainHash(mempoolHash),
        "mempoolSlot" -> mempoolSlot.asJson
      )
    case ForgeTrace.ForgedBlock(slot, _, hash, blockNo, prevHash) =>
      EventKind.ForgedBlock.line(
        "slot" -> slot.asJson,
        "block" -> hash.hex.asJson,
        "blockNo" -> blockNo.asJson,
        "blockPrev" -> chainHash(prevHash)
      )
    case ForgeTrace.DidntAdoptBlock(slot, _) =>
      EventKind.DidntAdoptBlock.line("slot" -> slot.asJson)
    case ForgeTrace.ForgedInvalidBlock(slot, _, reason) =>
      EventKind.ForgedInvalidBlock.line(
        "slot" -> slot.asJson,
        "reason" -> Json.obj("rule" -> reason.rule.asJson, "reason" -> reason.reason.asJson)
      )
    case ForgeTrace.AdoptedBlock(slot, hash, blockSize, _) =>
      EventKind.AdoptedBlock.line(
        "slot" -> slot.asJson,
        "blockHash" -> hash.hex.asJson,
        "blockSize" -> blockSize.asJson
      )

  given Traced[ForgeEvent] =
    // the reference prints the constructor (`showT`) as a string
    case ForgeEvent.BlockForgingUpdate(state) =>
      EventKind.BlockForgingUpdate.line("enabled" -> state.toString.asJson)
    case ForgeEvent.Started(pool, vrfHash, start, evolutions, counter, period) =>
      EventKind.ForgeStarted.line(
        "pool" -> pool.hex.asJson,
        "vrfKeyHash" -> vrfHash.hex.asJson,
        "kesStart" -> start.asJson,
        "kesEvolutions" -> evolutions.asJson,
        "opCertCounter" -> counter.asJson,
        "kesPeriod" -> period.asJson,
        "remainingKesPeriods" -> (start + evolutions - period).asJson
      )
    case ForgeEvent.Loop(trace)  => Traced[ForgeTrace].line(trace)
    case ForgeEvent.Clock(trace) => Traced[ClockTrace].line(trace)
    case ForgeEvent.Leios(trace) => Traced[LeiosForgeTrace].line(trace)
    // the label of the reference's one block forging for all eras (consensus 82ecba329
    // `Cardano/Node.hs` 1024), ahead of the key's fields (`TraceLabelCreds`, `Consensus.hs` 104-106)
    case ForgeEvent.StateInfo(info) =>
      EventKind.StateInfo
        .line(("credentials" -> "Cardano".asJson) :: kesInfo(info)*)
        .at(kesSeverity(info))
    case ForgeEvent.Forged(
          slot,
          epoch,
          no,
          hash,
          prev,
          txs,
          size,
          kes,
          rel,
          stake,
          total,
          counter
        ) =>
      EventKind.Forged.line(
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
        "totalStake" -> total.asJson,
        "opCertCounter" -> counter.asJson
      )
    case ForgeEvent.Adopted(b, latency) =>
      EventKind.Adopted.line("block" -> block(b), "latencyMs" -> latency.asJson)
    case ForgeEvent.EpochSummary(epoch, stats) =>
      EventKind.ForgeEpoch.line(("epoch" -> epoch.asJson) +: Reports.forgeStats(stats)*)
    case ForgeEvent.Scheduled(epoch, slots, expected, settled) =>
      EventKind.LeaderSchedule.line(
        "epoch" -> epoch.asJson,
        "slots" -> slots.asJson,
        "expected" -> BigDecimal(expected).setScale(2, BigDecimal.RoundingMode.HALF_EVEN).asJson,
        "settled" -> settled.asJson
      )
    case ForgeEvent.Discarded(slot, reason, hash) =>
      val why = reason match
        case DiscardReason.BlockContext => "blockContext"
        case DiscardReason.Inputs       => "inputs"
        case DiscardReason.Credentials  => "credentials"
        case DiscardReason.Refused      => "refused"
        case DiscardReason.NotLed       => "notLeader"
        case DiscardReason.Certificate  => "certificate"
      EventKind.ForgeDiscarded.line(
        "slot" -> slot.asJson,
        "reason" -> why.asJson,
        "hash" -> hash.map(_.hex).asJson
      )
    case ForgeEvent.Lost(slot, hash, reason) =>
      EventKind.Lost.line(
        "slot" -> slot.asJson,
        "hash" -> hash.hex.asJson,
        "reason" -> String.valueOf(reason).asJson
      )
    case ForgeEvent.NotKept(slot, hash, retention) =>
      EventKind.NotKept.line(
        "slot" -> slot.asJson,
        "hash" -> hash.hex.asJson,
        "reason" -> retention.message.asJson
      )
    case ForgeEvent.KesEvolved(period, rel) =>
      EventKind.KesEvolved.line("kesPeriod" -> period.asJson, "relativeKesPeriod" -> rel.asJson)
    case ForgeEvent.KesReloaded(counter, start, evolutions, period) =>
      EventKind.KesReloaded.line(
        "opCertCounter" -> counter.asJson,
        "kesStart" -> start.asJson,
        "kesEvolutions" -> evolutions.asJson,
        "kesPeriod" -> period.asJson,
        "remainingKesPeriods" -> (start + evolutions - period).asJson
      )
    case ForgeEvent.KesReloadRefused(reason) =>
      EventKind.KesReloadRefused.line(
        "kind" -> reason.productPrefix.asJson,
        "message" -> reason.message.asJson
      )
    case ForgeEvent.KesFileAbsent(period, file) =>
      EventKind.KesFileAbsent.line("kesPeriod" -> period.asJson, "file" -> file.asJson)
    case ForgeEvent.KesWrittenByRename(reason) =>
      val errno = reason match
        case NoExchange.Refused(n)                        => Some(n)
        case NoExchange.NotLinux | NoExchange.NoRenameat2 => None
      EventKind.KesWrittenByRename.line(
        "reason" -> reason.productPrefix.asJson,
        "errno" -> errno.asJson
      )
    case ForgeEvent.KesKeyAhead(key, period) =>
      EventKind.KesKeyAhead.line(
        "keyPeriod" -> key.asJson,
        "kesPeriod" -> period.asJson,
        "message" -> (s"the KES key in its file is at period $key, ahead of the current period " +
          s"$period, and cannot sign before then: restore the unevolved KES key, or issue a new " +
          "KES key and operational certificate").asJson
      )
    case ForgeEvent.KesPersistFailed(period, error) =>
      EventKind.KesPersistFailed.line(
        "kesPeriod" -> period.asJson,
        "kind" -> error.productPrefix.asJson,
        "message" -> error.message.asJson
      )
    case ForgeEvent.KeyFileGroupAccess(file, mode) =>
      EventKind.KeyFileGroupAccess.line(
        "file" -> file.asJson,
        "mode" -> ForgeKeyProblem.octal(mode).asJson
      )
    case ForgeEvent.Failed(slot, error) =>
      EventKind.ForgeFailed.line(
        "slot" -> slot.asJson,
        "kind" -> error.productPrefix.asJson,
        "message" -> error.message.asJson
      )
    case ForgeEvent.Defect(slot, defect, evicted) =>
      val detail = defect match
        case ForgeDefect.BodySize(actual, maximum) =>
          List("bodySize" -> actual.asJson, "maxBodySize" -> maximum.asJson)
        case ForgeDefect.EndorserBlockSize(actual, maximum) =>
          List("endorserBlockSize" -> actual.asJson, "maxEndorserBlockSize" -> maximum.asJson)
        case ForgeDefect.BlockDecode(cause) => List("cause" -> cause.message.asJson)
      EventKind.ForgeDefect.line(
        (List("slot" -> slot.asJson, "kind" -> defect.productPrefix.asJson) ++ detail :+
          ("evicted" -> evicted.map(_.hex).asJson))*
      )

  /** The forge loop's Leios lines (`TraceLeiosKernel` 's `ToJSON`, `LeiosDemoTypes.hs:1980-1984,
    * 2019-2047, 2133-2137` at ouroboros-consensus 2416493b): hashes in hex, the kernel messages in
    * the reference's words with the point as the voting thread's lines show it.
    */
  given Traced[LeiosForgeTrace] =
    case LeiosForgeTrace.NotDownloaded(point) =>
      EventKind.LeiosKernelMsg.line(
        "message" -> s"EB not yet downloaded: ${leiosPoint(point)}".asJson
      )
    case LeiosForgeTrace.NoCertificate(point) =>
      EventKind.LeiosKernelMsg.line(
        "message" -> s"EB downloaded but no certificate: ${leiosPoint(point)}".asJson
      )
    case LeiosForgeTrace.BlockCertified(atSlot, point) =>
      EventKind.LeiosBlockCertified.line(
        "atSlot" -> atSlot.asJson,
        "ebSlot" -> point.slot.toBigInt.asJson,
        "ebHash" -> point.hash.bytes.hex.asJson
      )
    case LeiosForgeTrace.BlockForged(slot, point, txs, ebSize, closureSize) =>
      EventKind.LeiosBlockForged.line(
        "slot" -> slot.asJson,
        "hash" -> point.hash.bytes.hex.asJson,
        "numTxs" -> txs.asJson,
        "ebSize" -> ebSize.asJson,
        "closureSize" -> closureSize.asJson,
        "mempoolRestSize" -> 0.asJson
      )
    case LeiosForgeTrace.BlockAnnounced(rbHash, point) =>
      EventKind.LeiosBlockAnnounced.line(
        "rbHash" -> rbHash.hex.asJson,
        "ebSlot" -> point.slot.toBigInt.asJson,
        "ebHash" -> point.hash.bytes.hex.asJson
      )
    case LeiosForgeTrace.CertifiedAndAnnounced(atSlot, rbHash) =>
      EventKind.LeiosCertifiedAndAnnounced.line(
        "slotNo" -> atSlot.asJson,
        "rbHash" -> rbHash.hex.asJson
      )

  /** The voting thread's lines (`TraceLeiosKernel`'s `ToJSON`, `LeiosDemoTypes.hs:1975-2090`,
    * ouroboros-consensus 2416493b): hashes in hex (`prettyRbHash`, `prettyEbHash`), durations in
    * seconds, a vote by its block's hash and its seat and a weight as a rational, as the node's
    * LeiosNotify client's lines write them, and the reason a vote was not cast as the reference
    * words it (`notVotedReasonText`, `:2177-2184`), each refusal in Scarab's rendering of it.
    */
  given Traced[LeiosKernelEvent[Rejection, LeiosClosureDefect]] =
    case LeiosKernelEvent.VotingDisabled =>
      EventKind.LeiosKernelMsg.line(
        "message" -> "runLeiosVoting: disabled because no topLevelConfigVotingKeys".asJson
      )
    case LeiosKernelEvent.NoVoteOnset(point) =>
      EventKind.LeiosKernelMsg.line(
        "message" -> s"newVoteTimers: no wall-clock onset for ${leiosPoint(point)}".asJson
      )
    case LeiosKernelEvent.VoteScheduled(point, voteIn, deadlineIn) =>
      EventKind.LeiosVoteScheduled.line(
        "ebHash" -> point.hash.bytes.hex.asJson,
        "ebSlot" -> point.slot.toBigInt.asJson,
        "voteIn" -> durationSeconds(voteIn).asJson,
        "deadlineIn" -> durationSeconds(deadlineIn).asJson
      )
    case LeiosKernelEvent.EbValidated(point, reapplied, applied, duration) =>
      EventKind.LeiosEbValidated.line(
        "ebHash" -> point.hash.bytes.hex.asJson,
        "ebSlot" -> point.slot.toBigInt.asJson,
        "reapplied" -> reapplied.asJson,
        "applied" -> applied.asJson,
        "duration" -> durationSeconds(duration).asJson
      )
    case LeiosKernelEvent.TxsPrevalidated(point, offered, independent, validated, duration) =>
      EventKind.LeiosTxsPrevalidated.line(
        "ebHash" -> point.hash.bytes.hex.asJson,
        "ebSlot" -> point.slot.toBigInt.asJson,
        "offered" -> offered.asJson,
        "independent" -> independent.asJson,
        "validated" -> validated.asJson,
        "duration" -> durationSeconds(duration).asJson
      )
    case LeiosKernelEvent.PrevalidationSkipped(point, reason) =>
      val (kind, detail) = reason match
        case LeiosPrevalidation.Skip.NoDijkstraTip => ("noDijkstraTip", None)
        case LeiosPrevalidation.Skip.OtherEpoch(tip, block) =>
          ("otherEpoch", Some(s"tip epoch $tip, block epoch $block"))
        case LeiosPrevalidation.Skip.Tick(cause)  => ("tick", Some(cause.show))
        case LeiosPrevalidation.Skip.Store(cause) => ("store", Some(cause.message))
      EventKind.LeiosPrevalidationSkipped.line(
        "ebHash" -> point.hash.bytes.hex.asJson,
        "ebSlot" -> point.slot.toBigInt.asJson,
        "reason" -> kind.asJson,
        "detail" -> detail.asJson
      )
    case LeiosKernelEvent.Voted(vote, weight) =>
      EventKind.LeiosVoted.line(
        "rbHash" -> vote.announcingRbHash.bytes.hex.asJson,
        "seat" -> vote.voter.index.asJson,
        "weight" -> weightOf(weight)
      )
    // the LeiosNotify client's line of a counted vote, of no connection for the node's own
    case LeiosKernelEvent.VoteAcquired(vote, tally) =>
      EventKind.LeiosVoteAcquired.line(
        "connection" -> Json.Null,
        "rbHash" -> vote.announcingRbHash.bytes.hex.asJson,
        "seat" -> vote.voter.index.asJson,
        "weight" -> weightOf(tally.weight),
        "tally" -> weightOf(tally.tally),
        "threshold" -> weightOf(tally.threshold)
      )
    case LeiosKernelEvent.Certified(rbHash) =>
      EventKind.LeiosCertified.line("rbHash" -> rbHash.bytes.hex.asJson)
    case LeiosKernelEvent.SeatNotVoted(point, _, answer) =>
      notVoted(point, s"voteRejected: ${addVoteResult(answer)}")
    case LeiosKernelEvent.NotVoted(point, reason) =>
      notVoted(
        point,
        reason match
          case LeiosNotVotedReason.ChainTipDoesNotAnnounce => "chainTipDoesNotAnnounce"
          case LeiosNotVotedReason.TooLate                 => "tooLate"
          case LeiosNotVotedReason.NotOnCommittee          => "notOnCommittee"
          case LeiosNotVotedReason.EbTxsInvalid(cause)     => s"ebTxsInvalid: ${cause.message}"
          case LeiosNotVotedReason.VoteRejected(answers) =>
            s"voteRejected: all ${answers.length} votes rejected"
          case LeiosNotVotedReason.ClosureUnavailable(cause) =>
            s"closureUnavailable: ${closureError(cause)}"
      )

  private def notVoted(point: EbPoint, reason: String): Line =
    EventKind.LeiosNotVoted.line(
      "ebHash" -> point.hash.bytes.hex.asJson,
      "ebSlot" -> point.slot.toBigInt.asJson,
      "reason" -> reason.asJson
    )

  /** `prettyLeiosPoint`: the slot and the endorser block's hash. */
  private def leiosPoint(point: EbPoint): String =
    s"${point.slot.toBigInt}@${point.hash.bytes.hex}"

  /** A weight as the LeiosNotify client's lines write one, `numerator/denominator`. */
  private def weightOf(w: Weight): Json = s"${w.value.numerator}/${w.value.denominator}".asJson

  private def durationSeconds(d: scala.concurrent.duration.FiniteDuration): BigDecimal =
    BigDecimal(BigDecimal(d.toNanos, 9).underlying.stripTrailingZeros)

  /** `show` of an `AddVoteResult` that counted nothing. */
  private def addVoteResult(answer: AddVoteResult): String = answer match
    case AddVoteResult.NoCommittee     => "NoCommittee"
    case AddVoteResult.Invalid(reason) => s"VoteInvalid $reason"
    case AddVoteResult.AlreadyKnown    => "AlreadyKnown"
    case AddVoteResult.NotVotable      => "NotVotable"
    case AddVoteResult.Added(tally, _) => s"Added $tally"

  /** `show` of a `LeiosClosureError`. */
  private def closureError(defect: LeiosClosureDefect): String = defect match
    case LeiosClosureDefect.Missing(eb) => s"LeiosClosureMissing ${eb.bytes.hex}"
    case LeiosClosureDefect.TxUndecodable(eb, tx, cause) =>
      s"LeiosClosureTxUndecodable ${eb.bytes.hex} ${tx.bytes.hex} $cause"
