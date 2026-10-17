// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{Block, Header, PeerError}
import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.{Mutex, Queue, Semaphore, Supervisor}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{CardanoBlockFetch, ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.network.{
  ChainSyncClient,
  NetworkError,
  NodeToNode,
  NodeToNodeConnection,
  TxSubmissionClient
}

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
  /** What a session measured of its peer, for the log. */
  enum Observation:
    /** One pipelined ChainSync round: `requested` RequestNext sent at once, `received` instructions
      * back after `millis` (from the first request to the last reply). D3 uses it to see how long a
      * relay takes to answer a deep pipeline.
      */
    case Round(requested: Int, received: Int, millis: Long)

    /** One block fetched on its own (at the tip), from the request to the block. */
    case Fetched(millis: Long)

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
      observe: Option[Peer.Observation => F[Unit]] = None
  )(using
      peerRaise: Raise[F, PeerError]
  ): Resource[F, Peer[F]] =
    val F = Async[F]
    // Contravariance cannot lift a Raise over a different error type, so translate explicitly.
    given Raise[F, NetworkError] = new Raise[F, NetworkError]:
      def functor = F
      def raise[E2 <: NetworkError, A](e: E2): F[A] = peerRaise.raise(peerError(e))
    NodeToNode
      .connect[F, NtNHeader](
        settings.address,
        settings.magic,
        NtNHeader.codec,
        settings.network,
        transactions
      )
      .flatMap(over(_, settings, prepare, headers, observe))

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
      observe: Option[Peer.Observation => F[Unit]] = None
  )(using
      peerRaise: Raise[F, PeerError]
  ): Resource[F, Peer[F]] =
    val F = Async[F]
    def protocol[A](reason: String): F[A] = PeerError.Protocol(reason).raise

    Supervisor[F](await = false).evalMap { supervisor =>
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
        Ref.of[F, Map[Bytes, Bytes]](Map.empty),
        Ref.of[F, Option[(ChainSync.Tip, BigInt)]](None),
        Ref.of[F, Boolean](false),
        Semaphore[F](Depth.toLong),
        Mutex[F],
        Ref.of[F, HeaderGate[F]](HeaderGate.none[F])
      ).mapN { (queue, current, blocks, progress, started, permits, fetchLock, gate) =>
        def decode(update: ChainSyncClient.Update[NtNHeader]): F[SyncEvent] = update match
          case ChainSyncClient.Update.RollForward(h, tip) =>
            Header
              .fromNtN(h, settings.byronEpochSlots)
              .fold(e => protocol(e.message), h => F.pure(SyncEvent.RollForward(h, tip)))
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

        def prefetchRange(headers: Vector[Header]): F[Unit] =
          if headers.size < 2 then F.unit
          else
            fetchLock.lock
              .surround(
                connection.blockFetch.requestRange(headers.head.point, headers.last.point)
              )
              .flatMap {
                case Some(raw) =>
                  // A range answers in chain order, one block per announced header; the rules
                  // check each block against the header they were handed.
                  val byHash =
                    if raw.size == headers.size then headers.map(_.hash).zip(raw)
                    else
                      raw.flatMap(b =>
                        Block
                          .decode(b, settings.byronEpochSlots)
                          .toOption
                          .map(_.header.hash -> b)
                      )
                  prepare(raw) *> blocks.update(_ ++ byHash)
                case None => F.unit
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
            took <- F.monotonic.map(_ - began)
            _ <- observe.traverse_(_(Peer.Observation.Round(n, updates.size, took.toMillis)))
            batch <- updates.traverse(decode)
            // header-first: nothing of this batch is fetched before its headers are valid
            _ <- gate.get.flatMap(_.accept(batch))
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
            batch <- requestHeaders
            published <- Deferred[F, Unit]
            _ <- supervisor.supervise(
              (prefetch(batch) *> previous.get *> queue.offer(Right(batch)) *>
                published.complete(()).void).handleErrorWith(fail)
            )
            _ <- produce(published)
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

        new Peer[F]:
          def intersect(candidates: Vector[ChainSync.Point]) =
            current.set(Vector.empty) *> blocks.set(Map.empty) *> progress.set(None) *>
              connection.chainSync.findIntersect(thin(candidates)).flatMap { found =>
                found.traverse_ { case (point, _) =>
                  headers.traverse_(_.start(point).flatMap(gate.set))
                } *> F.pure(found.map(_._1))
              }

          def next: F[SyncEvent] =
            start *> current.modify(q => (q.drop(1), q.headOption)).flatMap {
              case Some(event) => F.pure(event)
              case None =>
                queue.take.flatMap {
                  // The producer fibers run in the background; the failure they caught
                  // (a typed PeerError carried by the Raise interpreter, or a defect) is
                  // handed over unchanged to whoever calls `next`, so re-raise it as is.
                  case Left(error)  => F.raiseError[SyncEvent](error)
                  case Right(batch) => permits.release *> current.set(batch) *> next
                }
            }

          def fetch(header: Header): F[Bytes] =
            blocks.modify(m => (m - header.hash, m.get(header.hash))).flatMap {
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
                          took <- F.monotonic.map(_ - began)
                          _ <- observe.traverse_(_(Peer.Observation.Fetched(took.toMillis)))
                        yield fetched
                      )
                      .flatMap {
                        case Some(Vector(block)) => F.pure(block)
                        case Some(other) =>
                          protocol(s"expected one block, received ${other.size}")
                        case None => protocol("peer does not have the announced block")
                      }
            }
      }
    }
