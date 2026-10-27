// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.TxSubmission2
import scala.concurrent.duration.*

import NetworkError.RuntimeViolation

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

  /** `run` ends when the peer closes the protocol or, with `finish`, once that completes and the
    * peer next asks for transactions and waits: a blocking request is the only moment this side may
    * end the protocol, with `MsgDone`. A new conversation may then start with a fresh `MsgInit`.
    */
  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      source: Source[F],
      finish: Option[F[Unit]] = None
  )(using Raise[F, NetworkError]): F[Unit] =
    val typedSource = new TxSubmission2Runtime.Source[F, Bytes, Bytes]:
      def after(ticket: Long, max: Int) =
        source
          .after(ticket, max)
          .map(_.map(h => TxSubmission2Runtime.Held(h.id, h.bytes, h.ticket)))
      def awaitAfter(ticket: Long) = source.awaitAfter(ticket)
      def body(id: Bytes) = source.body(id)
    val rules = new TxSubmission2Runtime.Rules[Bytes]:
      def acknowledge(unacked: Vector[Bytes], blocking: Boolean, ack: Int, request: Int) =
        TxSubmissionPolicy.acknowledge(unacked, blocking, ack, request)
      def bodies(unacked: Vector[Bytes], ids: Vector[Bytes]) =
        TxSubmissionPolicy.bodies(unacked, ids)
      def offers(ids: Vector[Bytes], requested: Int) = TxSubmissionPolicy.offers(ids, requested)
    TxSubmission2Runtime.client(
      channel,
      typedSource,
      TxSubmission2.conwayCodec,
      rules,
      finish,
      message => RuntimeViolation.TxSubmissionUnexpected(TxSubmission2.fromEngine(message))
    )

  /** A TxSubmission2 client running in the background that can be asked to end. */
  trait Running[F[_]]:
    /** Ask the client to end the protocol and wait up to `within` for it to have. It ends at the
      * peer's next blocking request (the only moment the protocol allows it), which a peer that
      * does not ask may never send: `false` then, and the connection must not carry a new
      * conversation.
      */
    def finish(within: FiniteDuration): F[Boolean]

    /** Request protocol termination and retain its exact result. The connection action owns the
      * deadline; cancelling this wait does not cancel the resource-owned protocol fiber.
      */
    def terminate: F[Either[NetworkError, Unit]]

  /** Offer `source` to the peer for the life of the resource. A failure ends the connection.
    * @param role
    *   The side of the protocol to run: the multiplexer's own by default.
    */
  def background[F[_]: cats.effect.Async](
      mux: Mux[F],
      source: Source[F],
      role: Option[Mux.Role] = None
  ): cats.effect.Resource[F, Unit] =
    backgroundFinishing(mux, source, role).void

  /** As [[background]], and the client can be asked to end ([[Running.finish]]). */
  def backgroundFinishing[F[_]: cats.effect.Async](
      mux: Mux[F],
      source: Source[F],
      role: Option[Mux.Role] = None,
      completed: Option[Either[NetworkError, Unit] => F[Unit]] = None,
      termination: Option[F[Unit]] = None
  ): cats.effect.Resource[F, Running[F]] =
    import cats.effect.syntax.all.*
    val F = cats.effect.Async[F]
    cats.effect.Resource
      .eval((F.deferred[Unit], F.deferred[Either[NetworkError, Unit]]).tupled)
      .flatMap { case (asked, ended) =>
        // The fiber's own error scope. Mux already typed transport failures; an uncaught
        // exception here comes from the local source/interpreter, even if it is an IOException.
        val program = Fibers
          .scoped[F, NetworkError, Unit](NetworkError.Defect(_))(
            role
              .fold(mux.channel(Protocols.TxSubmission2))(mux.channel(Protocols.TxSubmission2, _))
              .flatMap(
                run(
                  _,
                  source,
                  Some(termination.fold(asked.get)(external => F.race(asked.get, external).void))
                )
              )
          )
          .flatTap(result => completed.traverse_(_(result)))
          .flatTap(result => ended.complete(result))
          .flatMap(_.fold(mux.abort, _ => F.unit))
          .void
        program.background.as(
          new Running[F]:
            def finish(within: FiniteDuration) =
              F.timeoutTo(terminate.map(_.isRight), within, F.pure(false))
            def terminate = asked.complete(()) *> ended.get
        )
      }
