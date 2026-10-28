// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.{NetworkError, ProtocolError, TxSubmission2Engine}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role

/** Shared TxSubmission2 client/server drivers. Production Conway and upstream `Any` adapters use
  * the same request, acknowledgement, and body-fetch loops; wire profiles remain independent.
  */
private[network] object TxSubmission2Runtime:
  type State = TxSubmission2Engine.State
  val State = TxSubmission2Engine.State
  type Message[I, B] = TxSubmission2Engine.Message[I, B]
  type Codec[I, B] = TxSubmission2Engine.Codec[I, B]
  type RuleError = NetworkError.Violation | TxSubmissionPolicy.Violation

  final case class Held[I](id: I, bytes: Int, ticket: Long)

  trait Source[F[_], I, B]:
    def after(ticket: Long, max: Int): F[Vector[Held[I]]]
    def awaitAfter(ticket: Long): F[Unit]
    def body(id: I): F[Option[B]]

  trait Sink[F[_], I, B]:
    def known(id: I): F[Boolean]
    def submit(originals: Vector[B])(using Raise[F, NetworkError]): F[Unit]

  trait Rules[I]:
    def acknowledge(
        unacked: Vector[I],
        blocking: Boolean,
        ack: Int,
        request: Int
    ): Either[RuleError, Vector[I]]
    def bodies(unacked: Vector[I], ids: Vector[I]): Either[RuleError, Unit]
    def offers(ids: Vector[I], requested: Int): Either[RuleError, Unit]

  final case class ServerSettings(
      idsPerRequest: Int = 3,
      maxTxToRequest: Int = 8,
      maxUnacked: Int = TxSubmissionPolicy.MaxUnacknowledged
  )

  def validateServerSettings(
      settings: ServerSettings,
      codec: Codec[?, ?]
  ): Either[ProtocolError, Unit] =
    if settings.idsPerRequest <= 0 || settings.idsPerRequest > 65535 ||
      settings.maxTxToRequest <= 0 || settings.maxTxToRequest > 65535 ||
      settings.maxUnacked <= 0 || settings.maxUnacked > 65535 || codec.maxBatch <= 0
    then Left(ProtocolError.InvalidTxSubmission2Limits)
    else Right(())

  private final case class Window[I](unacked: Vector[I], cursor: Long)

  def client[F[_]: Temporal, I, B](
      channel: MuxChannel[F],
      source: Source[F, I, B],
      codec: Codec[I, B],
      rules: Rules[I],
      finish: Option[F[Unit]],
      unexpected: Message[I, B] => NetworkError.Violation
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val protocol = Protocols.TxSubmission2
    def illegal[A](detail: RuleError): F[A] = detail match
      case violation: TxSubmissionPolicy.Violation =>
        NetworkError.TxSubmission(violation).raise[F, A]
      case violation: NetworkError.Violation =>
        NetworkError.IllegalTransition(protocol, violation).raise[F, A]
    def send(state: State, message: Message[I, B]): F[Unit] =
      codec
        .encode(state, Role.Client, message)
        .fold(
          error => illegal(error),
          channel.send
        )
    def receive(state: State): F[Message[I, B]] =
      TxSubmissionPolicy
        .timeLimit(state)
        .fold(
          channel.recv(codec.maxMessageBytes(state))(codec.decodePrefix(state, Role.Server, _))
        )(duration =>
          Timed.within(duration, protocol, state.toString)(
            channel.recv(codec.maxMessageBytes(state))(codec.decodePrefix(state, Role.Server, _))
          )
        )
    def offers(held: Vector[Held[I]]): F[Vector[TxSubmission2Engine.Offer[I]]] =
      held.traverse(h =>
        codec
          .advertisedSize(h.bytes)
          .fold(
            illegal,
            size =>
              F.pure(
                TxSubmission2Engine.Offer(h.id, size)
              )
          )
      )
    def answerIds(
        window: Window[I],
        blocking: Boolean,
        ack: Int,
        request: Int
    ): F[Option[Window[I]]] =
      val limit = request.min(codec.maxBatch)
      rules
        .acknowledge(window.unacked, blocking, ack, request)
        .fold(error => illegal(error), F.pure)
        .flatMap { retained =>
          val fresh: F[Option[Vector[Held[I]]]] =
            if blocking then
              finish
                .fold(source.awaitAfter(window.cursor).map(Right(_)))(stop =>
                  F.race(stop, source.awaitAfter(window.cursor))
                )
                .flatMap {
                  case Left(_)  => F.pure(None)
                  case Right(_) => source.after(window.cursor, limit).map(Some(_))
                }
            else source.after(window.cursor, limit).map(Some(_))
          fresh.flatMap {
            case None =>
              send(State.Ids(true), TxSubmission2Engine.Message.Done).as(None)
            case Some(held) if blocking && held.isEmpty =>
              answerIds(window, blocking, ack, request)
            case Some(held) =>
              offers(held).flatMap { encoded =>
                send(State.Ids(blocking), TxSubmission2Engine.Message.ReplyIds(encoded)).as(
                  Some(
                    Window(
                      retained ++ held.map(_.id),
                      held.lastOption.fold(window.cursor)(_.ticket)
                    )
                  )
                )
              }
          }
        }
    def answerTxs(window: Window[I], ids: Vector[I]): F[Unit] =
      rules.bodies(window.unacked, ids).fold(illegal, F.pure) *>
        ids
          .flatTraverse(id => source.body(id).map(_.toVector))
          .flatMap(bodies => send(State.Txs, TxSubmission2Engine.Message.ReplyTxs(bodies)))
    def loop(window: Window[I]): F[Unit] =
      receive(State.Idle).flatMap {
        case TxSubmission2Engine.Message.RequestIds(blocking, ack, request) =>
          answerIds(window, blocking, ack, request).flatMap(_.fold(F.unit)(loop))
        case TxSubmission2Engine.Message.RequestTxs(ids) => answerTxs(window, ids) *> loop(window)
        case other                                       => illegal(unexpected(other))
      }
    send(State.Init, TxSubmission2Engine.Message.Init) *> loop(Window(Vector.empty, 0L))

  def server[F[_]: Temporal, I, B](
      channel: MuxChannel[F],
      sink: Sink[F, I, B],
      codec: Codec[I, B],
      rules: Rules[I],
      settings: ServerSettings,
      unexpected: Message[I, B] => NetworkError.Violation
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val protocol = Protocols.TxSubmission2
    def illegal[A](detail: RuleError): F[A] = detail match
      case violation: TxSubmissionPolicy.Violation =>
        NetworkError.TxSubmission(violation).raise[F, A]
      case violation: NetworkError.Violation =>
        NetworkError.IllegalTransition(protocol, violation).raise[F, A]
    def send(state: State, message: Message[I, B]): F[Unit] =
      codec.encode(state, Role.Server, message).fold(error => illegal(error), channel.send)
    def receive(state: State): F[Message[I, B]] =
      val read =
        channel.recv(codec.maxMessageBytes(state))(codec.decodePrefix(state, Role.Client, _))
      TxSubmissionPolicy
        .timeLimit(state)
        .fold(read)(duration => Timed.within(duration, protocol, state.toString)(read))
    def fetch(wanted: Vector[I]): F[Unit] =
      if wanted.isEmpty then F.unit
      else
        wanted.grouped(settings.maxTxToRequest.min(codec.maxBatch)).toVector.traverse_ { group =>
          send(State.Idle, TxSubmission2Engine.Message.RequestTxs(group)) *>
            receive(State.Txs).flatMap {
              case TxSubmission2Engine.Message.ReplyTxs(originals) =>
                originals
                  .traverse(original =>
                    codec
                      .bodyId(original)
                      .fold(
                        error => illegal(error),
                        id =>
                          if group.contains(id) then F.pure(original)
                          else illegal(NetworkError.RuntimeViolation.TransactionNotRequested)
                      )
                  )
                  .flatMap(sink.submit)
              case other => illegal(unexpected(other))
            }
        }
    def loop(ack: Int): F[Unit] =
      // Request counts are UInt16 and independent of reply-list batch ceilings.
      val request = settings.idsPerRequest.min(settings.maxUnacked)
      send(State.Idle, TxSubmission2Engine.Message.RequestIds(true, ack, request)) *>
        receive(State.Ids(true)).flatMap {
          case TxSubmission2Engine.Message.ReplyIds(offers) =>
            rules.offers(offers.map(_.transactionId), request).fold(illegal, F.pure) *>
              offers
                .filter(_.advertisedSize <= codec.maxAdvertisedSize)
                .traverseFilter(offer =>
                  sink
                    .known(offer.transactionId)
                    .map(known => Option.unless(known)(offer.transactionId))
                )
                .flatMap(fetch) *> loop(offers.size)
          case TxSubmission2Engine.Message.Done => F.unit
          case other                            => illegal(unexpected(other))
        }
    validateServerSettings(settings, codec).fold(
      error => illegal(error),
      _ =>
        receive(State.Init).flatMap {
          case TxSubmission2Engine.Message.Init => loop(0)
          case other                            => illegal(unexpected(other))
        }
    )
