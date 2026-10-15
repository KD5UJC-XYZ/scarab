// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Ref, Temporal}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{RelayOffer, TxSubmission2}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import scala.concurrent.duration.*

/** TxSubmission2 as the side that owns transactions (the protocol's client, which is the initiator
  * of the outbound connection): it offers the transactions of a [[Source]] and hands out bodies on
  * request. The server drives; this side only answers.
  *
  * Each transaction is offered to a peer once. `ticket` numbers increase with arrival, so the
  * per-peer cursor is the highest ticket already offered.
  */
object TxSubmissionClient:
  final case class Held(id: Bytes, bytes: Int, ticket: Long)

  trait Source[F[_]]:
    /** Up to `max` transactions with a ticket above `ticket`, oldest first. */
    def after(ticket: Long, max: Int): F[Vector[Held]]

    /** Completes once a transaction with a ticket above `ticket` exists. */
    def awaitAfter(ticket: Long): F[Unit]

    /** The original bytes of a transaction, if it is still held. */
    def body(id: Bytes): F[Option[Bytes]]

  final case class Settings(
      /** Time to wait for the server's next request. */
      idleTimeout: FiniteDuration = 3373.seconds
  )

  private final case class Window(unacked: Vector[Bytes], cursor: Long)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      source: Source[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = Protocols.TxSubmission2
    def illegal[A](detail: String): F[A] = NetworkError.IllegalTransition(id, detail).raise[F, A]

    def send(state: TxSubmission2.State, message: TxSubmission2.Message): F[Unit] =
      TxSubmission2.encode(state, Role.Client, message).fold(illegal, channel.send)

    def offers(held: Vector[Held]): F[Vector[RelayOffer]] =
      held.traverse(h =>
        TxSubmission2
          .advertisedSize(h.bytes)
          .fold(illegal, size => F.pure(RelayOffer(h.id, size)))
      )

    def receive(state: TxSubmission2.State): F[TxSubmission2.Message] =
      Timed.within(settings.idleTimeout, id, state.toString)(
        channel.recv(TxSubmission2.MaxMessageBytes)(
          TxSubmission2.decodePrefix(state, Role.Server, _)
        )
      )

    def answerIds(window: Window, blocking: Boolean, ack: Int, request: Int): F[Window] =
      val retained = window.unacked.drop(ack)
      val limit = request.min(TxSubmission2.MaxBatch)
      if ack > window.unacked.size then illegal("acknowledged more ids than were outstanding")
      else if blocking && (request == 0 || retained.nonEmpty) then
        illegal("blocking request with ids still outstanding")
      else if !blocking && retained.isEmpty then
        illegal("non-blocking request with nothing outstanding")
      else
        val fresh =
          if blocking then source.awaitAfter(window.cursor) *> source.after(window.cursor, limit)
          else source.after(window.cursor, limit)
        fresh.flatMap { held =>
          // A blocking wait that raced with a removal can come back empty: wait again.
          if blocking && held.isEmpty then answerIds(window, blocking, ack, request)
          else
            offers(held).flatMap { os =>
              send(TxSubmission2.State.Ids(blocking), TxSubmission2.Message.ReplyIds(os)).as(
                Window(retained ++ held.map(_.id), held.lastOption.fold(window.cursor)(_.ticket))
              )
            }
        }

    def answerTxs(window: Window, ids: Vector[Bytes]): F[Unit] =
      if ids.exists(i => !window.unacked.contains(i)) then
        illegal("requested a transaction that was not offered")
      else
        ids.flatTraverse(i => source.body(i).map(_.toVector)).flatMap { bodies =>
          send(TxSubmission2.State.Txs, TxSubmission2.Message.ReplyTxs(bodies))
        }

    def loop(window: Window): F[Unit] =
      receive(TxSubmission2.State.Idle).flatMap {
        case TxSubmission2.Message.RequestIds(blocking, ack, request) =>
          answerIds(window, blocking, ack, request).flatMap(loop)
        case TxSubmission2.Message.RequestTxs(ids) => answerTxs(window, ids) *> loop(window)
        case other                                 => illegal(s"unexpected server message $other")
      }

    send(TxSubmission2.State.Init, TxSubmission2.Message.Init) *>
      loop(Window(Vector.empty, 0L))

  /** Offer `source` to the peer for the life of the resource. A failure ends the connection. */
  def background[F[_]: cats.effect.Async](
      mux: Mux[F],
      source: Source[F],
      settings: Settings = Settings(),
      /** The side of the protocol to run: the multiplexer's own by default. */
      role: Option[Mux.Role] = None
  ): cats.effect.Resource[F, Unit] =
    import cats.effect.syntax.all.*
    val program = cats.mtl.Handle
      .allow[NetworkError](
        role
          .fold(mux.channel(Protocols.TxSubmission2))(mux.channel(Protocols.TxSubmission2, _))
          .flatMap(run(_, source, settings))
      )
      .rescue(error => mux.abort(error.message))
      .handleErrorWith(t =>
        mux.abort(s"tx-submission: ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}")
      )
    program.background.void
