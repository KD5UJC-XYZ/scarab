// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent, SchedulingReport}
import xyz.kd5ujc.scarab.network.{ConnectionEvent, DiffTime}
import xyz.kd5ujc.scarab.ledger.ForgeKeyProblem
import xyz.kd5ujc.scarab.node.Reports
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.forge.{
  CannotForge,
  ForgeEvent,
  ForgeTrace,
  KesEvolutionError,
  KesInfo
}
import xyz.kd5ujc.scarab.node.mempool.{MempoolEvent, Rejection}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.StorageEvent

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

  given [E](using traced: Traced[E]): Traced[Scheduled[E]] = scheduled =>
    val line = traced.line(scheduled.event)
    line.copy(fields =
      line.fields ++ scheduled.report.map(r => "scheduling" -> Reports.scheduling(r))
    )

  /** The follower's events that make a line: [[FollowerEvent.Immutable]] is bookkeeping for the
    * metrics (a block moved past `k`), one per block, and has none.
    */
  type FollowerLine = FollowerEvent.Connected | FollowerEvent.Extended | FollowerEvent.RolledBack |
    FollowerEvent.EpochEntered | FollowerEvent.Reconnecting | FollowerEvent.EraEntered |
    FollowerEvent.EpochValidated | FollowerEvent.ByronEpochValidated | FollowerEvent.LedgerEpoch |
    FollowerEvent.Held

  given Traced[FollowerLine] =
    case FollowerEvent.Connected(p) => EventKind.Connected.line("intersection" -> point(p))
    case FollowerEvent.Extended(b, epoch, tip, era) =>
      EventKind.Extended.line(
        "block" -> block(b),
        "epoch" -> epoch.asJson,
        "era" -> era.toString.asJson,
        "peerTip" -> point(tip.point)
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
    case FollowerEvent.Held(p, failure) =>
      EventKind.BlockHeld.line(
        "point" -> point(p),
        "tx" -> failure.txId.hex.asJson,
        "reason" -> failure.message.asJson
      )

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
    case StorageEvent.NetworkRecorded(id, adopted) =>
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
        "adoptedExisting" -> adopted.asJson
      )
    case StorageEvent.VolatileOpened(r) =>
      EventKind.VolatileOpened.line(
        "blocks" -> r.blocks.asJson,
        "files" -> r.files.asJson,
        "truncatedBytes" -> r.truncatedBytes.asJson,
        "removedFiles" -> r.removedFiles.asJson
      )
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
        "from" -> from.toString.asJson,
        "to" -> to.toString.asJson
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
    case MempoolEvent.Adopted(id) => EventKind.MempoolAdopted.line("tx" -> id.hex.asJson)
    case MempoolEvent.Removed(id) => EventKind.MempoolRemoved.line("tx" -> id.hex.asJson)
    case MempoolEvent.Dropped(id, reason) =>
      EventKind.MempoolDropped.line("tx" -> id.hex.asJson, "reason" -> reason.message.asJson)
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
    case ForgeEvent.Loop(trace) => Traced[ForgeTrace].line(trace)
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
    case ForgeEvent.Lost(slot, hash, reason) =>
      EventKind.Lost.line(
        "slot" -> slot.asJson,
        "hash" -> hash.hex.asJson,
        "reason" -> String.valueOf(reason).asJson
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
