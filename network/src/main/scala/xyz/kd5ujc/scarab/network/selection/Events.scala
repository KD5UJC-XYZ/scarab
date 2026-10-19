// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.*

/** The monitors' and the jobs' side of the governor: what a look at a connection and what the end
  * of a job do to the state (`Monitor.connections`, `Governor/Monitor.hs` 111-320, and the
  * completions of the jobs in `EstablishedPeers.hs`, `ActivePeers.hs` and `RootPeers.hs`).
  */
private[selection] object Events:

  // ------------------------------------------------------------------ failures

  /** What reporting a failure did to a peer. */
  private final case class Reported[A](
      state: GovernorState[A],
      retryIn: Option[FiniteDuration],
      forgotten: Boolean
  )

  /** `KnownPeers.reportFailures` (`State/KnownPeers.hs` 452-520) with the policy of the owner's
    * decision 8. A transient failure counts against the peer and sets the time of the next attempt
    * to `now + delayFor(failCount)`; a peer that already failed `maxConnectionRetries` times is
    * forgotten unless it is unforgettable (a local root or a bootstrap peer). The reference tests
    * the count before the increment, so the sixth failure forgets a peer, and so do we. A peer that
    * misbehaved is forgotten; an unforgettable one is kept, with the longest backoff.
    */
  private def report[A](
      st: GovernorState[A],
      peer: A,
      now: Time,
      failure: FailureClass,
      policy: Policy,
      delayFor: Int => FiniteDuration
  ): Reported[A] =
    st.peers.get(peer) match
      case None => Reported(st, None, false)
      case Some(r) =>
        val unforgettable = r.origin.unforgettable
        val count = r.failCount + 1
        val forget = failure match
          case FailureClass.Misbehaved => !unforgettable
          case FailureClass.Transient =>
            r.failCount >= policy.maxConnectionRetries && !unforgettable
        if forget then Reported(st.copy(peers = st.peers - peer), None, true)
        else
          val delay = failure match
            case FailureClass.Misbehaved =>
              (policy.baseColdRetry * (1L << policy.maxColdRetryBackoff.toLong))
            case FailureClass.Transient => delayFor(count)
          val moved = r.copy(
            status = Status.Cold(Some(now + delay)),
            failCount = count,
            clearFailAt = None
          )
          Reported(st.copy(peers = st.peers.updated(peer, moved)), Some(delay), false)

  /** The uniform fuzz of a delay, in whole milliseconds, drawn from the state's generator. */
  private def fuzz[A](
      st: GovernorState[A],
      low: FiniteDuration,
      high: FiniteDuration
  ): (FiniteDuration, GovernorState[A]) =
    val (millis, next) = st.rng.between(low.toMillis, high.toMillis)
    (millis.millis, st.copy(rng = next))

  // ------------------------------------------------------------------ connections

  def observed[A](
      st0: GovernorState[A],
      peer: A,
      observation: Observation,
      now: Time,
      policy: Policy
  )(using Ordering[A]): Decision[A] =
    st0.peers.get(peer) match
      case None => Decision(st0, Nil, Nil)
      case Some(record) =>
        observation match
          case Observation.CachedBlockRejected(reason) =>
            val result = report(st0, peer, now, FailureClass.Misbehaved, policy, _ => Duration.Zero)
            Decision(
              result.state,
              List(Job.Close(peer)),
              Trace.DemoteAsynchronous(
                peer,
                Temperature.Cold,
                Some(FailureClass.Misbehaved),
                Some(reason)
              ) ::
                (if result.forgotten then List(Trace.Forgotten(List(peer))) else Nil)
            )
          // a hot protocol ended and the connection is up: warm again
          case Observation.HotEnded(delay) if record.status == Status.Hot =>
            val (extra, st) = fuzz(st0, policy.warmReconnectFuzz._1, policy.warmReconnectFuzz._2)
            val readyAt = delay.fold(now)(d => now + (d + extra).max(Duration.Zero))
            Decision(
              st.updated(peer)(_.copy(status = Status.Warm(readyAt))),
              Nil,
              List(Trace.DemoteAsynchronous(peer, Temperature.Warm, None, None))
            )
          // the connection is going: it lingers until the connection manager releases it
          case Observation.Lost(failure, _, false, reason) =>
            record.status match
              case Status.Warm(_) | Status.Hot | Status.Activating =>
                Decision(
                  st0.withStatus(peer, Status.Cooling),
                  Nil,
                  List(
                    Trace.DemoteAsynchronous(peer, Temperature.Cold, Some(failure), Some(reason))
                  )
                )
              case _ => Decision(st0, Nil, Nil)
          // the connection is gone: back to cold, and the failure counts
          case Observation.Lost(failure, delay, true, reason) =>
            record.status match
              case Status.Warm(_) | Status.Hot | Status.Activating | Status.Cooling =>
                val (extra, st) =
                  fuzz(st0, policy.coldReconnectFuzz._1, policy.coldReconnectFuzz._2)
                val wait = (delay + extra).max(Duration.Zero)
                val result = report(st, peer, now, failure, policy, _ => wait)
                Decision(
                  result.state,
                  Nil,
                  Trace.DemoteAsynchronous(peer, Temperature.Cold, Some(failure), Some(reason)) ::
                    (if result.forgotten then List(Trace.Forgotten(List(peer))) else Nil)
                )
              // the job that is working on the peer reports how it ends
              case _ => Decision(st0, Nil, Nil)
          case _ => Decision(st0, Nil, Nil)

  // ------------------------------------------------------------------ jobs

  def completed[A](
      st0: GovernorState[A],
      outcome: Outcome[A],
      now: Time,
      policy: Policy
  )(using Ordering[A]): Decision[A] =
    def status(peer: A): Option[Status] = st0.peers.get(peer).map(_.status)
    // the count of the population the peer is in: ordinary, or big ledger peers
    def big(st: GovernorState[A], peer: A): Boolean = st.peers.get(peer).exists(_.origin.big)
    def established(st: GovernorState[A], peer: A): Int =
      val v = st.view(now)
      if big(st, peer) then v.big.established.size else v.established.size
    def active(st: GovernorState[A], peer: A): Int =
      val v = st.view(now)
      if big(st, peer) then v.big.active.size else v.active.size

    outcome match
      case Outcome.Shared(batch, results) => PeerShare.completed(st0, batch, results, now, policy)
      case Outcome.Connected(peer, generation)
          if generation == st0.rootsGeneration && status(peer).contains(Status.Connecting) =>
        val st = st0.updated(peer)(r =>
          r.copy(
            status = Status.Warm(now),
            everConnected = true,
            tepid = false,
            shareAt = Option.when(r.sharing.contains(true))(now + policy.peerShareActivationDelay)
          )
        )
        Decision(
          st,
          Nil,
          List(
            Trace.PromoteDone(
              Temperature.Warm,
              peer,
              if big(st, peer) then st.targets.establishedBig else st.targets.established,
              established(st, peer)
            )
          )
        )

      case Outcome.ConnectFailed(peer, failure, generation)
          if generation == st0.rootsGeneration && status(peer).contains(Status.Connecting) =>
        val (extra, st) = fuzz(st0, -2.seconds, 2.seconds)
        val backoff = (count: Int) =>
          extra + policy.baseColdRetry * (1L << (count - 1).min(policy.maxColdRetryBackoff).toLong)
        val result = report(st, peer, now, failure, policy, backoff)
        Decision(
          result.state,
          Nil,
          Trace.PromoteFailed(Temperature.Cold, peer, failure, result.retryIn, result.forgotten) ::
            (if result.forgotten then List(Trace.Forgotten(List(peer))) else Nil)
        )

      case Outcome.Activated(peer) if status(peer).contains(Status.Activating) =>
        val st = st0.updated(peer)(
          _.copy(status = Status.Hot, clearFailAt = Some(now + policy.clearFailCountDelay))
        )
        Decision(
          st,
          Nil,
          List(
            Trace.PromoteDone(
              Temperature.Hot,
              peer,
              if big(st, peer) then st.targets.activeBig else st.targets.active,
              active(st, peer)
            )
          )
        )

      case Outcome.ActivateFailed(peer, failure) if status(peer).contains(Status.Activating) =>
        // the peer leaves the established set: cold, after the repromote delay
        val (extra, st) = fuzz(st0, -2.seconds, 2.seconds)
        val result =
          report(st, peer, now, failure, policy, _ => policy.repromoteErrorDelay + extra)
        Decision(
          result.state,
          Nil,
          Trace.PromoteFailed(Temperature.Warm, peer, failure, result.retryIn, result.forgotten) ::
            (if result.forgotten then List(Trace.Forgotten(List(peer))) else Nil)
        )

      case Outcome.Deactivated(peer) if status(peer).contains(Status.Deactivating) =>
        val st = st0.updated(peer)(_.copy(status = Status.Warm(now), tepid = true))
        Decision(st, Nil, List(Trace.DemoteDone(Temperature.Hot, peer, active(st, peer))))

      case Outcome.DeactivateFailed(peer) if status(peer).contains(Status.Deactivating) =>
        // the connection was closed: the peer is cold again, available at once, and tepid
        val st = st0.updated(peer)(_.copy(status = Status.Cold(None), tepid = true))
        Decision(st, Nil, List(Trace.DemoteFailed(Temperature.Hot, peer, timedOut = false)))

      case Outcome.Closed(peer) if status(peer).contains(Status.Closing) =>
        val st = st0.updated(peer)(_.copy(status = Status.Cold(None)))
        Decision(st, Nil, List(Trace.DemoteDone(Temperature.Warm, peer, established(st, peer))))

      case Outcome.CloseTimedOut(peer)
          if status(peer).exists(s => s == Status.Closing || s == Status.Deactivating) =>
        // the connection manager has not released it: it lingers, and cannot be promoted or demoted
        val from =
          if status(peer).contains(Status.Closing) then Temperature.Warm else Temperature.Hot
        Decision(
          st0.withStatus(peer, Status.Cooling),
          Nil,
          List(Trace.DemoteFailed(from, peer, timedOut = true))
        )

      case Outcome.RootsFound(found, ttl, generation)
          if st0.publicRootsInFlight && generation == st0.rootsGeneration =>
        val local = st0.localRoots.keys
        val offered = found.ordinary ++ found.bootstrap ++ found.ledger
        // new peers only: not a local root, and not a public root of any kind already (the reference
        // takes them away from the answer, `RootPeers.hs` 163-168), so a big ledger peer stays one
        val fresh = offered.filterNot(p => local(p) || st0.peers.get(p).exists(_.origin.root))
        val peers = fresh.foldLeft(st0.peers) { (m, p) =>
          val origin =
            if found.bootstrap(p) then Origin.Bootstrap
            else if found.ledger(p) then Origin.Ledger
            else Origin.Public
          m.get(p) match
            case Some(r) =>
              m.updated(
                p,
                r.copy(origin = origin, advertise = found.advertise.get(p).orElse(r.advertise))
              )
            case None =>
              m.updated(p, PeerRecord.cold(origin).copy(advertise = found.advertise.get(p)))
        }
        val backoffs = if fresh.isEmpty then st0.publicRootBackoffs.max(0) + 1 else 0
        val retryIn =
          if backoffs == 0 then ttl
          else (1L << backoffs.min(policy.maxPublicRootBackoff).toLong).seconds
        Decision(
          st0.copy(
            peers = peers,
            publicRootBackoffs = backoffs,
            publicRootRetryAt = now + retryIn,
            publicRootsInFlight = false
          ),
          Nil,
          List(Trace.RootsResults(fresh, backoffs, retryIn))
        )

      case Outcome.RootsFailed(generation)
          if st0.publicRootsInFlight && generation == st0.rootsGeneration =>
        // one step in the failure direction (negative), an exponential delay of up to 2^8 seconds
        val backoffs = st0.publicRootBackoffs.min(0) - 1
        val retryIn = (1L << math.abs(backoffs).min(policy.maxPublicRootBackoff).toLong).seconds
        Decision(
          st0.copy(
            publicRootBackoffs = backoffs,
            publicRootRetryAt = now + retryIn,
            publicRootsInFlight = false
          ),
          Nil,
          List(Trace.RootsFailure(backoffs, retryIn))
        )

      case Outcome.BigLedgerFound(found, ttl, generation)
          if st0.bigLedgerInFlight && generation == st0.rootsGeneration =>
        // as the public roots, but into the big ledger population; a peer that is a local root or
        // a root of any kind already is left as it is (`BigLedgerPeers.hs` 138-147)
        val local = st0.localRoots.keys
        val fresh = found.filterNot(p => local(p) || st0.peers.get(p).exists(_.origin.root))
        val peers = fresh.foldLeft(st0.peers) { (m, p) =>
          m.get(p) match
            case Some(r) => m.updated(p, r.copy(origin = Origin.BigLedger))
            case None    => m.updated(p, PeerRecord.cold(Origin.BigLedger))
        }
        val backoffs = if fresh.isEmpty then st0.bigLedgerBackoffs.max(0) + 1 else 0
        val retryIn =
          if backoffs == 0 then ttl
          else (1L << backoffs.min(policy.maxPublicRootBackoff).toLong).seconds
        Decision(
          st0.copy(
            peers = peers,
            bigLedgerBackoffs = backoffs,
            bigLedgerRetryAt = now + retryIn,
            bigLedgerInFlight = false
          ),
          Nil,
          List(Trace.BigLedgerResults(fresh, backoffs, retryIn))
        )

      case Outcome.BigLedgerFailed(generation)
          if st0.bigLedgerInFlight && generation == st0.rootsGeneration =>
        val backoffs = st0.bigLedgerBackoffs.min(0) - 1
        val retryIn = (1L << math.abs(backoffs).min(policy.maxPublicRootBackoff).toLong).seconds
        Decision(
          st0.copy(
            bigLedgerBackoffs = backoffs,
            bigLedgerRetryAt = now + retryIn,
            bigLedgerInFlight = false
          ),
          Nil,
          List(Trace.BigLedgerFailure(backoffs, retryIn))
        )

      // A late rejection can forget a connecting source before its bearer is registered. Close
      // its subsequently successful job too; the earlier Close may have found nothing yet.
      case connected @ Outcome.Connected(peer, generation) if status(peer).forall {
            case Status.Cold(_) => true
            case _              => false
          } =>
        Decision(st0, List(Job.Close(peer, Some(generation))), aborted(connected))

      // the world moved on while the job ran: nothing to apply
      case other => Decision(st0, Nil, aborted(other))

  private def aborted[A](outcome: Outcome[A]): List[Trace[A]] =
    outcome match
      case Outcome.Connected(p, _)        => List(Trace.PromoteAborted(Temperature.Cold, p))
      case Outcome.ConnectFailed(p, _, _) => List(Trace.PromoteAborted(Temperature.Cold, p))
      case Outcome.Activated(p)           => List(Trace.PromoteAborted(Temperature.Warm, p))
      case Outcome.ActivateFailed(p, _)   => List(Trace.PromoteAborted(Temperature.Warm, p))
      case _                              => Nil
