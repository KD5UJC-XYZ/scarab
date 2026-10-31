// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.network.genesis.PicosClock
import xyz.kd5ujc.scarab.codec.{Bytes, HaskellSort}
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.ProtocolResults.Control
import xyz.kd5ujc.scarab.network.selection.{
  BlockFetchPolicy,
  FetchGsv,
  FetchMode,
  GenesisBlockFetch,
  Gsm
}

/** The BlockFetch decision logic of every inbound and outbound fetching session: ouroboros-network
  * 2fb69829 BlockFetch/State.hs `fetchLogicIterations`. One loop ([[watchDecisions]]) decides for
  * every peer from its whole current candidate and adds each decided request to that peer's request
  * slot, the reference's `fetchClientRequestVar`; the session's fetch client takes it from there
  * ([[Session.acquire]]). Sessions only report what changed: their candidate, the blocks that
  * arrived. Nothing a session publishes or offers decides what is requested.
  *
  * The node owns the loop for its whole lifetime, independently of session registration (consensus
  * 82ecba329 NodeKernel.hs:382–393). Its owner receives defects directly.
  */
final class FetchCoordinator[F[_]: Async] private (
    state: Ref[F, FetchCoordinator.State[F]],
    config: BlockFetchPolicy.Config,
    selection: Option[F[ChainSelection.Local]],
    fetched: Option[F[Set[Bytes]]],
    salt: Long,
    genesis: Ref[F, Option[FetchCoordinator.GenesisControl[F]]],
    decisions: Mutex[F],
    holder: Ref[F, Option[FetchCoordinator.Holding]]
):
  import FetchCoordinator.*
  private val F = Async[F]

  def setMode(mode: FetchMode): F[Unit] = change(_.copy(mode = mode))

  /** Installed once by the Genesis governor; phase and starvation are live observations. */
  def installGenesis(control: GenesisControl[F]): F[Unit] =
    locked(DecisionHolder.InstallGenesis)(genesis.set(Some(control)) *> changeLocked(identity))
  def refreshGenesis: F[Unit] = change(identity)

  /** Publish a GSM transition as one transaction with respect to a decision: a decision samples and
    * acts under the same lock, so one sampled before this transition cannot demote the dynamo after
    * it has completed.
    */
  def transitionGenesis(update: F[Unit]): F[Unit] =
    locked(DecisionHolder.TransitionGenesis)(F.uncancelable(_ => update *> changeLocked(identity)))

  /** `fetchLogicIterations` (State.hs:73–107): each iteration registers the grace delay (150),
    * waits until the trigger fingerprint differs from the last one or the grace has expired
    * (`readStateVariables`, 395–416), decides for every peer and acts (166–190), then sleeps for
    * the rest of the decision interval of the fetch mode it decided in (100–106).
    * Node/Genesis.hs:114 configures zero grace without Genesis, so the Praos loop runs at its 10ms
    * interval.
    */
  private[node] def watchDecisions(
      gracePicos: BigInt,
      clock: Option[PicosClock[F]] = None,
      intervals: DecisionIntervals = DecisionIntervals.default
  ): F[Nothing] = loop(F.pure(gracePicos), clock.getOrElse(PicosClock.system[F]), intervals)

  /** The node-owned decision loop, using the live Genesis grace configuration. */
  private[node] def run: F[Nothing] =
    val grace = genesis.get.flatMap(_.traverse(_.context)).map(_.fold(BigInt(0))(_.gracePicos))
    loop(grace, PicosClock.system[F], DecisionIntervals.default)

  private def loop(
      grace: F[BigInt],
      timer: PicosClock[F],
      intervals: DecisionIntervals
  ): F[Nothing] =
    F.tailRecM[Fingerprint, Nothing](Fingerprint.initial) { previous =>
      (timer.now, grace).tupled.flatMap { (start, gracePicos) =>
        iteration(previous, start + gracePicos, timer).flatMap { (next, genesisMode) =>
          timer.now.flatMap { end =>
            val interval = if genesisMode then intervals.genesisPicos else intervals.praosPicos
            timer.sleep((interval - (end - start)).max(0)).as(Left(next))
          }
        }
      }
    }

  /** One `fetchLogicIteration` (State.hs:136–190): the fingerprint the next iteration compares
    * with, and whether this one decided in the Genesis fetch mode.
    */
  private[node] def iteration(
      previous: Fingerprint,
      graceExpiresPicos: BigInt,
      timer: PicosClock[F]
  ): F[(Fingerprint, Boolean)] =
    F.tailRecM(()) { _ =>
      (state.get, selection.sequence, timer.now).tupled.flatMap { (current, local, now) =>
        if now >= graceExpiresPicos || fingerprint(current, local) != previous then
          F.pure(Right(()))
        else F.race(current.signal.get, timer.sleep(graceExpiresPicos - now)).as(Left(()))
      }
    } *> locked(DecisionHolder.Decide)(decide)

  /** One decision now, whatever changed: an iteration whose grace has expired. */
  private[node] def step: F[Unit] = locked(DecisionHolder.Decide)(decide).void

  /** What a stall report reads of each session, from the state without the decision lock: a stall
    * report must not wait behind the very transaction that may be stuck. Each session's own
    * pipeline probe (set by [[Session.observed]]) is read after the state, outside it.
    */
  private[node] def diagnostics: F[Vector[SessionDiagnostic]] =
    state.get.flatMap { s =>
      s.peers.toVector.sortBy(_._1).traverse { (id, peer) =>
        peer.probe.sequence.map(pipeline =>
          SessionDiagnostic(
            session = id,
            busy = peer.busy,
            pendingRequests = peer.request.fold(0)(_.requests),
            pendingHeaders = peer.request.fold(0)(_.headers.size),
            active = peer.active.toVector.sortBy(_._1).map { (token, a) =>
              ActiveFetch(token, a.requests, a.remaining.size)
            },
            candidateHeaders = peer.candidate.map(_.headers.size),
            pipeline = pipeline
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
        headers.map(estimatedBytes).sum,
        headers.map(_.hash).toSet
      )
    }
  }

  /** A session of the peer `identity` (the seed of its tiebreaker), ranked among the others by
    * `order`, the key of the reference's peer map; without one, by the order the sessions
    * registered in.
    */
  def session(identity: Long, order: Option[PeerOrder] = None): Resource[F, Session[F]] =
    Resource
      .make(
        state.modify { s =>
          val id = s.next
          val peer = PeerState[F](identity ^ salt, order.getOrElse(PeerOrder(id)))
          (s.copy(next = id + 1, peers = s.peers.updated(id, peer)), id)
        }
      )(id => change(s => s.copy(peers = s.peers - id)))
      .map(id => new Session(this, id))

  private def change(f: State[F] => State[F]): F[Unit] =
    locked(DecisionHolder.Change)(changeLocked(f))

  /** `fa` under the decision lock, noting who holds it and since when: one clock read and one `Ref`
    * write on entry and one on exit, and no lock of its own, so that a stall report can say what
    * the lock is held by without taking it.
    */
  private def locked[A](who: DecisionHolder)(fa: F[A]): F[A] =
    decisions.lock.surround(
      F.uncancelable(poll =>
        F.monotonic.flatMap(at => holder.set(Some(Holding(who, at)))) *>
          F.guarantee(poll(fa), holder.set(None))
      )
    )

  /** A change wakes whoever waits on the state: the decision loop and the fetch clients. */
  private def changeLocked(f: State[F] => State[F]): F[Unit] =
    F.uncancelable { _ =>
      Deferred[F, Unit].flatMap { signal =>
        state
          .modify(before => (f(before).copy(signal = signal), before.signal))
          .flatMap(_.complete(()).void)
      }
    }

  /** Decide from one snapshot and act on it (State.hs:166–190), returning the next fingerprint:
    * this snapshot's, with the statuses of the peers the decision added requests to (187–188).
    */
  private def decide: F[(Fingerprint, Boolean)] =
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
            val snapshot = before.copy(
              selected = local,
              fetched = cached.getOrElse(Set.empty),
              genesisContext = control.map(_._2),
              demotions = Vector.empty
            )
            val (after, requested, genesisMode) = act(snapshot)
            val next = fingerprint(snapshot, local).withStatuses(
              requested.flatMap(id => after.peers.get(id).map(id -> status(_)))
            )
            val changed = requested.nonEmpty || after.genesisState != before.genesisState
            (
              if changed then after.copy(signal = signal) else after.copy(signal = before.signal),
              (Option.when(changed)(before.signal), after.demotions, (next, genesisMode))
            )
          }
          .flatMap { (previous, demotions, result) =>
            previous.traverse_(_.complete(()).void) *>
              control.traverse_((owner, _) => demotions.traverse_(owner.demoteDynamo)).as(result)
          }
      }
    }

  /** The trigger fingerprint of `readStateVariables` (State.hs:401–413): the head of our current
    * chain, the head of each peer's candidate and each peer's status. Fetched blocks, GSVs and the
    * fetch mode are non-trigger state (317–335). The chain comparison's fingerprint is that of the
    * Peras weight snapshot (consensus 82ecba329 BlockFetch/ClientInterface.hs:235–243), not
    * applicable without Peras. Our chain's changes wake the loop through the follower's commit
    * ([[Follower.installFetchCoordinator]]), as a change of the ChainDB's chain wakes the STM read.
    */
  private def fingerprint(s: State[F], local: Option[ChainSelection.Local]): Fingerprint =
    Fingerprint(
      local.map(l => l.blocks.lastOption.fold(l.anchor)(_.point)),
      s.peers.map((id, peer) => id -> candidateOf(peer).tip),
      s.peers.map((id, peer) => id -> status(peer))
    )

  /** `PeerFetchStatus` (ClientState.hs:145–188): busy, or ready with the blocks in flight and
    * whether any request is.
    */
  private def status(peer: PeerState[F]): FetchStatus =
    val remaining = peer.active.valuesIterator.flatMap(_.remaining).toVector
    if peer.busy then FetchStatus.Busy
    else
      FetchStatus.Ready(
        remaining.map(_.hash).toSet,
        peer.active.valuesIterator.forall(_.requests == 0)
      )

  /** The session's ChainSync candidate: an empty fragment at the genesis anchor until it announces
    * one, as consensus 82ecba329 ChainSync/Client.hs:403–408 registers each client's candidate.
    */
  private def candidateOf(peer: PeerState[F]): ChainSelection.Candidate =
    peer.candidate.getOrElse(
      ChainSelection.Candidate(xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin, Vector.empty)
    )

  private def received(id: Long, allocation: Allocation, hash: Bytes): F[Unit] =
    change(s =>
      s.copy(peers = s.peers.updatedWith(id)(_.map { p =>
        val active = p.active.updatedWith(allocation.token)(
          _.map(a => a.copy(remaining = a.remaining.filterNot(_.hash == hash)))
        )
        val bytes = active.valuesIterator.flatMap(_.remaining).map(estimatedBytes).sum
        p.copy(active = active, busy = bytes > allocation.limits.lowBytes)
      }))
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

  /** NoBlocks removes the rejected fragment and its request in one transaction: network 2fb69829
    * ClientState.hs:665–685.
    */
  private def rejected(id: Long, allocation: Allocation, headers: Vector[Header]): F[Unit] =
    val hashes = headers.map(_.hash).toSet
    change(s =>
      s.copy(peers = s.peers.updatedWith(id)(_.map { peer =>
        val active = peer.active.updatedWith(allocation.token)(
          _.map(a =>
            a.copy(
              remaining = a.remaining.filterNot(h => hashes(h.hash)),
              requests = (a.requests - 1).max(0)
            )
          )
        )
        val bytes = active.valuesIterator.flatMap(_.remaining).map(estimatedBytes).sum
        peer.copy(active = active, busy = bytes > allocation.limits.lowBytes)
      }))
    )

  /** The request's ranges have ended, however they ended: nothing of it is in flight any more. */
  private def finished(id: Long, token: Long): F[Unit] =
    change(s =>
      s.copy(peers = s.peers.updatedWith(id)(_.map(p => p.copy(active = p.active - token))))
    )

  /** ControlMessage.hs:47–55 and ClientState.hs:551–555: termination wins over an already queued
    * request in the same transaction that acknowledges the request.
    */
  private def takeRequest(id: Long): F[Either[Deferred[F, Unit], Option[Allocation]]] =
    state.modify { s =>
      s.peers.get(id) match
        case None                                            => (s, Right(None))
        case Some(peer) if peer.control == Control.Terminate => (s, Right(None))
        case Some(peer) =>
          peer.request match
            case Some(request) =>
              (s.copy(peers = s.peers.updated(id, peer.copy(request = None))), Right(Some(request)))
            case None => (s, Left(s.signal))
    }

  private def acquire(id: Long): F[Option[Allocation]] =
    F.uncancelable { poll =>
      F.tailRecM(()) { _ =>
        takeRequest(id).flatMap {
          case Right(request) => F.pure(Right(request))
          case Left(signal)   => poll(signal.get).as(Left(()))
        }
      }
    }

  private def tryAcquire(id: Long): F[Option[Allocation]] =
    takeRequest(id).map(_.toOption.flatten)

  private def terminate(id: Long): F[Unit] =
    change(s => s.copy(peers = s.peers.updatedWith(id)(_.map(_.copy(control = Control.Terminate)))))

  /** Until no request of this session that its fetch client took is in flight. */
  private def settled(id: Long): F[Unit] =
    F.tailRecM(()) { _ =>
      state.get
        .map { s =>
          val pending = s.peers.get(id).flatMap(_.request).map(_.token)
          val acquired = s.peers.get(id).fold(Map.empty[Long, Active])(_.active) -- pending
          Either.cond(acquired.isEmpty, (), s.signal)
        }
        .flatMap(_.fold(_.get.as(Left(())), _ => F.pure(Right(()))))
    }

  /** The body of `hash` as `take` returns it, once it has one: after any session fetching it has
    * delivered it, waiting for the decisions to fetch it otherwise.
    */
  private def obtain[A](hash: Bytes, take: F[Option[A]]): F[A] =
    F.tailRecM(()) { _ =>
      state.get.flatMap { s =>
        await(hash) *> take.flatMap {
          case Some(value) => F.pure(Right(value))
          case None        => s.signal.get.as(Left(()))
        }
      }
    }

  private def hasBody(hash: Bytes): F[Boolean] = fetched.sequence.map(_.exists(_(hash)))

  private def changed: F[F[Unit]] = state.get.map(_.signal.get)

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
    * retrigger a decision or restart the grace wait; the next real trigger reads them.
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
      candidate: Option[ChainSelection.Candidate]
  ): F[Unit] =
    change { s =>
      s.copy(
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

  /** `fetchDecisionsForStateSnapshot` and `fetchLogicIterationAct` (State.hs:204–304): the
    * decisions for every peer, each requested fragment added to that peer's request slot. A finite,
    * pure transaction: it never waits or performs wire effects. Also the sessions whose slot it
    * added to, and whether it decided in the Genesis fetch mode.
    *
    * The peers are taken in the order of the reference's `Map.toList` over its maps keyed by peer
    * (`peerChainsAndPeerInfo`, State.hs:274–281), [[PeerOrder]]; the comparator of `comparePeerGSV`
    * is not transitive, so the order of its input decides the order of its output.
    */
  private def act(s: State[F]): (State[F], Vector[Long], Boolean) =
    def tipOf(peer: PeerState[F]): Option[Header] =
      peer.candidate.flatMap(_.headers.lastOption).orElse(peer.tip)
    val peers = s.peers.toVector.sortBy((id, peer) => (peer.order, id))
    val tips = peers.flatMap((_, peer) => tipOf(peer))
    val ordered = HaskellSort.sortBy(tips)(compareTips)
    def preference(tip: Option[Header]): Int = tip.fold(0) { h =>
      ordered.indexWhere(other => compareTips(h, other) == 0).max(0)
    }
    val useGenesis = s.genesisContext.exists(_.phase != Gsm.Phase.CaughtUp)
    val candidates = peers.map { (id, peer) =>
      val flight = peer.active.values.toVector
      val remaining = flight.flatMap(_.remaining)
      val restriction =
        ChainSelection.tiebreakerFor(tipOf(peer).fold(xyz.kd5ujc.scarab.protocol.Era.Byron)(_.era))
      // Decision.hs:251–310, stages 1–3: filterPlausibleCandidates (405–420), selectForkSuffixes
      // (547–558) and filterNotAlreadyFetched (599–617) over the peer's whole current candidate.
      // Genesis selects from the plausible suffix before removing fetched blocks
      // (Decision/Genesis.hs).
      val chain = candidateOf(peer)
      val chosen =
        s.selected.fold[Either[BlockFetchPolicy.Refusal, Vector[Header]]](
          Right(chain.headers.filterNot(h => s.fetched(h.hash)))
        ) { local =>
          if useGenesis then
            if !FetchCandidates.plausible(restriction, local, chain) then
              Left(BlockFetchPolicy.Refusal.ChainNotPlausible)
            else FetchCandidates.suffix(local, chain).map(_._2)
          else
            FetchCandidates
              .select(
                restriction,
                local,
                chain,
                chain.headers.filter(h => s.fetched(h.hash)).map(_.point).toSet
              )
              .map(_.headers)
        }
      BlockFetchPolicy.Candidate(
        peer = id,
        chainPreference =
          if useGenesis || s.mode == FetchMode.Deadline then -preference(tipOf(peer))
          else preference(tipOf(peer)),
        chainHead = tipOf(peer).fold(Bytes.empty)(_.hash),
        fragments = fragmentsOf(chosen.getOrElse(Vector.empty), estimatedBytes),
        performance = peer.performance,
        inFlight = BlockFetchPolicy.InFlight(
          flight.map(_.requests).sum,
          remaining.map(estimatedBytes).sum,
          remaining.map(_.hash).toSet
        ),
        status = status(peer) match
          case FetchStatus.Busy        => BlockFetchPolicy.Status.Busy
          case FetchStatus.Ready(_, _) => BlockFetchPolicy.Status.Ready
        ,
        tieBreak = peer.identity,
        chainHeadSlot = tipOf(peer).fold(BigInt(0))(_.slot),
        refusal = chosen.left.toOption
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
    // Stages 4–7 (filterNotAlreadyInFlightWithPeer, prioritisePeerChains,
    // filterNotAlreadyInFlightWithOtherPeers, fetchRequestDecisions) are BlockFetchPolicy.decide.
    val decided =
      genesisResult.fold(BlockFetchPolicy.decide(config, s.mode, candidates))(_.decisions)
    // Decision/Genesis.hs:211–229: the peers order is written only with a request; a demotion
    // (checkLastChainSelStarvation, 281–299) demotes the dynamo either way.
    val dispatched = genesisResult.fold(s)(r =>
      s.copy(
        genesisState =
          if r.decisions.exists(_.isInstanceOf[BlockFetchPolicy.Decision.Request[?]]) then r.state
          else s.genesisState,
        demotions = r.demoteDynamo.toVector
      )
    )
    // State.hs:298–304: only a request is acted on; a declined peer is left as it is.
    val (after, requested) = decided.foldLeft((dispatched, Vector.empty[Long])) {
      case ((current, requested), BlockFetchPolicy.Decision.Request(id, fragments))
          if fragments.exists(_.headers.nonEmpty) =>
        current.peers.get(id).fold((current, requested)) { peer =>
          val hashes = fragments.flatMap(_.headers.map(_.hash)).toSet
          val headers = candidateOf(peer).headers.filter(h => hashes(h.hash))
          (addRequest(current, id, peer, headers, fragments.size), requested :+ id)
        }
      case (unchanged, _) => unchanged
    }
    (after, requested, useGenesis)

  /** `addNewFetchRequest` (ClientState.hs:484–530): add `headers` (`fragments` ranges) to the
    * peer's request slot, merged with a request its client has not taken yet as the `FetchRequest`
    * semigroup merges (348–354), and count them in flight at once (`addHeadersInFlight`, 254–287).
    */
  private def addRequest(
      s: State[F],
      id: Long,
      peer: PeerState[F],
      headers: Vector[Header],
      fragments: Int
  ): State[F] =
    val (request, next) = peer.request match
      case Some(old) =>
        val joined =
          old.headers.lastOption.exists(l => headers.headOption.exists(_.prevHash.contains(l.hash)))
        (
          old.copy(
            headers = old.headers ++ headers,
            requests = old.requests + fragments - (if joined then 1 else 0),
            performance = peer.performance,
            limits = peer.performance.window
          ),
          s.next
        )
      case None =>
        (
          Allocation(s.next, headers, fragments, peer.performance, peer.performance.window),
          s.next + 1
        )
    val active = peer.active.updatedWith(request.token) { previous =>
      val before = previous.getOrElse(Active(Vector.empty, 0, Set.empty))
      Some(
        Active(
          before.remaining ++ headers,
          before.requests + request.requests - peer.request.fold(0)(_.requests),
          before.claimed ++ headers.map(_.hash)
        )
      )
    }
    val bytes = active.valuesIterator.flatMap(_.remaining).map(estimatedBytes).sum
    val updated = peer.copy(
      request = Some(request),
      active = active,
      busy = bytes >= peer.performance.window.highBytes
    )
    s.copy(next = next, peers = s.peers.updated(id, updated))

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

  /** `PeerFetchStatus` as the trigger fingerprint compares it (ClientState.hs:145–188). */
  private[node] enum FetchStatus:
    case Busy
    case Ready(inFlight: Set[Bytes], idle: Boolean)

  /** `FetchStateFingerprint` (State.hs:338–364). */
  private[node] final case class Fingerprint(
      current: Option[xyz.kd5ujc.scarab.protocol.ChainSync.Point],
      candidates: Map[Long, xyz.kd5ujc.scarab.protocol.ChainSync.Point],
      statuses: Map[Long, FetchStatus]
  ):
    /** `updateFetchStateFingerprintPeerStatus` (354–364): the later statuses override. */
    def withStatuses(updated: Vector[(Long, FetchStatus)]): Fingerprint =
      copy(statuses = statuses ++ updated)
  private[node] object Fingerprint:
    val initial: Fingerprint = Fingerprint(None, Map.empty, Map.empty)

  /** One request of a session that has not ended: `requests` BlockFetch ranges and `headers` of its
    * blocks still to arrive.
    */
  private[node] final case class ActiveFetch(token: Long, requests: Int, headers: Int)

  /** A session as a stall report sees it: what the coordinator holds for it (`busy`, the request
    * its fetch client has not taken yet, the requests in flight, the headers of its candidate) and,
    * for a pipelined session, its [[Peer.PipelineState]].
    */
  private[node] final case class SessionDiagnostic(
      session: Long,
      busy: Boolean,
      pendingRequests: Int,
      pendingHeaders: Int,
      active: Vector[ActiveFetch],
      candidateHeaders: Option[Int],
      pipeline: Option[Peer.PipelineState]
  )

  /** The decision lock is held by `by` for `running`. */
  private[node] final case class LockHolder(by: DecisionHolder, running: FiniteDuration)

  /** What takes the decision lock. */
  private[node] enum DecisionHolder:
    case InstallGenesis, TransitionGenesis, Change, Decide

  private[node] final case class Holding(who: DecisionHolder, at: FiniteDuration)

  private final case class Active(remaining: Vector[Header], requests: Int, claimed: Set[Bytes])
  private final case class PeerState[F[_]](
      identity: Long,
      order: PeerOrder,
      tip: Option[Header] = None,
      candidate: Option[ChainSelection.Candidate] = None,
      performance: FetchGsv.PeerGsv = FetchGsv.default,
      control: Control = Control.Continue,
      request: Option[Allocation] = None,
      active: Map[Long, Active] = Map.empty,
      busy: Boolean = false,
      probe: Option[F[Peer.PipelineState]] = None
  )
  private final case class State[F[_]](
      signal: Deferred[F, Unit],
      next: Long,
      peers: Map[Long, PeerState[F]],
      mode: FetchMode = FetchMode.BulkSync,
      selected: Option[ChainSelection.Local] = None,
      fetched: Set[Bytes] = Set.empty,
      genesisContext: Option[GenesisContext] = None,
      genesisState: GenesisBlockFetch.State[Long] = GenesisBlockFetch.State[Long](),
      demotions: Vector[Long] = Vector.empty
  )

  /** A request the decisions added for a session: `headers` in `requests` contiguous ranges. */
  private[node] final case class Allocation(
      token: Long,
      headers: Vector[Header],
      requests: Int,
      performance: FetchGsv.PeerGsv,
      limits: FetchGsv.Window
  )

  final class Session[F[_]] private[FetchCoordinator] (owner: FetchCoordinator[F], id: Long):
    def announce(
        tip: Option[Header],
        performance: FetchGsv.PeerGsv,
        candidate: Option[ChainSelection.Candidate] = None
    ): F[Unit] =
      owner.announce(id, tip, performance, candidate)
    def identity: Long = id

    /** Let [[diagnostics]] read this session's pipeline through `probe`, which must not block. */
    private[node] def observed(probe: F[Peer.PipelineState]): F[Unit] = owner.observe(id, probe)
    private[node] def updatePerformance(performance: FetchGsv.PeerGsv): F[Unit] =
      owner.updatePerformance(id, performance)
    private[node] def acquire: F[Option[Allocation]] = owner.acquire(id)
    private[node] def tryAcquire: F[Option[Allocation]] = owner.tryAcquire(id)
    private[node] def terminate: F[Unit] = owner.terminate(id)
    private[node] def settled: F[Unit] = owner.settled(id)
    private[node] def obtain[A](hash: Bytes, take: F[Option[A]]): F[A] = owner.obtain(hash, take)

    /** Whether the block of `hash` is fetched. */
    private[node] def hasBody(hash: Bytes): F[Boolean] = owner.hasBody(hash)

    /** Completes at the coordinator's next change after it was read: a block's arrival among them.
      */
    private[node] def changed: F[F[Unit]] = owner.changed
    private[node] def await(hash: Bytes): F[Unit] = owner.await(hash)
    private[node] def received(allocation: Allocation, hash: Bytes): F[Unit] =
      owner.received(id, allocation, hash)
    private[node] def rejected(allocation: Allocation, headers: Vector[Header]): F[Unit] =
      owner.rejected(id, allocation, headers)
    private[node] def completed(allocation: Allocation): F[Unit] =
      owner.completed(id, allocation.token)
    private[node] def finished(allocation: Allocation): F[Unit] =
      owner.finished(id, allocation.token)

  private[node] def estimatedBytes(header: Header): Long = header match
    case h: Header.Shelley =>
      (h.bodySize + h.raw.size + 10).min(BigInt(Long.MaxValue)).max(0).toLong
    case h: Header.Byron => h.blockSizeHint.value.toLong

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
      Ref.of[F, Option[Holding]](None)
    ).mapN { (state, genesis, decisions, holder) =>
      new FetchCoordinator(
        state,
        config,
        selection,
        fetched,
        salt,
        genesis,
        decisions,
        holder
      )
    }
