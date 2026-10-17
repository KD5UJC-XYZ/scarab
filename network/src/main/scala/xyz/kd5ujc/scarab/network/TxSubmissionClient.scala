// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
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

  /** `run` ends when the peer closes the protocol or, with `finish`, once that completes and the
    * peer next asks for transactions and waits: a blocking request is the only moment this side may
    * end the protocol, with `MsgDone`. A new conversation may then start with a fresh `MsgInit`.
    */
  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      source: Source[F],
      settings: Settings = Settings(),
      finish: Option[F[Unit]] = None
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

    /** The window after the reply, or none once the protocol was ended with `MsgDone`. */
    def answerIds(window: Window, blocking: Boolean, ack: Int, request: Int): F[Option[Window]] =
      val retained = window.unacked.drop(ack)
      val limit = request.min(TxSubmission2.MaxBatch)
      if ack > window.unacked.size then illegal("acknowledged more ids than were outstanding")
      else if blocking && (request == 0 || retained.nonEmpty) then
        illegal("blocking request with ids still outstanding")
      else if !blocking && retained.isEmpty then
        illegal("non-blocking request with nothing outstanding")
      else
        // a blocking wait ends early when the owner asks to finish: nothing is outstanding then
        val fresh: F[Option[Vector[Held]]] =
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
            send(TxSubmission2.State.Ids(true), TxSubmission2.Message.Done).as(None)
          // A blocking wait that raced with a removal can come back empty: wait again.
          case Some(held) if blocking && held.isEmpty =>
            answerIds(window, blocking, ack, request)
          case Some(held) =>
            offers(held).flatMap { os =>
              send(TxSubmission2.State.Ids(blocking), TxSubmission2.Message.ReplyIds(os)).as(
                Some(
                  Window(retained ++ held.map(_.id), held.lastOption.fold(window.cursor)(_.ticket))
                )
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
          answerIds(window, blocking, ack, request).flatMap(_.fold(F.unit)(loop))
        case TxSubmission2.Message.RequestTxs(ids) => answerTxs(window, ids) *> loop(window)
        case other                                 => illegal(s"unexpected server message $other")
      }

    send(TxSubmission2.State.Init, TxSubmission2.Message.Init) *>
      loop(Window(Vector.empty, 0L))

  /** A TxSubmission2 client running in the background that can be asked to end. */
  trait Running[F[_]]:
    /** Ask the client to end the protocol and wait up to `within` for it to have. It ends at the
      * peer's next blocking request (the only moment the protocol allows it), which a peer that
      * does not ask may never send: `false` then, and the connection must not carry a new
      * conversation.
      */
    def finish(within: FiniteDuration): F[Boolean]

  /** Offer `source` to the peer for the life of the resource. A failure ends the connection. */
  def background[F[_]: cats.effect.Async](
      mux: Mux[F],
      source: Source[F],
      settings: Settings = Settings(),
      /** The side of the protocol to run: the multiplexer's own by default. */
      role: Option[Mux.Role] = None
  ): cats.effect.Resource[F, Unit] =
    backgroundFinishing(mux, source, settings, role).void

  /** As [[background]], and the client can be asked to end ([[Running.finish]]). */
  def backgroundFinishing[F[_]: cats.effect.Async](
      mux: Mux[F],
      source: Source[F],
      settings: Settings = Settings(),
      role: Option[Mux.Role] = None
  ): cats.effect.Resource[F, Running[F]] =
    import cats.effect.syntax.all.*
    val F = cats.effect.Async[F]
    cats.effect.Resource.eval((F.deferred[Unit], F.deferred[Boolean]).tupled).flatMap {
      case (asked, ended) =>
        // the fiber's own error scope: the reason it ends the connection with is a value
        val program = Fibers
          .scoped[F, NetworkError, Boolean](t =>
            NetworkError.BearerClosed(
              s"tx-submission: ${Option(t.getMessage).getOrElse(t.toString)}"
            )
          )(
            role
              .fold(mux.channel(Protocols.TxSubmission2))(mux.channel(Protocols.TxSubmission2, _))
              .flatMap(run(_, source, settings, Some(asked.get)))
              .as(true)
          )
          .flatMap(_.fold(error => mux.abort(error).as(false), F.pure))
          .flatMap(ended.complete)
          .void
        program.background.as(
          new Running[F]:
            def finish(within: FiniteDuration) =
              asked.complete(()) *> F.timeoutTo(ended.get, within, F.pure(false))
        )
    }
