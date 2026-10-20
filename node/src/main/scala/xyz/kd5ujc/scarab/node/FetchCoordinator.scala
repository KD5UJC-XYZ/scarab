// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, FetchGsv, FetchMode}

/** One decision/reservation transaction for every inbound and outbound fetching session. The
  * decision is pure; a reservation is committed before its owner is woken. In particular, the
  * single-block recovery path cannot bypass the reference's peer and outstanding-work policy.
  */
final class FetchCoordinator[F[_]: Async] private (
    state: Ref[F, FetchCoordinator.State[F]],
    config: BlockFetchPolicy.Config,
    selection: Option[F[ChainSelection.Local]],
    fetched: Option[F[Set[Bytes]]],
    salt: Long
):
  import FetchCoordinator.*
  private val F = Async[F]

  def setMode(mode: FetchMode): F[Unit] = change(_.copy(mode = mode))

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
    F.uncancelable { _ =>
      (Deferred[F, Unit], selection.sequence, fetched.sequence).tupled.flatMap {
        (signal, local, cached) =>
          state
            .modify { before =>
              val input = f(before).copy(
                signal = signal,
                selected = local,
                fetched = cached.getOrElse(Set.empty)
              )
              val (after, grants) = dispatch(input)
              (after, (before.signal, grants))
            }
            .flatMap { (previous, grants) =>
              grants.traverse_((promise, allocation) => promise.complete(allocation).void) *>
                previous.complete(()).void
            }
      }
    }

  private def await(id: Long, hash: Bytes): F[Unit] =
    F.tailRecM(()) { _ =>
      state.get.flatMap { s =>
        val claimed =
          s.peers.exists((peer, p) => peer != id && p.active.values.exists(_.claimed(hash)))
        if claimed then s.signal.get.as(Left(())) else F.pure(Right(()))
      }
    }

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
          _.map(_.copy(tip = tip, performance = performance, candidate = candidate))
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
        Deferred[F, Allocation].flatMap { result =>
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
              (if live then change(_.copy(mode = mode)) else result.complete(Allocation.empty).void)
                .as(token -> result)
            }
        }
      ) { (token, _) =>
        change(s =>
          s.copy(peers =
            s.peers.updatedWith(id)(
              _.map(p =>
                p.copy(pending = p.pending.filterNot(_.token == token), active = p.active - token)
              )
            )
          )
        )
      }
      .evalMap((_, result) => result.get)

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
    val ordered = tips.sortWith((a, b) => compareTips(a, b) < 0)
    def preference(tip: Option[Header]): Int = tip.fold(0) { h =>
      ordered.indexWhere(other => compareTips(h, other) == 0).max(0)
    }
    val candidates = s.peers.toVector.sortBy(_._1).map { (id, peer) =>
      val flight = peer.active.values.toVector
      val remaining = flight.flatMap(_.remaining)
      val admitted = for
        local <- s.selected
        chain <- chainOf(peer)
      yield FetchCandidates.select(
        ChainSelection.tiebreakerFor(tipOf(peer).fold(xyz.kd5ujc.scarab.protocol.Era.Byron)(_.era)),
        local,
        chain,
        chain.headers.filter(h => s.fetched(h.hash)).map(_.point).toSet
      )
      val eligible = admitted.map(_.toOption.toVector.flatMap(_.headers).map(_.hash).toSet)
      val fragments =
        peer.pending.headOption.toVector.flatMap(o =>
          fragmentsOf(o.headers.filter(h => eligible.forall(_(h.hash))), h => sizeOf(h, s))
        )
      BlockFetchPolicy.Candidate(
        peer = id,
        chainPreference = preference(tipOf(peer)),
        chainHead = tipOf(peer).fold(Bytes.empty)(_.hash),
        fragments = fragments,
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
        refusal = admitted.flatMap(_.left.toOption)
      )
    }
    val decisions = BlockFetchPolicy.decide(config, s.mode, candidates)
    decisions.foldLeft((s, Vector.empty[(Deferred[F, Allocation], Allocation)])) {
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
            val headers = offer.headers.filter(h => hashes(h.hash))
            val inFlight = candidates.flatMap { c =>
              if s.mode == FetchMode.Deadline && c.peer != id then Vector.empty
              else c.inFlight.blocks.toVector
            }.toSet
            val admitted = candidates.find(_.peer == id)
            val eligible =
              admitted.toVector.flatMap(_.fragments.flatMap(_.headers.map(_.hash))).toSet
            val remaining =
              offer.headers.filter(h => eligible(h.hash) && !hashes(h.hash) && !inFlight(h.hash))
            val refusal = decision match
              case BlockFetchPolicy.Decision.Declined(_, reason) => Some(reason)
              case _                                             => None
            val allocation = Allocation(offer.token, headers, remaining, selected.size, refusal)
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

object FetchCoordinator:
  private final case class Offer[F[_]](
      token: Long,
      headers: Vector[Header],
      result: Deferred[F, Allocation],
      candidate: Option[ChainSelection.Candidate]
  )
  private final case class Active(remaining: Vector[Header], requests: Int, claimed: Set[Bytes])
  private final case class PeerState[F[_]](
      identity: Long,
      tip: Option[Header] = None,
      candidate: Option[ChainSelection.Candidate] = None,
      performance: FetchGsv.PeerGsv = FetchGsv.default,
      pending: Vector[Offer[F]] = Vector.empty,
      active: Map[Long, Active] = Map.empty,
      busy: Boolean = false
  )
  private final case class State[F[_]](
      signal: Deferred[F, Unit],
      next: Long,
      peers: Map[Long, PeerState[F]],
      mode: FetchMode = FetchMode.BulkSync,
      hints: Map[Bytes, (Long, Long)] = Map.empty,
      selected: Option[ChainSelection.Local] = None,
      fetched: Set[Bytes] = Set.empty
  )
  private[node] final case class Allocation(
      token: Long,
      headers: Vector[Header],
      remaining: Vector[Header],
      requests: Int,
      refusal: Option[BlockFetchPolicy.Refusal] = None
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
    private[node] def reserve(
        headers: Vector[Header],
        mode: FetchMode,
        candidate: Option[ChainSelection.Candidate] = None
    ): Resource[F, Allocation] =
      owner.reserve(id, headers, mode, candidate)
    private[node] def await(hash: Bytes): F[Unit] = owner.await(id, hash)
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

  private def compareTips(a: Header, b: Header): Int =
    val restriction =
      ChainSelection.tiebreakerFor(if a.era.ordinal > b.era.ordinal then a.era else b.era)
    val av = SelectView.of(a)
    val bv = SelectView.of(b)
    if ChainSelection.prefers(restriction, av, bv) then -1
    else if ChainSelection.prefers(restriction, bv, av) then 1
    else 0

  def create[F[_]: Async](
      config: BlockFetchPolicy.Config = BlockFetchPolicy.Config(),
      selection: Option[F[ChainSelection.Local]] = None,
      fetched: Option[F[Set[Bytes]]] = None,
      salt: Long = 0L
  ): F[FetchCoordinator[F]] =
    Deferred[F, Unit]
      .flatMap(signal => Ref.of[F, State[F]](State[F](signal, 0, Map.empty)))
      .map(new FetchCoordinator(_, config, selection, fetched, salt))
