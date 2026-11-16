// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.TxSubmission2
import xyz.kd5ujc.scarab.protocol.TxSubmission2.{EraTx, EraTxId}

import NetworkError.RuntimeViolation

/** TxSubmission2 as the side that collects transactions (the protocol's server, which is the
  * responder of an inbound connection): it asks the peer for transaction ids, fetches the bodies it
  * does not know and passes them to a [[Sink]].
  *
  * Every batch of offers is fully processed and acknowledged before the next request, so there is
  * never more than one batch outstanding and every id request is blocking.
  */
object TxSubmissionServer:
  trait Sink[F[_]]:
    /** Whether the transaction is already held (in the mempool or recently seen), whatever its era:
      * the reference compares ids by their hash alone.
      */
    def known(id: Bytes): F[Boolean]

    /** Offer fetched transactions, each with the era its `GenTx` names. Rejections are the sink's
      * business: a refused transaction is not a protocol error. A failure of the node's own
      * library, which gives no verdict on a transaction, ends the session through the caller's
      * `Raise`: the connection closes and the peer is tried again later, as the reference's
      * `ShutdownPeer` does for an exception in the inbound TxSubmission2 thread.
      */
    def submit(originals: Vector[EraTx])(using Raise[F, NetworkError]): F[Unit]

    /** How the transactions it takes are read: the codec refuses a reply whose transaction it
      * cannot read, and the transaction id is the hash of the body it reads.
      */
    def reader: TxSubmission2.TransactionReader

  /** @param idsPerRequest
    *   Ids asked for per request.
    */
  final case class Settings(idsPerRequest: Int = 3)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      sink: Sink[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val typedSink = new TxSubmission2Runtime.Sink[F, EraTxId, EraTx]:
      def known(id: EraTxId) = sink.known(id.id)
      def submit(originals: Vector[EraTx])(using Raise[F, NetworkError]) = sink.submit(originals)
    // The configured UInt16 request count is independent of the reply-list batch ceiling.
    TxSubmission2Runtime.server(
      channel,
      typedSink,
      TxSubmission2.eraCodec(sink.reader),
      TxSubmission2Runtime.eraRules,
      TxSubmission2Runtime
        .ServerSettings(
          settings.idsPerRequest,
          maxUnacked = settings.idsPerRequest
        ),
      message => RuntimeViolation.TxSubmissionUnexpected(TxSubmission2.fromEraEngine(message))
    )
