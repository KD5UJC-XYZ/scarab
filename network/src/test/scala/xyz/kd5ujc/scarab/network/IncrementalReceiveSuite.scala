// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{IncrementalDecoder, LocalTxSubmission}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}

class IncrementalReceiveSuite extends FailsOnUncaught:
  test(
    "the mux retains framing progress across an empty queue and starts fresh for the next message"
  ) {
    val wire = Bytes.wrap(
      Array[Byte](0x82.toByte, 0, 0x9f.toByte) ++ Array.fill[Byte](32)(0) ++ Array[Byte](
        0xff.toByte
      )
    )
    val done = Bytes.wrap(Array[Byte](0x81.toByte, 3))
    val delegate = LocalTxSubmission.incremental(LocalTxSubmission.State.Idle, Role.Client)
    val checked = new IncrementalDecoder[LocalTxSubmission.Message]:
      type Cursor = delegate.Cursor
      def initial: Cursor = delegate.initial
      def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[LocalTxSubmission.Message]) =
        if input.size > 16 then
          assertNotEquals(cursor, initial, "the completed prefix was discarded")
        delegate.resume(cursor, input)
    TestControl
      .executeEmbed(Peers.typed {
        Peers.linked(protocols = Set(Protocols.LocalTxSubmission)).use { (client, server) =>
          for
            c <- client.channel(Protocols.LocalTxSubmission)
            s <- server.channel(Protocols.LocalTxSubmission)
            result <- Peers
              .typed(
                c.send(wire.take(16)) *> IO.sleep(1.second) *> c.send(wire.drop(16) ++ done)
              )
              .background
              .use { _ =>
                for
                  first <- s.recvIncremental(LocalTxSubmission.MaxMessageBytes)(checked)
                  second <- s.recvIncremental(LocalTxSubmission.MaxMessageBytes)(
                    LocalTxSubmission.incremental(LocalTxSubmission.State.Idle, Role.Client)
                  )
                yield (first, second)
              }
          yield result
        }
      })
      .map(result =>
        assertEquals(
          result,
          Right(
            (
              LocalTxSubmission.Message.SubmitTx(wire.drop(2)),
              LocalTxSubmission.Message.Done
            )
          )
        )
      )
  }
