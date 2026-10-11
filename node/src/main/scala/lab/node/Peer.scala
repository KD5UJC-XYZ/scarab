// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import lab.cbor.Bytes
import lab.network.{CardanoBlockFetch, ChainSync, NtNHeader}
import lab.network.runtime.{ChainSyncClient, NetworkError, NodeToNode}

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
  final case class Settings(
      address: SocketAddress[Host],
      magic: Long,
      network: NodeToNode.Settings = NodeToNode.Settings(),
      /** Most ChainSync requests pipelined at once while the peer is far ahead. */
      readAhead: Int = 64,
      /** Slots per Byron epoch (10k of the Byron genesis), to place Byron headers on the slot axis.
        */
      byronEpochSlots: BigInt = Header.DefaultByronEpochSlots
  )

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
    case _: NetworkError.IngressLimit | _: NetworkError.Decode |
        _: NetworkError.IllegalTransition =>
      PeerError.Protocol(error.message)

  /** Node-to-node interpreter over `network-runtime`: ChainSync and BlockFetch share one
    * multiplexed connection (KeepAlive runs in the background). Every network failure surfaces as a
    * typed [[PeerError]].
    */
  def nodeToNode[F[_]: Async](
      settings: Settings,
      prepare: Prepare[F]
  )(using
      peerRaise: Raise[F, PeerError]
  ): Resource[F, Peer[F]] =
    val F = Async[F]
    // Contravariance cannot lift a Raise over a different error type, so translate explicitly.
    given Raise[F, NetworkError] = new Raise[F, NetworkError]:
      def functor = F
      def raise[E2 <: NetworkError, A](e: E2): F[A] = peerRaise.raise(peerError(e))
    def protocol[A](reason: String): F[A] = PeerError.Protocol(reason).raise

    Supervisor[F](await = false).flatMap { supervisor =>
      NodeToNode
        .connect[F, NtNHeader](
          settings.address,
          settings.magic,
          NtNHeader.codec,
          settings.network
        )
        .evalMap { connection =>
          // Read-ahead: while the peer is far ahead, ChainSync requests are pipelined and the
          // blocks of the whole batch are fetched with one BlockFetch range. `events` holds
          // announced but unconsumed instructions, `blocks` the prefetched blocks by header hash.
          // The next batch is requested in the background while the current one is applied, so the
          // network and the verification of the blocks overlap with the (sequential) rules.
          (
            Ref.of[F, Vector[SyncEvent]](Vector.empty),
            Ref.of[F, Map[Bytes, Bytes]](Map.empty),
            Ref.of[F, Option[(ChainSync.Tip, BigInt)]](None),
            Ref.of[F, Option[Throwable]](None),
            Ref.of[F, Boolean](false),
            Mutex[F],
            Mutex[F]
          ).mapN { (events, blocks, progress, failure, running, refillLock, fetchLock) =>
            def decode(update: ChainSyncClient.Update[NtNHeader]): F[SyncEvent] = update match
              case ChainSyncClient.Update.RollForward(h, tip) =>
                Header
                  .fromNtN(h, settings.byronEpochSlots)
                  .fold(e => protocol(e.message), h => F.pure(SyncEvent.RollForward(h, tip)))
              case ChainSyncClient.Update.RollBackward(point, tip) =>
                F.pure(SyncEvent.RollBackward(point, tip))

            /** Prefetch the blocks announced by the leading roll-forwards of a batch. */
            def prefetch(batch: Vector[SyncEvent]): F[Unit] =
              val headers = batch.takeWhile(_.isInstanceOf[SyncEvent.RollForward]).collect {
                case SyncEvent.RollForward(h, _) => h
              }
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

            def refill: F[Unit] =
              for
                seen <- progress.get
                ahead = seen.fold(BigInt(0)) { (tip, blockNo) =>
                  (tip.blockNo.value - blockNo).max(0)
                }
                n = (ahead - 1).min(settings.readAhead).max(1).toInt
                updates <- connection.chainSync.requestNextBatch(n)
                batch <- updates.traverse(decode)
                _ <- batch.lastOption.traverse_ {
                  case SyncEvent.RollForward(h, tip) => progress.set(Some(tip -> h.blockNo))
                  case SyncEvent.RollBackward(_, tip) =>
                    progress.set(Some(tip -> tip.blockNo.value))
                }
                // Blocks first: a header is visible to the consumer only once its block is.
                _ <- prefetch(batch)
                _ <- events.update(_ ++ batch)
              yield ()

            /** Refill now, unless another refill already brought something. */
            val refillIfEmpty: F[Unit] =
              refillLock.lock.surround(
                events.get.flatMap(q => if q.isEmpty then refill else F.unit)
              )

            /** Refill in the background; a failure is handed to the next call of `next`. */
            val refillSoon: F[Unit] =
              running.getAndSet(true).flatMap {
                case true => F.unit
                case false =>
                  supervisor
                    .supervise(
                      refillLock.lock
                        .surround(refill)
                        .attempt
                        .flatMap(_.fold(e => failure.set(Some(e)), _ => F.unit))
                        .guarantee(running.set(false))
                    )
                    .void
              }

            new Peer[F]:
              def intersect(candidates: Vector[ChainSync.Point]) =
                events.set(Vector.empty) *> blocks.set(Map.empty) *> progress.set(None) *>
                  connection.chainSync.findIntersect(candidates).flatMap { case found =>
                    F.pure(found.map(_._1))
                  }

              def next: F[SyncEvent] =
                failure.get.flatMap {
                  case Some(e) => F.raiseError[SyncEvent](e)
                  case None =>
                    events.modify(q => (q.drop(1), (q.headOption, q.size))).flatMap {
                      case (Some(event), size) =>
                        F.whenA(size - 1 <= settings.readAhead / 2)(refillSoon).as(event)
                      case (None, _) => refillIfEmpty *> next
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
                            connection.blockFetch.requestRange(specific.point, specific.point)
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
    }
