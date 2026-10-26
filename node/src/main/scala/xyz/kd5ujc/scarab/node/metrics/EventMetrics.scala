// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent, FollowerEvents}
import xyz.kd5ujc.scarab.network.{ConnectionEvent, ConnectionEvents}
import xyz.kd5ujc.scarab.network.selection.{
  Churn,
  FetchMode,
  Gsm,
  LedgerStateJudgement,
  OutboundConnectionsState,
  Temperature,
  Trace
}
import xyz.kd5ujc.scarab.node.{
  HeaderGate,
  InboundEvent,
  InboundEvents,
  LedgerPeersTrace,
  Output,
  Peer,
  PeerNetwork,
  StallWatchdog
}
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

  private def flag(on: Boolean): Double = if on then 1.0 else 0.0

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
      case FollowerEvent.Held(_, _)         => metrics.inc(blocksHeld)
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

    /** The operational certificate in force, at start-up and after every reload: where its window
      * is, its counter, and how many periods are left now.
      */
    private def certificate(
        start: BigInt,
        evolutions: BigInt,
        counter: BigInt,
        period: BigInt
    ): F[Unit] =
      kesExpiry.set(Some(start + evolutions)) *>
        metrics.set(opCertStartKesPeriod, number(start)) *>
        metrics.set(opCertExpiryKesPeriod, number(start + evolutions)) *>
        metrics.set(opCertCounter, number(counter)) *>
        kes(period)

    def emit(event: ForgeEvent): F[Unit] = event match
      case ForgeEvent.Started(_, _, start, evolutions, counter, period) =>
        certificate(start, evolutions, counter, period) *> metrics.set(forgingEnabled, 1.0)
      case ForgeEvent.KesReloaded(counter, start, evolutions, period) =>
        certificate(start, evolutions, counter, period) *> metrics.inc(
          kesReloads,
          ReloadLabel.Reloaded
        )
      case ForgeEvent.KesReloadRefused(_)    => metrics.inc(kesReloads, ReloadLabel.Refused)
      case ForgeEvent.KesPersistFailed(_, _) => metrics.inc(kesPersistFailures)
      case ForgeEvent.Forged(slot, _, _, _, _, _, _, period, _, _, _, _) =>
        metrics.set(forgedSlotLast, number(slot)) *> kes(period)
      case ForgeEvent.KesEvolved(period, _) => kes(period)
      case ForgeEvent.Adopted(_, latency) =>
        latency.traverse_(ms => metrics.observe(forgeAdoptionSeconds, ms / 1000.0))
      case ForgeEvent.Lost(_, _, _) | ForgeEvent.Skipped(_, _) | ForgeEvent.Failed(_, _) |
          ForgeEvent.EpochSummary(_, _) | ForgeEvent.KeyFileGroupAccess(_, _) =>
        // counted from the forger's own statistics at every scrape ([[Collectors.forging]])
        Sync[F].unit

  val mempool: MempoolEvents[F] = new MempoolEvents[F]:
    def emit(event: MempoolEvent): F[Unit] = event match
      case MempoolEvent.Added(_, _, _, _) => metrics.inc(txsProcessed)
      case MempoolEvent.Rejected(_, reason) =>
        metrics.inc(mempoolRejected, RejectionLabel.of(reason))
      case MempoolEvent.Waiting(_, _, _) => Sync[F].unit
      case MempoolEvent.Dropped(_, _)    => metrics.inc(mempoolDropped)
      case MempoolEvent.Adopted(_)       => metrics.inc(mempoolAdopted)
      case MempoolEvent.Removed(_)       => metrics.inc(mempoolRemoved)
      case MempoolEvent.Synced(_, _, _)  => Sync[F].unit

  /** The phase of the start, as the one-hot gauge [[nodePhase]]: the storage events move it from
    * restoring to replaying, the node itself says when it is following.
    */
  def phase(current: PhaseLabel): F[Unit] =
    PhaseLabel.values.toList.traverse_(p =>
      metrics.set(nodePhase, p, if p == current then 1.0 else 0.0)
    )

  private def replayReached(at: Option[BlockRef], count: Long, millis: Long): F[Unit] =
    metrics.set(replayedBlocks, count.toDouble) *>
      metrics.set(replaySeconds, millis / 1000.0) *>
      at.traverse_(b =>
        metrics.set(replayBlock, number(b.blockNo)) *> metrics.set(replaySlot, number(b.slot))
      )

  val storage: StorageEvents[F] = new StorageEvents[F]:
    def emit(event: StorageEvent): F[Unit] = event match
      case StorageEvent.FormatMigrated(m) => metrics.inc(snapshotsMigrated, (), m.count.toDouble)
      case StorageEvent.ImmutableOpened(r) =>
        immutableCount.set(r.blocks) *> metrics.set(immutableBlocks, r.blocks.toDouble)
      case StorageEvent.SnapshotSkipped(_, _) =>
        metrics.inc(snapshotProblems, SnapshotProblemLabel.Skipped)
      case StorageEvent.SnapshotRestored(_, _) => metrics.inc(snapshotsRestored)
      case StorageEvent.SnapshotUnbound(_, _) =>
        metrics.inc(snapshotProblems, SnapshotProblemLabel.Unbound)
      case StorageEvent.SnapshotWritten(_, _, bytes, millis) =>
        metrics.inc(snapshotsWritten) *>
          metrics.set(snapshotWriteSeconds, millis / 1000.0) *>
          metrics.set(snapshotBytes, bytes.toDouble)
      case StorageEvent.SnapshotFailed(_) =>
        metrics.inc(snapshotProblems, SnapshotProblemLabel.Failed)
      case StorageEvent.SnapshotRefused(_, _, _) =>
        metrics.inc(snapshotProblems, SnapshotProblemLabel.Refused)
      case StorageEvent.ReplayStarted(_, target) =>
        phase(PhaseLabel.Replaying) *> target.traverse_(t =>
          metrics.set(replayTargetBlock, number(t.blockNo)) *>
            metrics.set(replayTargetSlot, number(t.slot))
        )
      case StorageEvent.ReplayProgress(at, replayed, millis) =>
        replayReached(Some(at), replayed, millis)
      case StorageEvent.ReplayFinished(at, replayed, millis) =>
        replayReached(at, replayed, millis)
      case StorageEvent.NetworkRecorded(_, _) | StorageEvent.VolatileOpened(_) |
          StorageEvent.VolatileResumed(_, _, _) | StorageEvent.ShutdownSnapshot(_, _) =>
        Sync[F].unit

  val connection: ConnectionEvents[F] = new ConnectionEvents[F]:
    def emit(event: ConnectionEvent): F[Unit] =
      def count(label: ConnectionEventLabel) = metrics.inc(connectionEvents, label)
      event match
        case ConnectionEvent.Opened(_, _)     => count(ConnectionEventLabel.Opened)
        case ConnectionEvent.Changed(_, _, _) => count(ConnectionEventLabel.Changed)
        case ConnectionEvent.Refused(_)       => count(ConnectionEventLabel.Refused)
        case ConnectionEvent.Closed(_, _, _)  => count(ConnectionEventLabel.Closed)
        case ConnectionEvent.AcceptRateLimited(_, _) | ConnectionEvent.AcceptHardLimit(_) |
            ConnectionEvent.AcceptResumed(_) =>
          Sync[F].unit

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
        case InboundEvent.SessionReturned(_, _)        => InboundEventLabel.SessionReturned
        case InboundEvent.CachedBlockRejected(_, _, _) => InboundEventLabel.CachedBlockRejected
        case InboundEvent.Closed(_)                    => InboundEventLabel.Closed
        case InboundEvent.Refused(_)                   => InboundEventLabel.Refused
    )

  /** What the peer governor tells: its decisions as counts, its counters as the gauges of the
    * reference, the outbound connections state.
    */
  def peerSelection(out: Output[PeerNetwork.Addr]): F[Unit] = out match
    case Output.Counted(c) =>
      peerSelectionGauges.traverse_((gauge, of) => metrics.set(gauge, of(c).toDouble)) *>
        c.byOrigin.toList.traverse_((origin, n) => metrics.set(peerOrigins, origin, n.toDouble))
    case Output.LedgerPeers(trace) =>
      trace match
        case LedgerPeersTrace.Refreshed(_, ordinary, big, _) =>
          metrics.set(ledgerPeerPools, LedgerMapLabel.Ordinary, ordinary.toDouble) *>
            metrics.set(ledgerPeerPools, LedgerMapLabel.Big, big.toDouble)
        case _ => Sync[F].unit
    case Output.Connections(state) =>
      val trusted = state == OutboundConnectionsState.TrustedStateWithExternalPeers
      metrics.set(outboundTrusted, flag(trusted))
    case Output.Churned(trace) =>
      trace match
        case Churn.Trace.Mode(_) => metrics.inc(churnRounds)
        case Churn.Trace.Action(step, _, _) =>
          metrics.inc(churnSteps, (step, ChurnResultLabel.Reached))
        case Churn.Trace.Timeout(step, _, _) =>
          metrics.inc(churnSteps, (step, ChurnResultLabel.TimedOut))
        case Churn.Trace.Wait(delay) => metrics.set(churnWaitSeconds, delay.toMillis / 1000.0)
    case Output.Traced(trace) =>
      def count(label: PeerTransitionLabel, by: Int = 1): F[Unit] =
        metrics.inc(peerTransitions, label, by.toDouble).whenA(by > 0)
      trace match
        case Trace.TargetsChanged(t) =>
          metrics.set(peerTargets, TargetLabel.Root, t.root.toDouble) *>
            metrics.set(peerTargets, TargetLabel.Known, t.known.toDouble) *>
            metrics.set(peerTargets, TargetLabel.Established, t.established.toDouble) *>
            metrics.set(peerTargets, TargetLabel.Active, t.active.toDouble) *>
            metrics.set(peerTargets, TargetLabel.KnownBigLedger, t.knownBig.toDouble) *>
            metrics.set(peerTargets, TargetLabel.EstablishedBigLedger, t.establishedBig.toDouble) *>
            metrics.set(peerTargets, TargetLabel.ActiveBigLedger, t.activeBig.toDouble)
        case Trace.PromoteDone(Temperature.Warm, _, _, _) => count(PeerTransitionLabel.PromotedWarm)
        case Trace.PromoteDone(_, _, _, _)                => count(PeerTransitionLabel.PromotedHot)
        case Trace.DemoteDone(Temperature.Hot, _, _)      => count(PeerTransitionLabel.DemotedHot)
        case Trace.DemoteDone(_, _, _)                    => count(PeerTransitionLabel.DemotedCold)
        case Trace.PromoteFailed(_, _, _, retryIn, _) =>
          count(PeerTransitionLabel.PromoteFailed) *>
            retryIn.traverse_(d => metrics.observe(peerBackoffSeconds, d.toMillis / 1000.0))
        case Trace.DemoteFailed(_, _, _) => count(PeerTransitionLabel.DemoteFailed)
        case Trace.DemoteAsynchronous(_, _, failure, _) =>
          count(
            if failure.isDefined then PeerTransitionLabel.Lost else PeerTransitionLabel.DemotedHot
          )
        case Trace.ForgetCold(_, _, peers)      => count(PeerTransitionLabel.Forgotten, peers.size)
        case Trace.Forgotten(peers)             => count(PeerTransitionLabel.Forgotten, peers.size)
        case Trace.ForgetBigLedger(_, _, peers) => count(PeerTransitionLabel.Forgotten, peers.size)
        case Trace.JudgementChanged(j) =>
          metrics.set(peerSensitive, flag(j == LedgerStateJudgement.TooOld))
        case _ => Sync[F].unit

  /** A step of the Genesis State Machine: which state it is in now. */
  def gsm(phase: Gsm.Phase): F[Unit] =
    GsmStateLabel.values.toList.traverse_(label =>
      metrics.set(gsmState, label, if label.ordinal == phase.ordinal then 1.0 else 0.0)
    )

  def fetchMode(mode: FetchMode): F[Unit] =
    FetchModeLabel.values.toList.traverse_(label =>
      metrics.set(fetchModeGauge, label, if label.ordinal == mode.ordinal then 1.0 else 0.0)
    )

  /** What a peer session measured of its peer. */
  def peer(observation: Peer.Observation): F[Unit] = observation match
    case Peer.Observation.Round(_, received, millis) =>
      metrics.inc(chainSyncRounds) *>
        metrics.inc(chainSyncHeaders, (), received.toDouble) *>
        metrics.observe(chainSyncRoundSeconds, millis / 1000.0)
    case Peer.Observation.HeaderReceived => metrics.inc(chainSyncHeaders)
    case Peer.Observation.Fetched(millis) =>
      metrics.observe(blockFetchSeconds, millis / 1000.0)
    case Peer.Observation.Announced(_) => Sync[F].unit
    case Peer.Observation.HeadersArrived(_, _) | Peer.Observation.BlocksArrived(_, _) =>
      Sync[F].unit

  /** The stall watchdog's report ([[xyz.kd5ujc.scarab.node.StallWatchdog]]). */
  def stall(change: StallWatchdog.Change): F[Unit] = change match
    case StallWatchdog.Change.Began(_, _, _) =>
      metrics.inc(followerStalls) *> metrics.set(followerStalled, 1.0)
    case StallWatchdog.Change.Ended            => metrics.set(followerStalled, 0.0)
    case StallWatchdog.Change.ExitDue(_, _, _) => Sync[F].unit
    case StallWatchdog.Change.Behind(_)        => Sync[F].unit

  /** What a batch of headers added to the header-first counts (the sessions count from zero each,
    * so the totals are the sums of the additions).
    */
  def headers(added: HeaderGate.Counts): F[Unit] =
    metrics.inc(headersValidated, (), added.validated.toDouble) *>
      metrics.inc(headersUnchecked, (), added.unchecked.toDouble) *>
      metrics.inc(headersWaited, (), added.waited.toDouble)

object EventMetrics:
  def create[F[_]: Sync](using Metrics[F]): F[EventMetrics[F]] =
    (Ref.of[F, Long](0L), Ref.of[F, Option[BigInt]](None))
      .mapN(new EventMetrics(_, _))
      .flatTap(_.phase(PhaseLabel.Restoring))
