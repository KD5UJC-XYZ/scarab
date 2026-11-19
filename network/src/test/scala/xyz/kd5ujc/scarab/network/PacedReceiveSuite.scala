// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicInteger
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{
  IncrementalDecoder,
  Paced,
  ProtocolError,
  TxSubmission2,
  TxSubmission2Engine
}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}

import Peers.typed

/** A peer that paces its SDUs, so that the ingress queue runs dry after every one and the
  * multiplexer decodes after each: the decoder is resumed from its cursor, and reads each part of
  * the message once.
  */
class PacedReceiveSuite extends FailsOnUncaught:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private final class CountingReader extends TxSubmission2.TransactionReader:
    val reads = new AtomicInteger(0)
    def body(original: Bytes): Either[ProtocolError, Bytes] = Right(original)
    def read(original: Bytes): Either[ProtocolError, Bytes] =
      reads.incrementAndGet()
      Right(original)

  /** `decoder`, with its attempts counted in `attempts`. */
  private def counted[A](decoder: IncrementalDecoder[A], attempts: AtomicInteger) =
    new IncrementalDecoder[A]:
      type Cursor = decoder.Cursor
      def initial: Cursor = decoder.initial
      def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[A]) =
        attempts.incrementAndGet()
        decoder.resume(cursor, input)

  /** `message` sent a byte an SDU on `protocol`, the receiver given time to decode after each: what
    * `receive` returned, and how many SDUs were sent.
    */
  private def paced[A](protocol: Int, message: Bytes, head: Int)(
      receive: MuxChannel[IO] => Peers.Net ?=> IO[A]
  ): IO[(Either[NetworkError, A], Int)] =
    Settled { settle =>
      typed {
        Peers.linked(protocols = Set(protocol)).use { (client, server) =>
          for
            c <- client.channel(protocol)
            s <- server.channel(protocol)
            fiber <- typed(receive(s)).start
            _ <- c.send(message.take(head)) *> settle
            sdus <- (head until message.size).toList
              .traverse(i => c.send(message.slice(i, i + 1)) *> settle)
            result <- fiber.joinWithNever
          yield (result, sdus.size + 1)
        }
      }.map(_.fold(e => (Left(e), 0), identity))
    }

  test("h-1: a MsgReplyTxs paced a byte an SDU after three whole transactions reads each once") {
    val transaction = hex("8206d818" + "58c8" + "01" * 200)
    val message = hex("82039f") ++ transaction ++ transaction ++ transaction ++ transaction ++
      hex("ff")
    val head = 3 + 3 * transaction.size
    val reader = new CountingReader
    val decoder = TxSubmission2.eraCodec(reader).incremental(TxSubmission2.State.Txs, Role.Client)
    val attempts = new AtomicInteger(0)
    paced(Protocols.TxSubmission2, message, head)(s =>
      s.recv(TxSubmission2.MaxMessageBytes)(counted(decoder, attempts))
    ).map { (result, sdus) =>
      result match
        case Right(TxSubmission2Engine.Message.ReplyTxs(txs)) => assertEquals(txs.size, 4)
        case other                                            => fail(s"not the reply: $other")
      // one attempt on the empty buffer, then one after every SDU: the queue ran dry each time
      assertEquals(attempts.get, sdus + 1)
      assertEquals(reader.reads.get, 4, "each transaction read once")
    } *> {
      // the receive of each prefix from its start, as before decoders kept their cursor
      val again = new CountingReader
      val restarting = Paced.restarting(
        TxSubmission2.eraCodec(again).incremental(TxSubmission2.State.Txs, Role.Client)
      )
      paced(Protocols.TxSubmission2, message, head)(s =>
        s.recv(TxSubmission2.MaxMessageBytes)(restarting)
      ).map((_, sdus) =>
        assert(again.reads.get >= 3 * sdus, s"read ${again.reads.get} times over $sdus SDUs")
      )
    }
  }
