// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{Async, Ref, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.*
import scala.concurrent.duration.*

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
      address: NumericPeer,
      magic: Long,
      readTimeout: FiniteDuration = 120.seconds,
      awaitTimeout: FiniteDuration = 110.seconds
  )

  /** Node-to-node v14 interpreter. ChainSync owns one connection for the resource lifetime; each
    * fetch uses a short-lived BlockFetch connection. Every transport or protocol failure surfaces
    * as a typed [[PeerError]].
    */
  def nodeToNode[F[_]: Async](settings: Settings)(using Raise[F, PeerError]): Resource[F, Peer[F]] =
    val F = Async[F]
    def lift[A](label: String)(fa: F[A]): F[A] =
      fa.handleErrorWith(e =>
        PeerError.Unavailable(s"$label: ${Option(e.getMessage).getOrElse(e.toString)}").raise
      )
    def protocol[A](reason: String): F[A] = PeerError.Protocol(reason).raise

    val transport: Resource[F, ByteTransport[F]] =
      Resource
        .eval(
          TcpLimits
            .checked(read = settings.readTimeout)
            .fold(r => PeerError.Unavailable(r).raise[F, TcpLimits], F.pure)
        )
        .flatMap(limits => AsyncTcpTransport.resource[F](settings.address, limits))

    val deadlines =
      SessionDeadlines[F](
        10.seconds,
        settings.readTimeout,
        10.seconds,
        F.pure(Some(settings.awaitTimeout))
      )

    val chainSync = transport
      .flatMap(t =>
        ConnectionSession.resource[F, ChainSyncFixtures.OpaqueNtNHeaderFixture](
          t,
          ConnectionSession.NtN14,
          ChainSync.Role.Client,
          deadlines,
          ConnectionSession
            .Config(maxSduPayload = 65535, maxChunkBytes = 65543, maxIngressBytes = 1048576)
        )
      )
      .evalTap(s =>
        lift("handshake")(s.negotiate(Handshake.defaultNodeToNode(settings.magic))).flatMap {
          case Handshake.Result.Negotiated(14, data) if data == Handshake.Data(settings.magic) =>
            F.unit
          case other => PeerError.Handshake(s"unexpected negotiation $other").raise
        }
      )

    def fetchOne(header: Header): F[Bytes] =
      val target = CardanoBlockFetch.SpecificPoint
        .from(header.point)
        .fold(r => protocol[CardanoBlockFetch.SpecificPoint](r), F.pure)
      target.flatMap { specific =>
        lift("block-fetch")(
          transport
            .flatMap(t => BlockFetchSession.resource[F](t, BlockFetchSession.Config()))
            .use { session =>
              for
                _ <- session.negotiate(Handshake.Data(settings.magic))
                _ <- session.request(CardanoBlockFetch.InclusiveRange.single(specific))
                start <- session.receive
                _ <- F.raiseUnless(start == BlockFetch.Message.StartBatch)(
                  new IllegalStateException("peer did not start the batch")
                )
                block <- session.receive.flatMap {
                  case BlockFetch.Message.Block(b) => F.pure(b.bytes)
                  case other =>
                    F.raiseError(new IllegalStateException(s"expected block, got $other"))
                }
                done <- session.receive
                _ <- F.raiseUnless(done == BlockFetch.Message.BatchDone)(
                  new IllegalStateException("batch did not end after one block")
                )
                _ <- session.finish
              yield block
            }
        )
      }

    chainSync.attempt.evalMap {
      case Left(e) => PeerError.Unavailable(s"chain-sync: ${e.getMessage}").raise[F, Peer[F]]
      case Right(session) =>
        Ref.of[F, Boolean](false).map { awaiting =>
          new Peer[F]:
            def intersect(candidates: Vector[ChainSync.Point]) =
              lift("intersect")(
                session.send(ChainSync.Message.FindIntersect(candidates)) *> session.receive
              ).flatMap {
                case ChainSync.Message.IntersectFound(point, _) if candidates.contains(point) =>
                  F.pure(Some(point))
                case ChainSync.Message.IntersectFound(point, _) =>
                  protocol(s"intersection ${Points.show(point)} was not offered")
                case ChainSync.Message.IntersectNotFound(_) => F.pure(None)
                case other => protocol(s"unexpected intersect reply $other")
              }

            def next: F[SyncEvent] =
              awaiting.get
                .flatMap(waiting =>
                  if waiting then F.unit
                  else lift("request")(session.send(ChainSync.Message.RequestNext))
                )
                .flatMap(_ => lift("receive")(session.receive))
                .flatMap {
                  case ChainSync.Message.AwaitReply => awaiting.set(true) *> next
                  case ChainSync.Message.RollForward(h, tip) =>
                    awaiting.set(false) *>
                      Header
                        .fromEnvelope(h.bytes)
                        .fold(e => protocol(e.message), h => F.pure(SyncEvent.RollForward(h, tip)))
                  case ChainSync.Message.RollBackward(point, tip) =>
                    awaiting.set(false).as(SyncEvent.RollBackward(point, tip))
                  case other => protocol(s"unexpected ChainSync message $other")
                }

            def fetch(header: Header) = fetchOne(header)
        }
    }
