// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.network.genesis.PicosClock
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.selection.{
  BlockFetchPolicy,
  FetchGsv,
  FetchMode,
  GenesisBlockFetch,
  Gsm
}

/** One decision/reservation transaction for every inbound and outbound fetching session. The
  * decision is pure; a reservation is committed before its owner is woken. In particular, the
  * single-block recovery path cannot bypass the reference's peer and outstanding-work policy.
  */
final class FetchCoordinator[F[_]: Async] private (
    state: Ref[F, FetchCoordinator.State[F]],
    config: BlockFetchPolicy.Config,
    selection: Option[F[ChainSelection.Local]],
    fetched: Option[F[Set[Bytes]]],
    salt: Long,
    genesis: Ref[F, Option[FetchCoordinator.GenesisControl[F]]],
    decisions: Mutex[F],
    holder: Ref[F, Option[FetchCoordinator.Holding]],
    waits: Ref[F, Map[Long, FetchCoordinator.Waiting]]
):
  import FetchCoordinator.*
  private val F = Async[F]

  def setMode(mode: FetchMode): F[Unit] = change(_.copy(mode = mode))

  /** Installed once by the Genesis governor; phase and starvation are live observations. */
  def installGenesis(control: GenesisControl[F]): F[Unit] =
    locked("installGenesis")(genesis.set(Some(control)) *> changeLocked(identity))
  def refreshGenesis: F[Unit] = change(identity)

  /** Publish a GSM transition and its decision as one transaction. A previously sampled Genesis
    * decision cannot demote the dynamo after this transition has completed.
    */
  def transitionGenesis(update: F[Unit]): F[Unit] =
    locked("transitionGenesis")(F.uncancelable(_ => update *> changeLocked(identity)))

  /** State.hs:100–107,150: grace expiry retriggers decisions in both modes. Node/Genesis.hs:114
    * configures zero grace without Genesis, so the Praos loop instead runs at its 10ms rate limit.
    * With Genesis enabled, the configured grace also applies while the GSM is caught up.
    */
  private[node] def watchDecisions(
      gracePicos: BigInt,
      clock: Option[PicosClock[F]] = None,
      intervals: DecisionIntervals = DecisionIntervals.default
  ): F[Nothing] =
    val timer = clock.getOrElse(PicosClock.system[F])
    type Point = xyz.kd5ujc.scarab.protocol.ChainSync.Point
    type Fingerprint = (Option[Point], Map[Long, (Option[Point], Boolean)], Boolean)
    def fingerprint(s: State[F]): Fingerprint =
      (
        s.selected.map(l => l.blocks.lastOption.fold(l.anchor)(_.point)),
        s.peers.map((id, peer) =>
          id ->
            (peer.candidate.map(_.tip).orElse(peer.tip.map(_.point)), peer.busy)
        ),
        s.genesisContext.exists(_.phase == Gsm.Phase.Syncing)
      )
    F.tailRecM[(Option[Fingerprint], BigInt), Nothing]((None, 0)) { (previous, deadline) =>
      (state.get, timer.now).tupled.flatMap { (current, now) =>
        val input = fingerprint(current)
        val interval =
          if current.genesisContext.exists(_.phase == Gsm.Phase.Syncing) then intervals.genesisPicos
          else intervals.praosPicos
        val expires = if previous.contains(input) then deadline else now + gracePicos.max(interval)
        val remaining = (expires - now).max(0)
        F.race(current.signal.get, timer.sleep(remaining))
          .flatMap {
            case Left(_)  => F.pure(Left((Some(input), expires)))
            case Right(_) => refreshGenesis.as(Left((None, 0)))
          }
      }
    }

  /** What each session holds, read from the state without the decision lock: a stall report must
    * not wait behind the very transaction that may be stuck. Each session's own pipeline probe (set
    * by [[Session.observed]]) is read after the state, outside it.
    */
  private[node] def diagnostics: F[Vector[SessionDiagnostic]] =
    (state.get, waits.get, F.monotonic).tupled.flatMap { (s, waiting, now) =>
      s.peers.toVector.sortBy(_._1).traverse { (id, peer) =>
        peer.probe.sequence.map(pipeline =>
          SessionDiagnostic(
            session = id,
            busy = peer.busy,
            pendingOffers = peer.pending.size,
            pendingHeaders = peer.pending.map(_.headers.size).sum,
            active = peer.active.toVector.sortBy(_._1).map { (token, a) =>
              ActiveFetch(token, a.requests, a.remaining.size)
            },
            candidateHeaders = peer.candidate.map(_.headers.size),
            pipeline = pipeline,
            reserving = waiting.get(id).map(w => Reserving(w.phase, now - w.at))
          )
        )
      }
    }

  /** Who holds the decision lock and for how long, if anyone does; a plain read, without the lock.
    */
  private[node] def lockHolder: F[Option[LockHolder]] =
    (holder.get, F.monotonic).mapN((held, now) => held.map(h => LockHolder(h.who, now - h.at)))

  private def observe(id: Long, probe: F[Peer.PipelineState]): F[Unit] =
    state.update(s => s.copy(peers = s.peers.updatedWith(id)(_.map(_.copy(probe = Some(probe))))))

  private[node] def inFlight: F[Map[Long, BlockFetchPolicy.InFlight]] = state.get.map { s =>
    s.peers.map { (id, p) =>
      val active = p.active.values.toVector
      val headers = active.flatMap(_.remaining)
      id -> BlockFetchPolicy.InFlight(
        active.map(_.requests).sum,
        headers.map(h => sizeOf(h, s)).sum,
        headers.map(_.hash).toSet
      )
    }
  }

  def session(identity: Long): Resource[F, Session[F]] =
    Resource
      .make(
        state.modify { s =>
          val id = s.next
          (
            s.copy(
              next = id + 1,
              peers =
                s.peers.updated(id, PeerState[F](identity ^ salt, pending = Vector.empty[Offer[F]]))
            ),
            id
          )
        }
      )(id => change(s => s.copy(peers = s.peers - id)))
      .map(id => new Session(this, id))

  private def change(f: State[F] => State[F]): F[Unit] =
    locked("change")(changeLocked(f))

  /** `fa` under the decision lock, noting who holds it and since when: one clock read and one `Ref`
    * write on entry and one on exit, and no lock of its own, so that a stall report can say what
    * the lock is held by without taking it.
    */
  private def locked[A](who: String)(fa: F[A]): F[A] =
    decisions.lock.surround(
      F.monotonic.flatMap(at => holder.set(Some(Holding(who, at)))) *>
        F.guarantee(fa, holder.set(None))
    )

  /** Note that session `id` is in `phase` of a reservation from now on. */
  private def mark(id: Long, phase: Phase): F[Unit] =
    F.monotonic.flatMap(at => waits.update(_.updated(id, Waiting(phase, at))))

  private def unmark(id: Long): F[Unit] = waits.update(_ - id)

  private def changeLocked(f: State[F] => State[F]): F[Unit] =
    F.uncancelable { poll =>
      poll(
        (
          Deferred[F, Unit],
          selection.sequence,
          fetched.sequence,
          genesis.get.flatMap(_.traverse(c => c.context.map(c -> _)))
        ).tupled
      ).flatMap { (signal, local, cached, control) =>
        state
          .modify { before =>
            val input = f(before).copy(
              signal = signal,
              selected = local,
              fetched = cached.getOrElse(Set.empty),
              genesisContext = control.map(_._2),
              demotions = Vector.empty
            )
            val (after, grants) = dispatch(input)
            // Time advances on idle retries without changing the work a waiter can observe.
            // Keep the same signal in that case; completing it wakes every fetching session.
            val changed = before.peers != after.peers || before.mode != after.mode ||
              before.selected != after.selected || before.fetched != after.fetched ||
              before.genesisContext.map(_.phase) != after.genesisContext.map(_.phase) ||
              before.genesisState != after.genesisState
            val committed = if changed then after else after.copy(signal = before.signal)
            (committed, (Option.when(changed)(before.signal), grants, after.demotions))
          }
          .flatMap { (previous, grants, demotions) =>
            grants.traverse_((promise, allocation) => promise.complete(allocation).void) *>
              previous.traverse_(_.complete(()).void) *>
              control.traverse_((owner, _) => demotions.traverse_(owner.demoteDynamo))
          }
      }
    }

  private def await(hash: Bytes): F[Unit] =
    F.tailRecM(()) { _ =>
      state.get.flatMap { s =>
        fetched.sequence.flatMap { cached =>
          val claimed =
            s.peers.valuesIterator.exists(p => p.active.values.exists(_.claimed(hash)))
          if cached.exists(_(hash)) || !claimed then F.pure(Right(()))
          else s.signal.get.as(Left(()))
        }
      }
    }

  /** GSV estimates are non-trigger state (native State.hs:419–425). Recording them must not
    * retrigger a full decision or restart the grace wait; the next real trigger reads them.
    */
  private def updatePerformance(id: Long, performance: FetchGsv.PeerGsv): F[Unit] =
    state.update(s =>
      s.copy(peers = s.peers.updatedWith(id)(_.map { peer =>
        if performance.sampleTimePicos < peer.performance.sampleTimePicos then peer
        else peer.copy(performance = performance)
      }))
    )

  private def announce(
      id: Long,
      tip: Option[Header],
      performance: FetchGsv.PeerGsv,
      hints: Map[Bytes, Long],
      candidate: Option[ChainSelection.Candidate]
  ): F[Unit] =
    change { s =>
      val added = hints.map((hash, size) => hash -> (size -> s.next))
      val live = s.peers.valuesIterator
        .flatMap(p =>
          p.active.valuesIterator.flatMap(_.claimed) ++ p.pending.iterator
            .flatMap(_.headers.map(_.hash)) ++ p.candidate.iterator.flatMap(_.headers.map(_.hash))
        )
        .toSet
      val combined = s.hints ++ added
      val retained = combined.filter((hash, _) => live(hash)) ++
        combined.toVector.sortBy(_._2._2).takeRight(2 * BlockPool.Retained).toMap
      s.copy(
        next = s.next + 1,
        hints = retained,
        peers = s.peers.updatedWith(id)(
          _.map(peer =>
            peer.copy(
              tip = tip,
              performance =
                if performance.sampleTimePicos < peer.performance.sampleTimePicos then
                  peer.performance
                else performance,
              candidate = candidate
            )
          )
        )
      )
    }

  private def reserve(
      id: Long,
      headers: Vector[Header],
      mode: FetchMode,
      candidate: Option[ChainSelection.Candidate]
  ): Resource[F, Allocation] =
    Resource
      .make(
        mark(id, Phase.LockWait) *> locked("reserve")(Deferred[F, Allocation].flatMap { result =>
          mark(id, Phase.LockHeld) *>
            state
              .modify { s =>
                val token = s.next
                val peer = s.peers.get(id)
                val next = s.copy(
                  next = token + 1,
                  peers = s.peers.updatedWith(id)(
                    _.map(p =>
                      p.copy(pending = p.pending :+ Offer(token, headers, result, candidate))
                    )
                  )
                )
                (next, (token, peer.isDefined))
              }
              .flatMap { (token, live) =>
                (if live then changeLocked(_.copy(mode = mode))
                 else result.complete(Allocation.empty).void)
                  .as(token -> result)
              }
        })
      ) { (token, _) =>
        unmark(id) *> change(s =>
          s.copy(peers =
            s.peers.updatedWith(id)(
              _.map(p =>
                p.copy(pending = p.pending.filterNot(_.token == token), active = p.active - token)
              )
            )
          )
        )
      }
      .evalMap((_, result) => mark(id, Phase.GrantWait) *> F.guarantee(result.get, unmark(id)))

  private def received(id: Long, token: Long, hash: Bytes): F[Unit] =
    change(s =>
      s.copy(peers = s.peers.updatedWith(id)(_.map { p =>
        val active = p.active.updatedWith(token)(
          _.map(a => a.copy(remaining = a.remaining.filterNot(_.hash == hash)))
        )
        val bytes = active.valuesIterator.flatMap(_.remaining).map(h => sizeOf(h, s)).sum
        p.copy(active = active, busy = p.busy && bytes > p.performance.window.lowBytes)
      }))
    )

  private def reconcile(id: Long, token: Long, runs: Vector[Vector[Header]]): F[Unit] =
    change(s =>
      s.copy(peers =
        s.peers.updatedWith(id)(
          _.map(p =>
            p.copy(active =
              p.active.updatedWith(token)(
                _.map(_ => Active(runs.flatten, runs.size, runs.flatten.map(_.hash).toSet))
              )
            )
          )
        )
      )
    )

  private def completed(id: Long, token: Long): F[Unit] =
    change(s =>
      s.copy(peers =
        s.peers.updatedWith(id)(
          _.map(p =>
            p.copy(active =
              p.active.updatedWith(token)(_.map(a => a.copy(requests = (a.requests - 1).max(0))))
            )
          )
        )
      )
    )

  /** A finite transaction can admit several peers, but never waits or performs wire effects. */
  private def dispatch(s: State[F]): (State[F], Vector[(Deferred[F, Allocation], Allocation)]) =
    def chainOf(peer: PeerState[F]): Option[ChainSelection.Candidate] =
      peer.pending.headOption.flatMap(_.candidate).orElse(peer.candidate)
    def tipOf(peer: PeerState[F]): Option[Header] =
      chainOf(peer).flatMap(_.headers.lastOption).orElse(peer.tip)
    val tips = s.peers.valuesIterator.flatMap(tipOf).toVector
    val ordered = CandidateSelection.sort(tips)(compareTips)
    def preference(tip: Option[Header]): Int = tip.fold(0) { h =>
      ordered.indexWhere(other => compareTips(h, other) == 0).max(0)
    }
    val useGenesis = s.genesisContext.exists(_.phase != Gsm.Phase.CaughtUp)
    val candidates = s.peers.toVector.sortBy(_._1).map { (id, peer) =>
      val flight = peer.active.values.toVector
      val remaining = flight.flatMap(_.remaining)
      val admitted = for
        _ <- Option.when(useGenesis || peer.pending.nonEmpty)(())
        local <- s.selected
        chain <- chainOf(peer)
      yield FetchCandidates.select(
        ChainSelection.tiebreakerFor(tipOf(peer).fold(xyz.kd5ujc.scarab.protocol.Era.Byron)(_.era)),
        local,
        chain,
        chain.headers.filter(h => s.fetched(h.hash)).map(_.point).toSet
      )
      val fullSuffix = for
        _ <- Option.when(useGenesis)(())
        local <- s.selected
        chain <- chainOf(peer)
      yield
        val restriction = ChainSelection.tiebreakerFor(
          tipOf(peer).fold(xyz.kd5ujc.scarab.protocol.Era.Byron)(_.era)
        )
        if !FetchCandidates.plausible(restriction, local, chain) then
          Left(BlockFetchPolicy.Refusal.ChainNotPlausible)
        else FetchCandidates.suffix(local, chain).map(_._2)
      val eligible = admitted.map(_.toOption.toVector.flatMap(_.headers).map(_.hash).toSet)
      val fragments =
        peer.pending.headOption.toVector.flatMap(o =>
          fragmentsOf(o.headers.filter(h => eligible.forall(_(h.hash))), h => sizeOf(h, s))
        )
      BlockFetchPolicy.Candidate(
        peer = id,
        chainPreference =
          if useGenesis || s.mode == FetchMode.Deadline then -preference(tipOf(peer))
          else preference(tipOf(peer)),
        chainHead = tipOf(peer).fold(Bytes.empty)(_.hash),
        fragments =
          if useGenesis then
            fragmentsOf(fullSuffix.toVector.flatMap(_.toOption.toVector.flatten), h => sizeOf(h, s))
          else fragments,
        performance = peer.performance,
        inFlight = BlockFetchPolicy.InFlight(
          flight.map(_.requests).sum,
          remaining.map(h => sizeOf(h, s)).sum,
          remaining.map(_.hash).toSet
        ),
        status =
          if peer.busy && remaining.map(h => sizeOf(h, s)).sum > peer.performance.window.lowBytes
          then BlockFetchPolicy.Status.Busy
          else BlockFetchPolicy.Status.Ready,
        tieBreak = peer.identity,
        chainHeadSlot = tipOf(peer).fold(BigInt(0))(_.slot),
        refusal =
          if useGenesis then fullSuffix.flatMap(_.left.toOption)
          else admitted.flatMap(_.left.toOption)
      )
    }
    val genesisResult = s.genesisContext.filter(_ => useGenesis).map { context =>
      GenesisBlockFetch.decide(
        config,
        s.genesisState,
        context.nowPicos,
        context.starvation,
        candidates,
        context.gracePicos,
        s.fetched
      )
    }
    val decisions =
      genesisResult.fold(BlockFetchPolicy.decide(config, s.mode, candidates))(_.decisions)
    val dispatched = genesisResult.fold(s)(r =>
      s.copy(genesisState = r.state, demotions = r.demoteDynamo.toVector)
    )
    val granted =
      decisions.foldLeft((dispatched, Vector.empty[(Deferred[F, Allocation], Allocation)])) {
        case ((current, grants), decision) =>
          val (id, chosen) = decision match
            case BlockFetchPolicy.Decision.Request(peer, fragments) =>
              (peer, Some(fragments))
            case BlockFetchPolicy.Decision.Declined(peer, BlockFetchPolicy.Refusal.NoBlocks) =>
              (peer, Some(Vector.empty))
            case BlockFetchPolicy.Decision
                  .Declined(peer, BlockFetchPolicy.Refusal.InFlightOtherPeer) =>
              (peer, Some(Vector.empty))
            case BlockFetchPolicy.Decision.Declined(
                  peer,
                  BlockFetchPolicy.Refusal.ChainNotPlausible |
                  BlockFetchPolicy.Refusal.IntersectionTooDeep |
                  BlockFetchPolicy.Refusal.AlreadyFetched
                ) =>
              (peer, Some(Vector.empty))
            case BlockFetchPolicy.Decision.Declined(peer, _) => (peer, None)
          (current.peers.get(id), chosen) match
            case (Some(peer), Some(selected)) if peer.pending.nonEmpty =>
              val offer = peer.pending.head
              val hashes = selected.flatMap(_.headers.map(_.hash)).toSet
              // Genesis selects the oldest missing work from the complete candidate, not merely
              // this caller's publication batch. Keeping only the offer's overlap can return an
              // empty grant with unchanged remaining work and spin without any transport progress.
              val available =
                if useGenesis then chainOf(peer).toVector.flatMap(_.headers) else offer.headers
              val headers = available.filter(h => hashes(h.hash))
              val inFlight = candidates.flatMap { c =>
                if (useGenesis || s.mode == FetchMode.Deadline) && c.peer != id then Vector.empty
                else c.inFlight.blocks.toVector
              }.toSet
              val admitted = candidates.find(_.peer == id)
              val eligible =
                admitted.toVector.flatMap(_.fragments.flatMap(_.headers.map(_.hash))).toSet
              val remaining =
                offer.headers.filter(h =>
                  eligible(h.hash) && !hashes(h.hash) && !inFlight(h.hash) && !s.fetched(h.hash)
                )
              val refusal = decision match
                case BlockFetchPolicy.Decision.Declined(_, reason) => Some(reason)
                case _                                             => None
              val allocation = Allocation(
                offer.token,
                headers,
                remaining,
                selected.size,
                refusal,
                allowOverlap = useGenesis
              )
              // Native AlreadyFetched also labels a peer which cannot serve the oldest point of
              // the preferred chain (Genesis.hs:74–75). Its own offer can still be uncached. Keep
              // that offer pending until a real decision trigger; an empty grant with the same
              // remaining headers would cause the caller to reserve forever without progress.
              if headers.isEmpty && remaining.nonEmpty && refusal.contains(
                  BlockFetchPolicy.Refusal.AlreadyFetched
                )
              then (current, grants)
              else
                val active =
                  if headers.isEmpty then peer.active
                  else
                    peer.active.updated(
                      offer.token,
                      Active(headers, selected.size, headers.map(_.hash).toSet)
                    )
                val bytes = active.valuesIterator.flatMap(_.remaining).map(h => sizeOf(h, s)).sum
                val next = peer.copy(
                  pending = peer.pending.tail,
                  active = active,
                  busy = (peer.busy && candidates
                    .find(_.peer == id)
                    .exists(_.status == BlockFetchPolicy.Status.Busy)) ||
                    bytes >= peer.performance.window.highBytes
                )
                (
                  current.copy(peers = current.peers.updated(id, next)),
                  grants :+ (offer.result -> allocation)
                )
            case _ => (current, grants)
      }

    if granted._2.exists(_._2.headers.nonEmpty) then granted
    else (granted._1.copy(genesisState = s.genesisState), granted._2)

object FetchCoordinator:
  /** Pinned diffusion Configuration.hs:95–96; native simulator policies can explicitly use zero. */
  final case class DecisionIntervals(genesisPicos: BigInt, praosPicos: BigInt)
  object DecisionIntervals:
    val default: DecisionIntervals = DecisionIntervals(40000000000L, 10000000000L)

  final case class GenesisContext(
      phase: Gsm.Phase,
      nowPicos: BigInt,
      starvation: GenesisBlockFetch.Starvation,
      gracePicos: BigInt = GenesisBlockFetch.DefaultGracePicos
  )
  final case class GenesisControl[F[_]](context: F[GenesisContext], demoteDynamo: Long => F[Unit])
  private final case class Offer[F[_]](
      token: Long,
      headers: Vector[Header],
      result: Deferred[F, Allocation],
      candidate: Option[ChainSelection.Candidate]
  )

  /** One reservation of a session that has not completed: `requests` BlockFetch requests were
    * granted and `headers` of its headers are still to arrive.
    */
  private[node] final case class ActiveFetch(token: Long, requests: Int, headers: Int)

  /** A session as a stall report sees it: what the coordinator holds for it (`busy`, the offers
    * waiting for a decision and their headers, the reservations in flight, the headers of its
    * candidate) and, for a pipelined session, its [[Peer.PipelineState]].
    */
  private[node] final case class SessionDiagnostic(
      session: Long,
      busy: Boolean,
      pendingOffers: Int,
      pendingHeaders: Int,
      active: Vector[ActiveFetch],
      candidateHeaders: Option[Int],
      pipeline: Option[Peer.PipelineState],
      reserving: Option[Reserving] = None
  )

  /** Where in a BlockFetch reservation a session is. */
  private[node] enum Phase:
    /** Waiting for the decision lock. */
    case LockWait

    /** Holding the decision lock, queueing the offer and deciding. */
    case LockHeld

    /** Offer queued; waiting for the decision that grants it. */
    case GrantWait

  /** A session's reservation is in `phase` for `running`. */
  private[node] final case class Reserving(phase: Phase, running: FiniteDuration)

  /** The decision lock is held by `by` for `running`. */
  private[node] final case class LockHolder(by: String, running: FiniteDuration)

  private[node] final case class Waiting(phase: Phase, at: FiniteDuration)
  private[node] final case class Holding(who: String, at: FiniteDuration)

  private final case class Active(remaining: Vector[Header], requests: Int, claimed: Set[Bytes])
  private final case class PeerState[F[_]](
      identity: Long,
      tip: Option[Header] = None,
      candidate: Option[ChainSelection.Candidate] = None,
      performance: FetchGsv.PeerGsv = FetchGsv.default,
      pending: Vector[Offer[F]] = Vector.empty,
      active: Map[Long, Active] = Map.empty,
      busy: Boolean = false,
      probe: Option[F[Peer.PipelineState]] = None
  )
  private final case class State[F[_]](
      signal: Deferred[F, Unit],
      next: Long,
      peers: Map[Long, PeerState[F]],
      mode: FetchMode = FetchMode.BulkSync,
      hints: Map[Bytes, (Long, Long)] = Map.empty,
      selected: Option[ChainSelection.Local] = None,
      fetched: Set[Bytes] = Set.empty,
      genesisContext: Option[GenesisContext] = None,
      genesisState: GenesisBlockFetch.State[Long] = GenesisBlockFetch.State[Long](),
      demotions: Vector[Long] = Vector.empty
  )
  private[node] final case class Allocation(
      token: Long,
      headers: Vector[Header],
      remaining: Vector[Header],
      requests: Int,
      refusal: Option[BlockFetchPolicy.Refusal] = None,
      allowOverlap: Boolean = false
  )
  private object Allocation:
    val empty: Allocation = Allocation(-1, Vector.empty, Vector.empty, 0)

  final class Session[F[_]] private[FetchCoordinator] (owner: FetchCoordinator[F], id: Long):
    def announce(
        tip: Option[Header],
        performance: FetchGsv.PeerGsv,
        hints: Map[Bytes, Long] = Map.empty,
        candidate: Option[ChainSelection.Candidate] = None
    ): F[Unit] =
      owner.announce(id, tip, performance, hints, candidate)
    def identity: Long = id

    /** Let [[diagnostics]] read this session's pipeline through `probe`, which must not block. */
    private[node] def observed(probe: F[Peer.PipelineState]): F[Unit] = owner.observe(id, probe)
    private[node] def updatePerformance(performance: FetchGsv.PeerGsv): F[Unit] =
      owner.updatePerformance(id, performance)
    private[node] def reserve(
        headers: Vector[Header],
        mode: FetchMode,
        candidate: Option[ChainSelection.Candidate] = None
    ): Resource[F, Allocation] =
      owner.reserve(id, headers, mode, candidate)
    private[node] def await(hash: Bytes): F[Unit] = owner.await(hash)
    private[node] def received(allocation: Allocation, hash: Bytes): F[Unit] =
      owner.received(id, allocation.token, hash)
    private[node] def reconcile(allocation: Allocation, runs: Vector[Vector[Header]]): F[Unit] =
      owner.reconcile(id, allocation.token, runs)
    private[node] def completed(allocation: Allocation): F[Unit] =
      owner.completed(id, allocation.token)

  private[node] def estimatedBytes(header: Header): Long = header match
    case h: Header.Shelley =>
      (h.bodySize + h.raw.size + 10).min(BigInt(Long.MaxValue)).max(0).toLong
    case _: Header.Byron => 0L

  private def sizeOf[F[_]](header: Header, state: State[F]): Long =
    state.hints.get(header.hash).fold(estimatedBytes(header))(_._1)

  private def fragmentsOf(
      headers: Vector[Header],
      size: Header => Long
  ): Vector[BlockFetchPolicy.Fragment] =
    val runs = headers.foldLeft(Vector.empty[Vector[Header]]) { (groups, header) =>
      groups.lastOption match
        case Some(previous) if header.prevHash.contains(previous.last.hash) =>
          groups.init :+ (previous :+ header)
        case _ => groups :+ Vector(header)
    }
    runs.map(run =>
      BlockFetchPolicy.Fragment(
        run.head.prevHash,
        run.map(h => BlockFetchPolicy.Header(h.hash, h.slot, size(h)))
      )
    )

  private[node] def compareTips(a: Header, b: Header): Int =
    // ClientInterface.hs:286–291 uses compareAnchoredFragments, hence raw Ord;
    // configured (including cross-era) eligibility is checked by FetchCandidates.
    CandidateSelection.compareTips(a, b)

  def create[F[_]: Async](
      config: BlockFetchPolicy.Config = BlockFetchPolicy.Config(),
      selection: Option[F[ChainSelection.Local]] = None,
      fetched: Option[F[Set[Bytes]]] = None,
      salt: Long = 0L
  ): F[FetchCoordinator[F]] =
    (
      Deferred[F, Unit].flatMap(signal => Ref.of[F, State[F]](State[F](signal, 0, Map.empty))),
      Ref.of[F, Option[GenesisControl[F]]](None),
      Mutex[F],
      Ref.of[F, Option[Holding]](None),
      Ref.of[F, Map[Long, Waiting]](Map.empty)
    ).mapN { (state, genesis, decisions, holder, waits) =>
      new FetchCoordinator(
        state,
        config,
        selection,
        fetched,
        salt,
        genesis,
        decisions,
        holder,
        waits
      )
    }
