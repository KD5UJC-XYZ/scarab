// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Resource}
import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSyncFixtures
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

class ChainSyncFlightSuite extends FailsOnUncaught:
  test("canceling a blocked send releases the legacy client busy flag") {
    Peers
      .typed {
        for
          entered <- Deferred[IO, Unit]
          channel = new MuxChannel[IO]:
            def protocol = Protocols.ChainSync
            def send(message: Bytes)(using Raise[IO, NetworkError]) =
              entered.complete(()).void *> IO.never[Unit]
            def flush = IO.unit
            def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
                Raise[IO, NetworkError]
            ) = IO.never[A]
          client <- ChainSyncClient(channel, ChainSyncFixtures.rawItem)
          idle <- Resource
            .make(
              Fibers
                .scoped[IO, NetworkError, ChainSyncClient.Update[Bytes]](NetworkError.Defect(_))(
                  client.requestNext
                )
                .start
            )(_.cancel)
            .use { fiber =>
              entered.get *> fiber.cancel *> client.idle
            }
        yield assert(idle)
      }
      .map(result => assertEquals(result, Right(())))
  }

  test("a typed send refusal also releases the legacy client busy flag") {
    Peers
      .typed {
        val cause = NetworkError.Timeout(Protocols.ChainSync, "injected-send")
        val channel = new MuxChannel[IO]:
          def protocol = Protocols.ChainSync
          def send(message: Bytes)(using raise: Raise[IO, NetworkError]) =
            raise.raise[NetworkError, Unit](cause)
          def flush = IO.unit
          def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
              Raise[IO, NetworkError]
          ) = IO.never[A]
        for
          client <- ChainSyncClient(channel, ChainSyncFixtures.rawItem)
          result <- Fibers.scoped[IO, NetworkError, ChainSyncClient.Update[Bytes]](
            NetworkError.Defect(_)
          )(client.requestNext)
          idle <- client.idle
        yield
          assertEquals(result, Left(cause))
          assert(idle)
      }
      .map(result => assertEquals(result, Right(())))
  }
