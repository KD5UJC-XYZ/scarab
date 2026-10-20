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
    config: BlockFetchPolicy.Config
):
  import FetchCoordinator.*
  private val F = Async[F]

  def session(identity: Long): Resource[F, Session[F]] =
    Resource
      .make(
        state.modify { s =>
          val id = s.next
          (s.copy(next = id + 1, peers = s.peers.updated(id, PeerState(identity))), id)
        }
      )(id => change(s => s.copy(peers = s.peers - id)))
      .map(id => new Session(this, id))

  private def change(f: State[F] => State[F]): F[Unit] =
    F.uncancelable { _ => state.modify { before =>
      val (after, grants) = dispatch(f(before))
      (after, grants)
    }.flatMap(_.traverse_((promise, allocation) => promise.complete(allocation).void)) }

  private def announce(id: Long, tip: Option[Header], performance: FetchGsv.PeerGsv): F[Unit] =
    change(s => s.copy(peers = s.peers.updatedWith(id)(_.map(_.copy(tip = tip, performance = performance)))))

  private def reserve(id: Long, headers: Vector[Header], mode: FetchMode): Resource[F, Allocation] =
    Resource
      .make(
        Deferred[F, Allocation].flatMap { result =>
          state.modify { s =>
            val token = s.next
            val peer = s.peers.get(id)
            val next = s.copy(next = token + 1, peers = s.peers.updatedWith(id)(_.map(p =>
              p.copy(pending = p.pending :+ Offer(token, headers, result)))))
            (next, (token, peer.isDefined))
          }.flatMap { (token, live) =>
            (if live then change(_.copy(mode = mode)) else result.complete(Allocation.empty).void)
              .as(token -> result)
          }
        }
      ) { (token, _) =>
        change(s => s.copy(peers = s.peers.updatedWith(id)(_.map(p =>
          p.copy(pending = p.pending.filterNot(_.token == token), active = p.active - token)))))
      }
      .evalMap((_, result) => result.get)

  private def received(id: Long, token: Long, hash: Bytes): F[Unit] =
    change(s => s.copy(peers = s.peers.updatedWith(id)(_.map { p =>
      val active = p.active.updatedWith(token)(_.map(a =>
        a.copy(remaining = a.remaining.filterNot(_.hash == hash))))
      val bytes = active.valuesIterator.flatMap(_.remaining).map(estimatedBytes).sum
      p.copy(active = active, busy = p.busy && bytes > p.performance.window.lowBytes)
    })))

  private def reconcile(id: Long, token: Long, runs: Vector[Vector[Header]]): F[Unit] =
    change(s => s.copy(peers = s.peers.updatedWith(id)(_.map(p =>
      p.copy(active = p.active.updatedWith(token)(_.map(_ => Active(runs.flatten, runs.size))))))))

  private def completed(id: Long, token: Long): F[Unit] =
    change(s => s.copy(peers = s.peers.updatedWith(id)(_.map(p =>
      p.copy(active = p.active.updatedWith(token)(_.map(a =>
        a.copy(requests = (a.requests - 1).max(0)))))))))

  /** A finite transaction can admit several peers, but never waits or performs wire effects. */
  private def dispatch(s: State[F]): (State[F], Vector[(Deferred[F, Allocation], Allocation)]) =
    val tips = s.peers.valuesIterator.flatMap(_.tip).toVector
    val ordered = tips.sortWith((a, b) => compareTips(a, b) < 0)
    def preference(tip: Option[Header]): Int = tip.fold(0) { h =>
      ordered.indexWhere(other => compareTips(h, other) == 0).max(0)
    }
    val candidates = s.peers.toVector.sortBy(_._1).map { (id, peer) =>
      val flight = peer.active.values.toVector
      val remaining = flight.flatMap(_.remaining)
      val fragments = peer.pending.headOption.toVector.flatMap(o => fragmentsOf(o.headers))
      BlockFetchPolicy.Candidate(
        peer = id,
        chainPreference = preference(peer.tip),
        chainHead = peer.tip.fold(Bytes.empty)(_.hash),
        fragments = fragments,
        performance = peer.performance,
        inFlight = BlockFetchPolicy.InFlight(flight.map(_.requests).sum,
          remaining.map(estimatedBytes).sum, remaining.map(_.hash).toSet),
        status = if peer.busy && remaining.map(estimatedBytes).sum > peer.performance.window.lowBytes then BlockFetchPolicy.Status.Busy else BlockFetchPolicy.Status.Ready,
        tieBreak = peer.identity,
        chainHeadSlot = peer.tip.fold(BigInt(0))(_.slot)
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
          case BlockFetchPolicy.Decision.Declined(peer, BlockFetchPolicy.Refusal.InFlightOtherPeer) =>
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
            val remaining = offer.headers.filter(h => !hashes(h.hash) && !inFlight(h.hash))
            val allocation = Allocation(offer.token, headers, remaining, selected.size)
            val active = if headers.isEmpty then peer.active else
              peer.active.updated(offer.token, Active(headers, selected.size))
            val bytes = active.valuesIterator.flatMap(_.remaining).map(estimatedBytes).sum
            val next = peer.copy(pending = peer.pending.tail, active = active,
              busy = (peer.busy && candidates.find(_.peer == id).exists(_.status == BlockFetchPolicy.Status.Busy)) ||
                bytes >= peer.performance.window.highBytes)
            (current.copy(peers = current.peers.updated(id, next)), grants :+ (offer.result -> allocation))
          case _ => (current, grants)
    }

object FetchCoordinator:
  private final case class Offer[F[_]](token: Long, headers: Vector[Header], result: Deferred[F, Allocation])
  private final case class Active(remaining: Vector[Header], requests: Int)
  private final case class PeerState[F[_]](
      identity: Long,
      tip: Option[Header] = None,
      performance: FetchGsv.PeerGsv = FetchGsv.default,
      pending: Vector[Offer[F]] = Vector.empty,
      active: Map[Long, Active] = Map.empty,
      busy: Boolean = false
  )
  private final case class State[F[_]](
      next: Long = 0,
      peers: Map[Long, PeerState[F]] = Map.empty,
      mode: FetchMode = FetchMode.BulkSync
  )
  private[node] final case class Allocation(token: Long, headers: Vector[Header], remaining: Vector[Header], requests: Int)
  private object Allocation:
    val empty: Allocation = Allocation(-1, Vector.empty, Vector.empty, 0)

  final class Session[F[_]] private[FetchCoordinator](owner: FetchCoordinator[F], id: Long):
    def announce(tip: Option[Header], performance: FetchGsv.PeerGsv): F[Unit] =
      owner.announce(id, tip, performance)
    private[node] def reserve(headers: Vector[Header], mode: FetchMode): Resource[F, Allocation] =
      owner.reserve(id, headers, mode)
    private[node] def received(allocation: Allocation, hash: Bytes): F[Unit] =
      owner.received(id, allocation.token, hash)
    private[node] def reconcile(allocation: Allocation, runs: Vector[Vector[Header]]): F[Unit] =
      owner.reconcile(id, allocation.token, runs)
    private[node] def completed(allocation: Allocation): F[Unit] =
      owner.completed(id, allocation.token)

  private[node] def estimatedBytes(header: Header): Long = header match
    case h: Header.Shelley => (h.bodySize + h.raw.size + 10).min(BigInt(Long.MaxValue)).max(0).toLong
    case h: Header.Byron => h.raw.size.toLong + 65536L

  private def fragmentsOf(headers: Vector[Header]): Vector[BlockFetchPolicy.Fragment] =
    val runs = headers.foldLeft(Vector.empty[Vector[Header]]) { (groups, header) =>
      groups.lastOption match
        case Some(previous) if header.prevHash.contains(previous.last.hash) =>
          groups.init :+ (previous :+ header)
        case _ => groups :+ Vector(header)
    }
    runs.map(run => BlockFetchPolicy.Fragment(run.head.prevHash,
      run.map(h => BlockFetchPolicy.Header(h.hash, h.slot, estimatedBytes(h)))))

  private def compareTips(a: Header, b: Header): Int =
    val restriction = ChainSelection.tiebreakerFor(if a.era.ordinal > b.era.ordinal then a.era else b.era)
    val av = SelectView.of(a)
    val bv = SelectView.of(b)
    if ChainSelection.prefers(restriction, av, bv) then -1
    else if ChainSelection.prefers(restriction, bv, av) then 1
    else 0

  def create[F[_]: Async](config: BlockFetchPolicy.Config = BlockFetchPolicy.Config()): F[FetchCoordinator[F]] =
    Ref.of[F, State[F]](State[F]()).map(new FetchCoordinator(_, config))
