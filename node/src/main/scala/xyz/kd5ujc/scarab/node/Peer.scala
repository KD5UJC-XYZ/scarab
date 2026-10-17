// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{Block, Header, PeerError}
import cats.ApplicativeThrow
import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.effect.std.{Mutex, Queue, Semaphore, Supervisor}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{CardanoBlockFetch, ChainSync, NtNHeader}
import scala.util.control.NoStackTrace
import xyz.kd5ujc.scarab.network.{
  ChainSyncClient,
  NetworkError,
  NodeToNode,
  NodeToNodeConnection,
  TxSubmissionClient
}
import xyz.kd5ujc.scarab.network.selection.{FetchMode, Time}

/** A [[PeerError]] on its way from the fiber that met it to the session that handles it. A cats-mtl
  * `Raise` that comes from `Handle.allow` is valid only in the fibers of its scope: raised in any
  * other fiber (a background fiber of the connection, a fiber started by the pipeline) it is a
  * `cats.mtl.Handle$Submarine` that no scope catches, the fiber dies and whatever it held stays
  * held (issue 130). The code below the session therefore raises through [[Peer.carrying]], which
  * fails the fiber with this exception, an ordinary value that crosses fibers; the session's own
  * fiber raises it again through its `Raise` ([[Peer.landed]]).
  */
final class PeerFailure(val error: PeerError)
    extends RuntimeException(error.message)
    with NoStackTrace

enum SyncEvent:
  case RollForward(header: Header, tip: ChainSync.Tip)
  case RollBackward(point: ChainSync.Point, tip: ChainSync.Tip)

/** Upstream chain source: one ChainSync conversation plus block retrieval. */
trait Peer[F[_]]:
  /** The newest point among `candidates` (ordered newest first) the peer also has, if any. */
  def intersect(candidates: Vector[ChainSync.Point]): F[Option[ChainSync.Point]]

  /** Next roll-forward or roll-backward instruction, waiting while the peer is at its tip. */
  def next: F[SyncEvent]

  /** Raw node-to-node block bytes for a header announced by `next`. */
  def fetch(header: Header): F[Bytes]

object Peer:
  /** A peer whose request pipeline can be brought to rest between two batches. */
  trait Controlled[F[_]] extends Peer[F]:
    /** Stop requesting headers and wait until the pipeline is idle: no request outstanding on
      * ChainSync, none fetching blocks. What the peer announced meanwhile is dropped. Afterwards
      * the connection's hot protocols can be ended on the wire ([[NodeToNodeConnection.finish]]).
      * It waits for the peer to answer the request that is in flight, which at the peer's tip is
      * the next block; the caller bounds the wait.
      */
    def quiesce: F[Unit]

  /** A `Raise` for the fibers of a connection: the error fails the fiber as a [[PeerFailure]]
    * wherever it is raised, and [[landed]] raises it in the session.
    */
  def carrying[F[_]](using F: ApplicativeThrow[F]): Raise[F, PeerError] = new Raise[F, PeerError]:
    def functor = F
    def raise[E2 <: PeerError, A](e: E2): F[A] = F.raiseError(new PeerFailure(e))

  /** The same for the network layer's failures, classified as peer errors ([[peerError]]). */
  def carryingNetwork[F[_]](using F: ApplicativeThrow[F]): Raise[F, NetworkError] =
    new Raise[F, NetworkError]:
      def functor = F
      def raise[E2 <: NetworkError, A](e: E2): F[A] = F.raiseError(new PeerFailure(peerError(e)))

  /** `fa`, with a [[PeerFailure]] that it fails with raised through `into`: call it in the fiber
    * that owns `into` 's scope.
    */
  def landed[F[_], A](fa: F[A])(using F: ApplicativeThrow[F], into: Raise[F, PeerError]): F[A] =
    F.handleErrorWith(fa) {
      case failure: PeerFailure => into.raise(failure.error)
      case other                => F.raiseError(other)
    }

  /** What a session measured of its peer, for the log. */
  enum Observation:
    /** One pipelined ChainSync round: `requested` RequestNext sent at once, `received` instructions
      * back after `millis` (from the first request to the last reply). D3 uses it to see how long a
      * relay takes to answer a deep pipeline.
      */
    case Round(requested: Int, received: Int, millis: Long)

    /** One block fetched on its own (at the tip), from the request to the block. */
    case Fetched(millis: Long)

    /** The tip the peer announced with the last instruction of a batch of ChainSync replies. */
    case Announced(tip: ChainSync.Tip)

    /** The headers of one batch of ChainSync replies reached us together, at `at` on the monotonic
      * clock; `slots` are those of the roll-forwards, in order. The peer metrics
      * (`PeerMetrics.reportHeader`, the reference's `reportHeader` per header) count the peer that
      * is first with a slot. One observation per batch, not per header: the headers of a pipelined
      * batch share their arrival time, and the callback stays off the per-header path.
      */
    case HeadersArrived(slots: Vector[Long], at: Time)

    /** The blocks of one BlockFetch request reached us, each as `(slot, size in bytes)`, at `at`
      * (`reportFetch` per block in the reference).
      */
    case BlocksArrived(blocks: Vector[(Long, Long)], at: Time)

  final case class Settings(
      address: SocketAddress[Host],
      magic: Long,
      network: NodeToNode.Settings = NodeToNode.Settings(),
      /** Most ChainSync requests pipelined at once while the peer is far ahead (D3: 128, see
        * docs/performance.md; 64 to 256 were measured against the public Preview relay).
        */
      readAhead: Int = 128,
      /** Slots per Byron epoch (10k of the Byron genesis), to place Byron headers on the slot axis.
        */
      byronEpochSlots: BigInt
  )

  /** The most intersection candidates one FindIntersect carries. */
  private val MaxCandidates = 64

  /** At most [[MaxCandidates]] of `candidates` (newest first): the newest 16, then every second,
    * fourth, eighth... one, and always the last (the oldest, which the anchor is).
    */
  private[node] def thin[A](candidates: Vector[A]): Vector[A] =
    if candidates.size <= MaxCandidates then candidates
    else
      val picked = Iterator
        .unfold((0, 1)) { case (i, step) =>
          Option.when(i < candidates.size)(
            i -> ((i + step, if i >= 15 then step * 2 else step))
          )
        }
        .take(MaxCandidates - 1)
        .toVector
      (picked.map(candidates(_)) :+ candidates.last).distinct

  /** Split announced headers into runs one BlockFetch request accepts: at most 100 blocks and about
    * 8 MB of announced body (the client refuses more than 10 MB per request).
    */
  private[node] def ranges(headers: Vector[Header]): Vector[Vector[Header]] =
    val budget = 8000000L
    val out = Vector.newBuilder[Vector[Header]]
    var run = Vector.empty[Header]
    var size = 0L
    headers.foreach { h =>
      val bytes = h match
        case s: Header.Shelley => s.bodySize.toLong + s.raw.size + 512
        case _                 => 0L
      if run.nonEmpty && (run.size >= 100 || size + bytes > budget) then
        out += run
        run = Vector.empty
        size = 0L
      run :+= h
      size += bytes
    }
    if run.nonEmpty then out += run
    out.result()

  /** The maximal runs of consecutive headers the caller owns (the others were claimed by another
    * session, which fetches them).
    */
  private[node] def ownedRuns(flagged: Vector[(Header, Boolean)]): Vector[Vector[Header]] =
    val out = Vector.newBuilder[Vector[Header]]
    var run = Vector.empty[Header]
    flagged.foreach { (h, owned) =>
      if owned then run :+= h
      else if run.nonEmpty then
        out += run
        run = Vector.empty
    }
    if run.nonEmpty then out += run
    out.result()

  /** Batches the peer keeps in flight: one being applied, the others being fetched. */
  private val Depth = 3

  /** Called with each prefetched batch of raw blocks before they are handed to the rules. */
  type Prepare[F[_]] = Vector[Bytes] => F[Unit]

  /** `host:port` where host is a DNS name or an IP literal. */
  def parseAddress(text: String): Either[String, SocketAddress[Host]] =
    SocketAddress.fromString(text).toRight(s"invalid peer address '$text', expected <host>:<port>")

  /** The node-level classification of a network failure. */
  def peerError(error: NetworkError): PeerError = error match
    case _: NetworkError.Connect | _: NetworkError.Timeout | _: NetworkError.BearerClosed =>
      PeerError.Unavailable(error.message)
    case _: NetworkError.HandshakeRefused | _: NetworkError.VersionMismatch =>
      PeerError.Handshake(error.message)
    case _: NetworkError.IngressLimit | _: NetworkError.Decode | _: NetworkError.IllegalTransition |
        _: NetworkError.QueryRefused =>
      PeerError.Protocol(error.message)

  /** Node-to-node interpreter over `network`: ChainSync and BlockFetch share one multiplexed
    * connection (KeepAlive runs in the background). Every network failure surfaces as a typed
    * [[PeerError]].
    */
  def nodeToNode[F[_]: Async](
      settings: Settings,
      prepare: Prepare[F],
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      headers: Option[HeaderCheck[F]] = None,
      observe: Option[Peer.Observation => F[Unit]] = None,
      pool: Option[BlockPool[F]] = None,
      fetchMode: Option[F[FetchMode]] = None
  )(using
      peerRaise: Raise[F, PeerError]
  ): Resource[F, Peer[F]] =
    // The connection's fibers (multiplexer, KeepAlive, TxSubmission2, the pipeline) raise
    // [[PeerFailure]]s, which are valid in any fiber; opening the connection happens in the
    // caller's fiber, where they are raised again through the caller's scope.
    given Raise[F, NetworkError] = carryingNetwork[F]
    NodeToNode
      .connect[F, NtNHeader](
        settings.address,
        settings.magic,
        NtNHeader.codec,
        settings.network,
        transactions
      )
      .flatMap(over(_, settings, prepare, headers, observe, pool, fetchMode))
      .handleErrorWith {
        case failure: PeerFailure =>
          Resource.eval(peerRaise.raise[PeerError, Peer[F]](failure.error))
        case other => Resource.raiseError[F, Peer[F], Throwable](other)
      }

  /** The peer interface over a connection that is already negotiated: one we opened
    * ([[nodeToNode]]) or an inbound duplex connection on which we run our own clients (G2). The
    * pipelined producer lives as long as the resource; the connection is not closed by it.
    *
    * With `headers`, every batch of ChainSync headers is validated before the blocks of the batch
    * are fetched (G6): a header that breaks a rule raises [[PeerError.InvalidHeader]] and no block
    * of its batch is requested.
    */
  def over[F[_]: Async](
      connection: NodeToNodeConnection[F, NtNHeader],
      settings: Settings,
      prepare: Prepare[F],
      headers: Option[HeaderCheck[F]] = None,
      observe: Option[Peer.Observation => F[Unit]] = None,
      /** Where fetched blocks wait for the session that applies them; share one between the
        * sessions of several peers so that each block is fetched from one of them only. Without
        * one, the session has a pool of its own.
        */
      pool: Option[BlockPool[F]] = None,
      /** The fetch mode the node is in now (bulk sync or deadline): it decides whether a block
        * another session has claimed may be requested too ([[BlockPool]]). Bulk sync without it.
        */
      fetchMode: Option[F[FetchMode]] = None
  )(using
      peerRaise: Raise[F, PeerError]
  ): Resource[F, Controlled[F]] =
    val F = Async[F]
    val currentMode: F[FetchMode] = fetchMode.getOrElse(F.pure(FetchMode.BulkSync))
    // Fibers of the pipeline raise through `carried`; the methods of the session's own fiber
    // (intersect, next, fetch) raise again through `peerRaise`.
    val carried = carrying[F]
    def protocol[A](reason: String): F[A] = PeerError.Protocol(reason).raise
    def protocolIn[A](reason: String): F[A] = carried.raise(PeerError.Protocol(reason))

    // The session's claims on the pool come first, so they are released last: after the supervisor
    // has cancelled and awaited the fibers that fetch, nothing the session claimed is left open.
    (for
      shared <- Resource.eval(pool.fold(BlockPool.create[F])(F.pure))
      claims <- shared.session
      supervisor <- Supervisor[F](await = false)
    yield (shared, claims, supervisor)).evalMap { (shared, claims, supervisor) =>
      // Read-ahead: while the peer is far ahead, ChainSync requests are pipelined and the
      // blocks of the whole batch are fetched with one BlockFetch range. A producer fiber
      // keeps up to `Depth` batches in flight: it requests the headers of the next batch while
      // the blocks of the previous ones are still being fetched and verified, and publishes
      // the batches to `queue` in order. The consumer applies one batch while the network and
      // the (parallel) signature checks work on the following ones. `blocks` holds the
      // prefetched blocks by header hash.
      (
        Queue.unbounded[F, Either[Throwable, Vector[SyncEvent]]],
        Ref.of[F, Vector[SyncEvent]](Vector.empty),
        F.pure(shared),
        Ref.of[F, Option[(ChainSync.Tip, BigInt)]](None),
        Ref.of[F, Boolean](false),
        Semaphore[F](Depth.toLong),
        Mutex[F],
        Ref.of[F, HeaderGate[F]](HeaderGate.none[F]),
        Ref.of[F, Boolean](false),
        Deferred[F, Unit]
      ).mapN {
        (queue, current, blocks, progress, started, permits, fetchLock, gate, stopping, ended) =>
          def decode(update: ChainSyncClient.Update[NtNHeader]): F[SyncEvent] = update match
            case ChainSyncClient.Update.RollForward(h, tip) =>
              Header
                .fromNtN(h, settings.byronEpochSlots)
                .fold(e => protocolIn(e.message), h => F.pure(SyncEvent.RollForward(h, tip)))
            case ChainSyncClient.Update.RollBackward(point, tip) =>
              F.pure(SyncEvent.RollBackward(point, tip))

          /** Prefetch the blocks announced by the leading roll-forwards of a batch, in ranges the
            * client's per-request limits accept (a batch of full blocks is more than 10 MB).
            */
          def prefetch(batch: Vector[SyncEvent]): F[Unit] =
            val headers = batch.takeWhile(_.isInstanceOf[SyncEvent.RollForward]).collect {
              case SyncEvent.RollForward(h, _) => h
            }
            ranges(headers).traverse_(prefetchRange)

          /** Fetch the blocks of one range that no other session has claimed. The claim is made
            * when this connection is free to send the request, so with several peers the ranges of
            * a batch go to whichever connection is idle (the reference's bulk-sync decision keeps a
            * block in flight with one peer only).
            */
          def prefetchRange(headers: Vector[Header]): F[Unit] =
            fetchLock.lock
              .surround(
                currentMode.flatMap(claims.claim(headers.map(_.hash), _)).flatMap { owned =>
                  val runs = ownedRuns(headers.zip(owned))
                  val mine = runs.flatMap(_.map(_.hash))
                  // a failure or a cancellation anywhere in the range gives up every claim of it, not
                  // only the run in flight (issue 130); the session's own claims are the backstop
                  runs
                    .traverse(run => requestRun(run).map(run -> _))
                    .onError(_ => claims.release(mine))
                    .onCancel(claims.release(mine))
                }
              )
              .flatMap(_.traverse_ { case (run, (fetched, arrivedAt)) =>
                fetched.fold(claims.release(run.map(_.hash))) { raw =>
                  // A range answers in chain order, one block per announced header; the rules
                  // check each block against the header they were handed.
                  val paired: Vector[(Bytes, Long, Bytes)] =
                    if raw.size == run.size then
                      run.zip(raw).map((h, b) => (h.hash, h.slot.toLong, b))
                    else
                      raw.flatMap(b =>
                        Block
                          .decode(b, settings.byronEpochSlots)
                          .toOption
                          .map(block => (block.header.hash, block.header.slot.toLong, b))
                      )
                  val byHash = paired.map((hash, _, b) => hash -> b)
                  observe.traverse_(
                    _(
                      Peer.Observation
                        .BlocksArrived(paired.map((_, slot, b) => slot -> b.size.toLong), arrivedAt)
                    )
                  ) *>
                    (prepare(raw) *> claims.fill(byHash)).guarantee(claims.release(run.map(_.hash)))
                }
              })

          /** One BlockFetch request for a run, with the time its blocks arrived. */
          def requestRun(run: Vector[Header]): F[(Option[Vector[Bytes]], Time)] =
            connection.blockFetch.requestRange(run.head.point, run.last.point).flatMap { fetched =>
              F.monotonic.map(fetched -> _)
            }

          /** The next batch of ChainSync instructions. */
          val requestHeaders: F[Vector[SyncEvent]] =
            for
              seen <- progress.get
              ahead = seen.fold(BigInt(0)) { (tip, blockNo) =>
                (tip.blockNo.value - blockNo).max(0)
              }
              n = (ahead - 1).min(settings.readAhead).max(1).toInt
              began <- F.monotonic
              updates <- connection.chainSync.requestNextBatch(n)
              arrived <- F.monotonic
              took = arrived - began
              _ <- observe.traverse_(_(Peer.Observation.Round(n, updates.size, took.toMillis)))
              batch <- updates.traverse(decode)
              _ <- observe.traverse_ { report =>
                val slots = batch.collect { case SyncEvent.RollForward(h, _) => h.slot.toLong }
                report(Peer.Observation.HeadersArrived(slots, arrived)).whenA(slots.nonEmpty)
              }
              _ <- batch.lastOption.traverse_ {
                case SyncEvent.RollForward(_, tip) =>
                  observe.traverse_(_(Peer.Observation.Announced(tip)))
                case SyncEvent.RollBackward(_, tip) =>
                  observe.traverse_(_(Peer.Observation.Announced(tip)))
              }
              // header-first: nothing of this batch is fetched before its headers are valid
              _ <- gate.get.flatMap(_.accept(batch)(using carried))
              _ <- batch.lastOption.traverse_ {
                case SyncEvent.RollForward(h, tip) => progress.set(Some(tip -> h.blockNo))
                case SyncEvent.RollBackward(_, tip) =>
                  progress.set(Some(tip -> tip.blockNo.value))
              }
            yield batch

          def fail(e: Throwable): F[Unit] = queue.offer(Left(e))

          /** Request batches for ever: the blocks of each are fetched in a fiber of its own and the
            * batch is published once the previous one has been, so order is kept.
            */
          def produce(previous: Deferred[F, Unit]): F[Unit] =
            for
              _ <- permits.acquire
              halt <- stopping.get
              _ <-
                if halt then
                  // brought to rest: the batches requested so far are published, then it is idle
                  previous.get *> ended.complete(()).void
                else
                  for
                    batch <- requestHeaders
                    published <- Deferred[F, Unit]
                    _ <- supervisor.supervise(
                      (prefetch(batch) *> previous.get *> queue.offer(Right(batch)) *>
                        published.complete(()).void).handleErrorWith(fail)
                    )
                    _ <- produce(published)
                  yield ()
            yield ()

          val start: F[Unit] =
            started.getAndSet(true).flatMap {
              case true => F.unit
              case false =>
                supervisor
                  .supervise(
                    Deferred[F, Unit]
                      .flatTap(_.complete(()))
                      .flatMap(produce)
                      .handleErrorWith(fail)
                  )
                  .void
            }

          new Controlled[F]:
            def quiesce: F[Unit] =
              stopping.set(true) *> started.getAndSet(true).flatMap {
                // the producer never ran: it never will
                case false => ended.complete(()).void
                // it may wait for a permit that only a consumer returns: consume and drop
                case true =>
                  F.race(ended.get, pull.foreverM[Unit])
                    .void
                    .handleError(_ => ())
              }

            def intersect(candidates: Vector[ChainSync.Point]) = landed(
              current.set(Vector.empty) *> progress.set(None) *>
                connection.chainSync.findIntersect(thin(candidates)).flatMap { found =>
                  found.traverse_ { case (point, _) =>
                    headers.traverse_(_.start(point).flatMap(gate.set))
                  } *> F.pure(found.map(_._1))
                }
            )

            def next: F[SyncEvent] = landed(pull)

            private def pull: F[SyncEvent] =
              start *> current.modify(q => (q.drop(1), q.headOption)).flatMap {
                case Some(event) => F.pure(event)
                case None =>
                  queue.take.flatMap {
                    // The producer fibers run in the background; the failure they caught (a
                    // [[PeerFailure]], or a defect) is handed over unchanged to whoever calls `next`,
                    // which raises it in its own fiber.
                    case Left(error)  => F.raiseError[SyncEvent](error)
                    case Right(batch) => permits.release *> current.set(batch) *> pull
                  }
              }

            def fetch(header: Header): F[Bytes] = landed(fetchBlock(header))

            private def fetchBlock(header: Header): F[Bytes] =
              blocks.take(header.hash).flatMap {
                case Some(block) => F.pure(block)
                case None =>
                  CardanoBlockFetch.SpecificPoint.from(header.point) match
                    case Left(reason) => protocol(reason)
                    case Right(specific) =>
                      fetchLock.lock
                        .surround(
                          for
                            began <- F.monotonic
                            fetched <- connection.blockFetch
                              .requestRange(specific.point, specific.point)
                            arrived <- F.monotonic
                            took = arrived - began
                            _ <- observe.traverse_(_(Peer.Observation.Fetched(took.toMillis)))
                            _ <- observe.traverse_(report =>
                              fetched.traverse_(
                                _.traverse_(b =>
                                  report(
                                    Peer.Observation.BlocksArrived(
                                      Vector(header.slot.toLong -> b.size.toLong),
                                      arrived
                                    )
                                  )
                                )
                              )
                            )
                          yield fetched
                        )
                        .flatMap {
                          case Some(Vector(block)) => F.pure(block)
                          case Some(other) =>
                            protocol(s"expected one block, received ${other.size}")
                          case None =>
                            PeerError.Unavailable("peer does not have the announced block").raise
                        }
              }
      }
    }
