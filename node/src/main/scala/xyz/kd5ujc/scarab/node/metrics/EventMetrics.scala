// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, FollowerEvents}
import xyz.kd5ujc.scarab.network.{ConnectionEvent, ConnectionEvents}
import xyz.kd5ujc.scarab.node.{InboundEvent, InboundEvents, Peer}
import xyz.kd5ujc.scarab.node.forge.{ForgeEvent, ForgeEvents}
import xyz.kd5ujc.scarab.node.mempool.{MempoolEvent, MempoolEvents}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}
import xyz.kd5ujc.scarab.storage.{StorageEvent, StorageEvents}

/** The node's events as measurements. Every part of the node already reports what it does as a
  * value of a closed event type; this is the one place where those values become metrics, so the
  * call sites know nothing of them (the JSON log listens to the same events, see [[EventSink]]).
  *
  * It keeps the few facts an event does not carry on its own: how many blocks the immutable chain
  * holds, and when the operational certificate expires.
  */
final class EventMetrics[F[_]: Sync] private (
    immutableCount: Ref[F, Long],
    kesExpiry: Ref[F, Option[BigInt]]
)(using metrics: Metrics[F]):
  import Catalogue.*

  private def number(n: BigInt): Double = n.toDouble

  private def slot(point: ChainSync.Point): Double = point match
    case ChainSync.Point.Origin         => 0.0
    case ChainSync.Point.Block(slot, _) => number(slot.value)

  val follower: FollowerEvents[F] = new FollowerEvents[F]:
    def emit(event: FollowerEvent): F[Unit] = event match
      case FollowerEvent.Extended(_, _, tip, era) =>
        metrics.inc(blocksApplied, era) *>
          Era.values.toList.traverse_(e =>
            metrics.set(currentEra, e, if e == era then 1.0 else 0.0)
          ) *>
          metrics.set(peerTipSlot, slot(tip.point)) *>
          metrics.set(peerTipBlock, number(tip.blockNo.value))
      case FollowerEvent.RolledBack(_, _, depth) =>
        metrics.inc(rollbacks) *> metrics.inc(forks) *> metrics.observe(
          rollbackDepth,
          depth.toDouble
        )
      case FollowerEvent.Immutable(_) =>
        immutableCount
          .updateAndGet(_ + 1)
          .flatMap(n => metrics.set(immutableBlocks, n.toDouble))
      case FollowerEvent.LedgerEpoch(_, utxo, accounts, pools, _, _, _, _) =>
        metrics.set(ledgerUtxo, utxo.toDouble) *>
          metrics.set(ledgerAccounts, accounts.toDouble) *>
          metrics.set(ledgerPools, pools.toDouble)
      case FollowerEvent.Reconnecting(_, _) => metrics.inc(peerReconnects)
      case FollowerEvent.Connected(_) | FollowerEvent.EpochEntered(_, _) |
          FollowerEvent.EraEntered(_, _, _, _) | FollowerEvent.EpochValidated(_, _, _, _, _) |
          FollowerEvent.ByronEpochValidated(_, _, _, _, _) =>
        Sync[F].unit

  val forge: ForgeEvents[F] = new ForgeEvents[F]:
    private def kes(period: BigInt): F[Unit] =
      metrics.set(currentKesPeriod, number(period)) *>
        kesExpiry.get.flatMap(
          _.traverse_(expiry => metrics.set(remainingKesPeriods, number(expiry - period)))
        )

    def emit(event: ForgeEvent): F[Unit] = event match
      case ForgeEvent.Started(_, _, start, evolutions) =>
        kesExpiry.set(Some(start + evolutions)) *>
          metrics.set(forgingEnabled, 1.0) *>
          metrics.set(opCertStartKesPeriod, number(start)) *>
          metrics.set(opCertExpiryKesPeriod, number(start + evolutions))
      case ForgeEvent.Forged(slot, _, _, _, _, _, _, period, _, _, _) =>
        metrics.set(forgedSlotLast, number(slot)) *> kes(period)
      case ForgeEvent.KesEvolved(period, _) => kes(period)
      case ForgeEvent.Adopted(_, latency) =>
        latency.traverse_(ms => metrics.observe(forgeAdoptionSeconds, ms / 1000.0))
      case ForgeEvent.Lost(_, _, _) | ForgeEvent.Skipped(_, _) | ForgeEvent.Failed(_, _) |
          ForgeEvent.EpochSummary(_, _) =>
        // counted from the forger's own statistics at every scrape ([[Collectors.forging]])
        Sync[F].unit

  val mempool: MempoolEvents[F] = new MempoolEvents[F]:
    def emit(event: MempoolEvent): F[Unit] = event match
      case MempoolEvent.Added(_, _, _, _) => metrics.inc(txsProcessed)
      case MempoolEvent.Rejected(_, reason) =>
        metrics.inc(mempoolRejected, RejectionLabel.of(reason))
      case MempoolEvent.Dropped(_, _)   => metrics.inc(mempoolDropped)
      case MempoolEvent.Adopted(_)      => metrics.inc(mempoolAdopted)
      case MempoolEvent.Synced(_, _, _) => Sync[F].unit

  val storage: StorageEvents[F] = new StorageEvents[F]:
    def emit(event: StorageEvent): F[Unit] = event match
      case StorageEvent.FormatMigrated(m) => metrics.inc(snapshotsMigrated, (), m.count.toDouble)
      case StorageEvent.ImmutableOpened(r) =>
        immutableCount.set(r.blocks) *> metrics.set(immutableBlocks, r.blocks.toDouble)
      case StorageEvent.SnapshotSkipped(_, _) =>
        metrics.inc(snapshotProblems, SnapshotProblemLabel.Skipped)
      case StorageEvent.SnapshotRestored(_, _) => metrics.inc(snapshotsRestored)
      case StorageEvent.SnapshotWritten(_, _, bytes, millis) =>
        metrics.inc(snapshotsWritten) *>
          metrics.set(snapshotWriteSeconds, millis / 1000.0) *>
          metrics.set(snapshotBytes, bytes.toDouble)
      case StorageEvent.SnapshotFailed(_) =>
        metrics.inc(snapshotProblems, SnapshotProblemLabel.Failed)
      case StorageEvent.SnapshotRefused(_, _, _) =>
        metrics.inc(snapshotProblems, SnapshotProblemLabel.Refused)
      case StorageEvent.ReplayProgress(_, replayed, millis) =>
        metrics.set(replayedBlocks, replayed.toDouble) *> metrics.set(
          replaySeconds,
          millis / 1000.0
        )
      case StorageEvent.ReplayFinished(_, replayed, millis) =>
        metrics.set(replayedBlocks, replayed.toDouble) *> metrics.set(
          replaySeconds,
          millis / 1000.0
        )
      case StorageEvent.ReplayStarted(_, _) | StorageEvent.NetworkRecorded(_, _) |
          StorageEvent.VolatileOpened(_) | StorageEvent.VolatileResumed(_, _, _) |
          StorageEvent.ShutdownSnapshot(_, _) =>
        Sync[F].unit

  val connection: ConnectionEvents[F] = new ConnectionEvents[F]:
    def emit(event: ConnectionEvent): F[Unit] = metrics.inc(
      connectionEvents,
      event match
        case ConnectionEvent.Opened(_, _)     => ConnectionEventLabel.Opened
        case ConnectionEvent.Changed(_, _, _) => ConnectionEventLabel.Changed
        case ConnectionEvent.Refused(_)       => ConnectionEventLabel.Refused
        case ConnectionEvent.Closed(_, _, _)  => ConnectionEventLabel.Closed
    )

  val inbound: InboundEvents[F] = new InboundEvents[F]:
    def emit(remote: String, event: InboundEvent): F[Unit] = metrics.inc(
      inboundEvents,
      event match
        case InboundEvent.Accepted       => InboundEventLabel.Accepted
        case InboundEvent.Duplex         => InboundEventLabel.Duplex
        case InboundEvent.Unidirectional => InboundEventLabel.Unidirectional
        case InboundEvent.Following(_)   => InboundEventLabel.Following
        case InboundEvent.SessionEnded(_, dropped) =>
          if dropped then InboundEventLabel.SessionDropped else InboundEventLabel.SessionEnded
        case InboundEvent.Closed(_)  => InboundEventLabel.Closed
        case InboundEvent.Refused(_) => InboundEventLabel.Refused
    )

  /** What a peer session measured of its peer. */
  def peer(observation: Peer.Observation): F[Unit] = observation match
    case Peer.Observation.Round(_, received, millis) =>
      metrics.inc(chainSyncRounds) *>
        metrics.inc(chainSyncHeaders, (), received.toDouble) *>
        metrics.observe(chainSyncRoundSeconds, millis / 1000.0)
    case Peer.Observation.Fetched(millis) =>
      metrics.observe(blockFetchSeconds, millis / 1000.0)

  /** The totals of the header-first validation so far. */
  def headers(validated: Long, unchecked: Long, waited: Long): F[Unit] =
    metrics.advance(headersValidated, validated.toDouble) *>
      metrics.advance(headersUnchecked, unchecked.toDouble) *>
      metrics.advance(headersWaited, waited.toDouble)

object EventMetrics:
  def create[F[_]: Sync](using Metrics[F]): F[EventMetrics[F]] =
    (Ref.of[F, Long](0L), Ref.of[F, Option[BigInt]](None)).mapN(new EventMetrics(_, _))
