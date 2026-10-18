// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.TxSubmission2
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import scala.concurrent.duration.*

/** TxSubmission2 as the side that collects transactions (the protocol's server, which is the
  * responder of an inbound connection): it asks the peer for transaction ids, fetches the bodies it
  * does not know and passes them to a [[Sink]].
  *
  * Every batch of offers is fully processed and acknowledged before the next request, so there is
  * never more than one batch outstanding and every id request is blocking.
  */
object TxSubmissionServer:
  trait Sink[F[_]]:
    /** Whether the transaction is already held (in the mempool or recently seen). */
    def known(id: Bytes): F[Boolean]

    /** Offer fetched transactions (original bytes). Rejections are the sink's business: a refused
      * transaction is not a protocol error.
      */
    def submit(originals: Vector[Bytes]): F[Unit]

  final case class Settings(
      /** Ids asked for per request. */
      idsPerRequest: Int = 3,
      /** Time the client may take to send the transactions we asked for. */
      bodiesTimeout: FiniteDuration = TxSubmissionPolicy.ShortWait,
      /** Time to wait for the client's Init. */
      initTimeout: FiniteDuration = 60.seconds
  )

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      sink: Sink[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = Protocols.TxSubmission2
    def illegal[A](detail: String): F[A] = NetworkError.IllegalTransition(id, detail).raise[F, A]

    def send(state: TxSubmission2.State, message: TxSubmission2.Message): F[Unit] =
      TxSubmission2.encode(state, Role.Server, message).fold(illegal, channel.send)

    def receive(
        state: TxSubmission2.State,
        limit: Option[FiniteDuration]
    ): F[TxSubmission2.Message] =
      val read = channel.recv(TxSubmission2.maxMessageBytes(state))(
        TxSubmission2.decodePrefix(state, Role.Client, _)
      )
      limit
        .orElse(TxSubmissionPolicy.timeLimit(state))
        .fold(read)(l => Timed.within(l, id, state.toString)(read))

    def fetch(wanted: Vector[Bytes]): F[Unit] =
      if wanted.isEmpty then F.unit
      else
        wanted.grouped(TxSubmission2.MaxBatch).toVector.traverse_ { group =>
          send(TxSubmission2.State.Idle, TxSubmission2.Message.RequestTxs(group)) *>
            receive(TxSubmission2.State.Txs, Some(settings.bodiesTimeout)).flatMap {
              case TxSubmission2.Message.ReplyTxs(originals) =>
                originals
                  .traverse(o =>
                    TxSubmission2.bodyId(o).fold(illegal[Bytes], F.pure).flatMap { txId =>
                      if group.contains(txId) then F.pure(o)
                      else illegal("received a transaction that was not requested")
                    }
                  )
                  .flatMap(sink.submit)
              case other => illegal(s"unexpected $other")
            }
        }

    def loop(ack: Int): F[Unit] =
      send(
        TxSubmission2.State.Idle,
        TxSubmission2.Message.RequestIds(blocking = true, ack, settings.idsPerRequest)
      ) *> receive(TxSubmission2.State.Ids(true), None).flatMap {
        case TxSubmission2.Message.ReplyIds(offers) =>
          TxSubmissionPolicy
            .offers(offers.map(_.transactionId), settings.idsPerRequest)
            .fold(v => NetworkError.TxSubmission(v).raise[F, Unit], F.pure) *> offers
            .filter(o => o.advertisedSize <= TxSubmission2.MaxOriginalBytes + 16L)
            .traverseFilter(o =>
              sink.known(o.transactionId).map(k => Option.unless(k)(o.transactionId))
            )
            .flatMap(fetch) *> loop(offers.size)
        case TxSubmission2.Message.Done => F.unit
        case other                      => illegal(s"unexpected $other")
      }

    receive(TxSubmission2.State.Init, Some(settings.initTimeout)).flatMap {
      case TxSubmission2.Message.Init => loop(0)
      case other                      => illegal(s"expected Init, got $other")
    }
