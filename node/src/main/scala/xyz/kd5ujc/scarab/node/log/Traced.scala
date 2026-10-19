// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent, SchedulingReport}
import xyz.kd5ujc.scarab.network.ConnectionEvent
import xyz.kd5ujc.scarab.node.Reports
import xyz.kd5ujc.scarab.node.forge.ForgeEvent
import xyz.kd5ujc.scarab.node.mempool.MempoolEvent
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
    FollowerEvent.EpochValidated | FollowerEvent.ByronEpochValidated | FollowerEvent.LedgerEpoch

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
    case StorageEvent.SnapshotWritten(name, a, bytes, millis) =>
      EventKind.SnapshotWritten.line(
        "file" -> name.asJson,
        "anchor" -> anchor(a),
        "bytes" -> bytes.asJson,
        "millis" -> millis.asJson
      )
    case StorageEvent.SnapshotFailed(reason) =>
      EventKind.SnapshotFailed.line("reason" -> String.valueOf(reason).asJson)
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

  /** What the connection manager did (G2); a refusal is a warning. */
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
    case MempoolEvent.Adopted(id) => EventKind.MempoolAdopted.line("tx" -> id.hex.asJson)
    case MempoolEvent.Dropped(id, reason) =>
      EventKind.MempoolDropped.line("tx" -> id.hex.asJson, "reason" -> reason.message.asJson)
    case MempoolEvent.Synced(count, bytes, dropped) =>
      EventKind.MempoolSynced.line(
        "count" -> count.asJson,
        "bytes" -> bytes.asJson,
        "dropped" -> dropped.asJson
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
    case ForgeEvent.Skipped(slot, reason) =>
      EventKind.ForgeSkipped.line("slot" -> slot.asJson, "reason" -> reason.message.asJson)
    case ForgeEvent.Failed(slot, error) =>
      EventKind.ForgeFailed.line(
        "slot" -> slot.asJson,
        "kind" -> error.productPrefix.asJson,
        "message" -> error.message.asJson
      )
