// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, IOApp}
import cats.effect.std.Semaphore
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

/** The cost of a message through the multiplexer: a producer sends messages of one size, a consumer
  * reads them, over an in-memory bearer, with the credit window a protocol gives a peer. Not part
  * of `check`: run it with `sbt "network/Test/runMain
  * xyz.kd5ujc.scarab.network.MuxThroughputBench"`; it prints the median microseconds per message
  * and the throughput. It uses only the surface the multiplexer had before issue 132 (`channel`,
  * `send`, `recv`), so the same file runs against both builds; the figures are in
  * `docs/performance.md` section 21.
  */
object MuxThroughputBench extends IOApp.Simple:
  private def exactly(n: Int)(bytes: Bytes): DecodeResult[Bytes] =
    if bytes.size >= n then DecodeResult.Decoded(Bytes(bytes.value.take(n)), n)
    else DecodeResult.NeedMore

  private def payload(n: Int): Bytes = Bytes.wrap(Array.tabulate(n)(i => (i * 31).toByte))

  private def round(protocol: Int, size: Int, window: Int, messages: Int): IO[Double] =
    Handle
      .allow[NetworkError] {
        Bearer.inMemory[IO](256).flatMap { (a, b) =>
          (
            Mux.resource(a, Set(protocol), Mux.Config()),
            Mux.resource(b, Set(protocol), Mux.Config(role = Mux.Role.Responder))
          ).tupled.use { (client, server) =>
            for
              c <- client.channel(protocol)
              s <- server.channel(protocol)
              credit <- Semaphore[IO](window.toLong)
              message = payload(size)
              began <- IO.monotonic
              producer <- (1 to messages).toList
                .traverse_(_ => credit.acquire *> c.send(message))
                .start
              _ <- (1 to messages).toList
                .traverse_(_ => s.recv(size + 16)(exactly(size)) *> credit.release)
              _ <- producer.joinWithNever
              took <- IO.monotonic.map(_ - began)
            yield took.toNanos.toDouble / messages / 1000.0
          }
        }
      }
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))

  private def median(xs: List[Double]): Double = xs.sorted.apply(xs.size / 2)

  def run: IO[Unit] =
    // (protocol, message size, credit window in messages, messages per round)
    val cases = List((2, 1400, 64, 40000), (2, 400, 64, 40000), (3, 65000, 8, 2000))
    cases.traverse_ { (protocol, size, window, messages) =>
      for
        _ <- round(protocol, size, window, messages) // warm-up
        _ <- round(protocol, size, window, messages)
        samples <- List.fill(7)(()).traverse(_ => round(protocol, size, window, messages))
        us = median(samples)
        _ <- IO.println(
          f"BENCH mux, messages of $size%6d bytes, window $window%3d: $us%8.2f us a message, ${size / us}%7.1f MB/s"
        )
      yield ()
    }
