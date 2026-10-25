// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.TxSubmission2

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
    /** Whether the transaction is already held (in the mempool or recently seen). */
    def known(id: Bytes): F[Boolean]

    /** Offer fetched transactions (original bytes). Rejections are the sink's business: a refused
      * transaction is not a protocol error.
      */
    def submit(originals: Vector[Bytes]): F[Unit]

  /** @param idsPerRequest
    *   Ids asked for per request.
    */
  final case class Settings(idsPerRequest: Int = 3)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      sink: Sink[F],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val typedSink = new TxSubmission2Runtime.Sink[F, Bytes, Bytes]:
      def known(id: Bytes) = sink.known(id)
      def submit(originals: Vector[Bytes]) = sink.submit(originals)
    val rules = new TxSubmission2Runtime.Rules[Bytes]:
      def acknowledge(unacked: Vector[Bytes], blocking: Boolean, ack: Int, request: Int) =
        TxSubmissionPolicy.acknowledge(unacked, blocking, ack, request)
      def bodies(unacked: Vector[Bytes], ids: Vector[Bytes]) =
        TxSubmissionPolicy.bodies(unacked, ids)
      def offers(ids: Vector[Bytes], requested: Int) = TxSubmissionPolicy.offers(ids, requested)
    // The configured UInt16 request count is independent of the reply-list batch ceiling.
    TxSubmission2Runtime.server(
      channel,
      typedSink,
      TxSubmission2.conwayCodec,
      rules,
      TxSubmission2Runtime
        .ServerSettings(
          settings.idsPerRequest,
          maxUnacked = settings.idsPerRequest
        ),
      message => RuntimeViolation.TxSubmissionUnexpected(TxSubmission2.fromEngine(message))
    )
