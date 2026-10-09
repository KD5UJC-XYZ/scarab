// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.syntax.all.*
import cats.effect.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

final case class RelayLimits(
    maxTransactions: Int = 8,
    maxOriginalBytes: Int = 524288,
    maxLifetime: FiniteDuration = 30.seconds
):
  def valid: Boolean = maxTransactions > 0 && maxTransactions <= 8 &&
    maxOriginalBytes > 0 && maxOriginalBytes <= 524288 &&
    maxLifetime > Duration.Zero && maxLifetime <= 30.seconds

/** The app must enforce at most two concurrent leases globally and current-generation eligibility.
  * A resource finalizer releases its pin; bytes must remain immutable until that finalizer runs.
  */
trait RelaySource[F[_]]:
  def acquireBatch(limits: RelayLimits): Resource[F, RelayLease[F]]
trait RelayLease[F[_]]:
  def offers: Vector[RelayOffer]
  def original(transactionId: Bytes): F[Option[Bytes]]

object TxSubmission2Session:
  enum Event:
    case Announced(ids: Vector[Bytes])
    case BodiesWritten(ids: Vector[Bytes])
    case Acknowledged(ids: Vector[Bytes])
    case Finished
  final case class Report(events: Vector[Event], requests: Int)
  final case class Config(
      lease: RelayLimits = RelayLimits(),
      handshake: FiniteDuration = 5.seconds,
      operation: FiniteDuration = 5.seconds,
      maxRequests: Int = 64,
      maxOutgoingBytes: Long = 600000,
      maxOutgoingFrames: Int = 256
  ):
    def valid: Boolean = lease.valid && handshake > Duration.Zero && handshake <= 10.seconds &&
      operation > Duration.Zero && operation <= 5.seconds && maxRequests > 0 && maxRequests <= 64 &&
      maxOutgoingBytes > 0 && maxOutgoingBytes <= 1048576 && maxOutgoingFrames > 0 && maxOutgoingFrames <= 256

  /** Owns transport close even if configuration/admission fails. All outgoing bytes include mux
    * headers and handshake. One reader, no socket dialing, discovery, signing or ledger access.
    */
  def resource[F[_]: Async](
      transport: ByteTransport[F],
      config: Config = Config()
  ): Resource[F, TxSubmission2Session[F]] =
    val F = Async[F]
    Resource
      .eval((Ref.of[F, Boolean](false), Deferred[F, Either[Throwable, Unit]]).tupled)
      .flatMap { case (closed, closeResult) =>
        val closePhysical = F.uncancelable { _ =>
          closed.getAndSet(true).flatMap { wasClosed =>
            if wasClosed then closeResult.get.flatMap(F.fromEither)
            else transport.close.attempt.flatTap(closeResult.complete(_).void).flatMap(F.fromEither)
          }
        }
        Resource.make(F.unit)(_ => closePhysical).flatMap { _ =>
          Resource.eval(Ref.of[F, (Long, Int)]((0L, 0))).flatMap { totals =>
            val bounded = new ByteTransport[F]:
              def read = transport.read
              def close = closePhysical
              def isClosed = closed.get
              def write(bytes: Bytes) = totals
                .modify { case (n, frames) =>
                  if bytes.size.toLong > config.maxOutgoingBytes - n || frames >= config.maxOutgoingFrames
                  then ((n, frames), false)
                  else ((n + bytes.size, frames + 1), true)
                }
                .flatMap(ok =>
                  F.raiseUnless(ok)(
                    new IllegalStateException("outgoing relay budget exceeded")
                  ) *> transport.write(bytes)
                )
            val driver =
              new SingleProtocolConnection.Driver[F, TxSubmission2.State, TxSubmission2.Message]:
                def suite = Handshake.Suite.NodeToNode
                def version = 14
                def protocol = TxSubmission2.MiniProtocolId
                def initial = TxSubmission2.State.Init
                def handshake = config.handshake
                def valid = config.valid
                def duration(state: TxSubmission2.State) =
                  F.pure(if state == TxSubmission2.State.Done then None else Some(config.operation))
                def messageLimit(state: TxSubmission2.State) =
                  if state == TxSubmission2.State.Idle || state == TxSubmission2.State.Init then
                    65535
                  else TxSubmission2.MaxMessageBytes
                def transition(
                    state: TxSubmission2.State,
                    sender: ChainSync.Role,
                    message: TxSubmission2.Message
                ) = TxSubmission2.transition(state, sender, message)
                def encode(
                    state: TxSubmission2.State,
                    sender: ChainSync.Role,
                    message: TxSubmission2.Message
                ) = TxSubmission2.encode(state, sender, message)
                def decode(state: TxSubmission2.State, sender: ChainSync.Role, bytes: Bytes) =
                  TxSubmission2.decodePrefix(state, sender, bytes)
            SingleProtocolConnection
              .resource(
                bounded,
                driver,
                ChainSync.Role.Client,
                SingleProtocolConnection.Config(
                  65535,
                  65543,
                  131086,
                  128,
                  32,
                  131072,
                  256,
                  Some(config.lease.maxLifetime)
                )
              )
              .flatMap(owner =>
                Resource
                  .eval(Ref.of[F, Boolean](false))
                  .map(new TxSubmission2Session(owner, config, _))
              )
          }
        }
      }

/** One lease and one run per connection. Missing/replaced source data fails before advertisement.
  * Acknowledgements are transport accounting only. Neither success nor any event is acceptance.
  */
final class TxSubmission2Session[F[_]: Async] private (
    owner: SingleProtocolConnection[F, TxSubmission2.State, TxSubmission2.Message],
    config: TxSubmission2Session.Config,
    started: Ref[F, Boolean]
):
  import TxSubmission2Session.*
  import TxSubmission2.Message
  private val F = Async[F]
  private def checked[A](result: Either[String, A]): F[A] =
    F.fromEither(result.leftMap(new IllegalArgumentException(_)))
  private def bounded[A](action: F[A]): F[A] = F.timeoutTo(
    action,
    config.operation,
    F.raiseError(new IllegalStateException("relay operation deadline expired"))
  )
  def close: F[Unit] = owner.close

  def run(data: Handshake.Data, source: RelaySource[F]): F[Report] = run(data, source, _ => F.unit)

  /** Callback records bounded metadata, never originals. Its time is included in session limits. */
  def run(data: Handshake.Data, source: RelaySource[F], onEvent: Event => F[Unit]): F[Report] =
    started.getAndSet(true).flatMap { used =>
      F.raiseWhen(used)(new IllegalStateException("relay session already used")) *>
        F.timeoutTo(
          source.acquireBatch(config.lease).use { lease =>
            for
              offers <- F.delay(lease.offers)
              _ <- F.raiseWhen(offers.size > config.lease.maxTransactions)(
                new IllegalArgumentException("lease inventory limit exceeded")
              )
              initial <- checked(TxSubmission2.Inventory.create(offers))
              originals <- offers.foldLeftM((Map.empty[Bytes, Bytes], 0)) {
                case ((saved, total), offer) =>
                  bounded(lease.original(offer.transactionId)).flatMap {
                    case None =>
                      F.raiseError(new IllegalStateException("lease original unavailable"))
                    case Some(bytes) =>
                      for
                        _ <- F.raiseWhen(bytes.size > config.lease.maxOriginalBytes - total)(
                          new IllegalArgumentException("lease byte limit exceeded")
                        )
                        id <- checked(TxSubmission2.bodyId(bytes))
                        size <- checked(TxSubmission2.advertisedSize(bytes.size))
                        _ <- F.raiseWhen(id != offer.transactionId || size != offer.advertisedSize)(
                          new IllegalArgumentException("lease identity or advertised size mismatch")
                        )
                      yield (saved.updated(id, bytes), total + bytes.size)
                  }
              }
              _ <- F.raiseWhen(data.query)(
                new IllegalArgumentException("query is not application negotiation")
              )
              negotiated <- owner.negotiate(Vector(14 -> data))
              _ <- F.raiseUnless(negotiated == Handshake.Result.Negotiated(14, data))(
                new IllegalStateException("strict NtN14 descriptor mismatch")
              )
              _ <- owner.send(Message.Init)
              result <- serve(initial, originals._1, 0, Vector.empty, onEvent)
            yield result
          },
          config.lease.maxLifetime,
          F.raiseError(new SingleProtocolConnection.WholeDeadlineExceeded)
        ).guarantee(owner.close)
    }

  private def serve(
      inventory: TxSubmission2.Inventory,
      originals: Map[Bytes, Bytes],
      count: Int,
      events: Vector[Event],
      onEvent: Event => F[Unit]
  ): F[Report] =
    def emit(event: Event, previous: Vector[Event]): F[Vector[Event]] =
      bounded(onEvent(event)).as((previous :+ event).takeRight(128))
    F.raiseWhen(count >= config.maxRequests)(
      new IllegalStateException("relay request limit exceeded")
    ) *>
      owner.receive.flatMap {
        case Message.RequestIds(blocking, ack, request) =>
          checked(inventory.requestIds(blocking, ack, request)).flatMap {
            case (next, reply, acknowledged) =>
              for
                afterAck <-
                  if acknowledged.isEmpty then F.pure(events)
                  else emit(Event.Acknowledged(acknowledged), events)
                _ <- owner.send(reply)
                report <- reply match
                  case Message.Done =>
                    owner.requireDrained *> emit(Event.Finished, afterAck).map(Report(_, count + 1))
                  case Message.ReplyIds(offers) =>
                    emit(Event.Announced(offers.map(_.transactionId)), afterAck).flatMap(es =>
                      serve(next, originals, count + 1, es, onEvent)
                    )
                  case _ => F.raiseError(new IllegalStateException("invalid inventory response"))
              yield report
          }
        case Message.RequestTxs(ids) =>
          for
            next <- checked(inventory.requestTxs(ids))
            _ <- owner.send(Message.ReplyTxs(ids.map(originals)))
            es <- emit(Event.BodiesWritten(ids), events)
            report <- serve(next, originals, count + 1, es, onEvent)
          yield report
        case _ => F.raiseError(new IllegalStateException("unexpected publisher request"))
      }
