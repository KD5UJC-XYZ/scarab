// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import fs2.{Chunk, Stream}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult
import xyz.kd5ujc.scarab.protocol.Mux as WireMux
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import Peers.*

class MuxDeadlinesSuite extends FailsOnUncaught:
  private val readTimeout = NetworkError.SduTimeout(NetworkError.SduOperation.Read)
  private val writeTimeout = NetworkError.SduTimeout(NetworkError.SduOperation.Write)

  private def frame(n: Int): Bytes =
    WireMux
      .encode(WireMux.Sdu(0, 2, WireMux.Direction.Responder, Bytes(Vector.fill(n)(1.toByte))))
      .fold(e => fail(e.message), identity)

  private def bytes(value: Bytes): Stream[IO, Byte] = Stream.chunk(Chunk.array(value.toArray))

  private def bearer(input: Stream[IO, Byte], output: Chunk[Byte] => IO[Unit] = _ => IO.unit) =
    new Bearer[IO]:
      def reads = input
      def write(value: Chunk[Byte]) = output(value)
      def close = IO.unit

  private def read(input: Stream[IO, Byte]): IO[(NetworkError, FiniteDuration, Boolean)] =
    for
      released <- Ref.of[IO, Boolean](false)
      result <- typed {
        Mux.resource(bearer(input.onFinalize(released.set(true))), Set(2)).use { mux =>
          for
            reason <- mux.closed
            now <- IO.monotonic
            closed <- released.get
          yield (reason, now, closed)
        }
      }
    yield result.fold(e => fail(e.message), identity)

  test("idle established bearer waits indefinitely before the first SDU bytes"):
    TestControl
      .executeEmbed(
        read(Stream.sleep_[IO](90.seconds) ++ bytes(frame(1)))
      )
      .map { (reason, elapsed, released) =>
        assertEquals(reason, NetworkError.BearerClosed(NetworkError.Closure.PeerClosed))
        assertEquals(elapsed, 90.seconds)
        assert(released)
      }

  for prefix <- List(1, 7, 8, 9) do
    test(s"partial SDU with $prefix header/payload bytes has one absolute30s deadline"):
      TestControl
        .executeEmbed(read(bytes(frame(100).take(prefix)) ++ Stream.never[IO]))
        .map { (reason, elapsed, released) =>
          assertEquals(reason, readTimeout)
          assertEquals(elapsed, 30.seconds)
          assert(released)
        }

  test("slow-loris progress every5s does not restart the SDU deadline"):
    val input = bytes(frame(100).take(1)) ++
      Stream.repeatEval(IO.sleep(5.seconds).as(1.toByte))
    TestControl.executeEmbed(read(input)).map { (reason, elapsed, released) =>
      assertEquals(reason, readTimeout)
      assertEquals(elapsed, 30.seconds)
      assert(released)
    }

  test("an idle interval after a complete SDU is unbounded; a new partial gets its own budget"):
    val input = bytes(frame(1).take(1)) ++ Stream.sleep_[IO](29.seconds) ++
      bytes(frame(1).drop(1)) ++ Stream.sleep_[IO](90.seconds) ++
      bytes(frame(100).take(1)) ++ Stream.never[IO]
    TestControl.executeEmbed(read(input)).map { (reason, elapsed, _) =>
      assertEquals(reason, readTimeout)
      assertEquals(elapsed, 149.seconds)
    }

  test("a complete SDU followed by a partial SDU in the same chunk resets the old deadline"):
    val input = bytes(frame(1).take(1)) ++ Stream.sleep_[IO](29.seconds) ++
      bytes(frame(1).drop(1) ++ frame(100).take(1)) ++ Stream.never[IO]
    TestControl.executeEmbed(read(input)).map { (reason, elapsed, _) =>
      assertEquals(reason, readTimeout)
      assertEquals(elapsed, 59.seconds)
    }

  test("EOF in a partial frame remains a framing error, before the deadline"):
    TestControl.executeEmbed(read(bytes(frame(100).take(9)))).map { (reason, elapsed, _) =>
      assertEquals(reason, NetworkError.Framing(WireMux.Error.TruncatedPayload(100, 1)))
      assertEquals(elapsed, Duration.Zero)
    }

  for (protocol, payload, expected) <- List((2, 1, 30), (0, 1, 10), (2, 2, 30)) do
    test(s"stalled writer protocol$protocol/$payload SDUs has one-SDU write budget${expected}s"):
      TestControl
        .executeEmbed(typed {
          Mux
            .resource(
              bearer(Stream.never[IO], _ => IO.never),
              Set(protocol),
              Mux.Config(sduSize = 1)
            )
            .use { mux =>
              for
                channel <- mux.channel(protocol)
                _ <- channel.send(Bytes(Vector.fill(payload)(1.toByte)))
                reason <- mux.closed
                elapsed <- IO.monotonic
              yield (reason, elapsed)
            }
        })
        .map(result => assertEquals(result, Right((writeTimeout, expected.seconds))))

  test("a completed SDU does not donate its unused write budget to the next stalled SDU"):
    TestControl
      .executeEmbed(typed {
        for
          writes <- Ref.of[IO, Int](0)
          result <- Mux
            .resource(
              bearer(
                Stream.never[IO],
                _ =>
                  writes.getAndUpdate(_ + 1).flatMap {
                    case 0 => IO.sleep(29.seconds)
                    case _ => IO.never
                  }
              ),
              Set(2),
              Mux.Config(sduSize = 1)
            )
            .use { mux =>
              for
                channel <- mux.channel(2)
                _ <- channel.send(Bytes(Vector[Byte](1, 2)))
                reason <- mux.closed
                elapsed <- IO.monotonic
                count <- writes.get
              yield (reason, elapsed, count)
            }
        yield result
      })
      .map(result => assertEquals(result, Right((writeTimeout, 59.seconds, 2))))

  test("releasing a mux cancels an idle timed source without leaving its reader alive"):
    TestControl
      .executeEmbed(for
        released <- Ref.of[IO, Boolean](false)
        result <- typed {
          Mux
            .resource(
              bearer(Stream.never[IO].onFinalize(released.set(true))),
              Set(2)
            )
            .use(_ => IO.sleep(60.seconds))
        }
        closed <- released.get
      yield (result, closed))
      .map(result => assertEquals(result, (Right(()), true)))

  private def exactly(n: Int)(value: Bytes): DecodeResult[Bytes] =
    if value.size >= n then DecodeResult.Decoded(value.take(n), n) else DecodeResult.NeedMore

  test("a fully decoded oversized message cannot bypass the state byte limit"):
    typed {
      linked(protocols = Set(2)).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- c.send(Bytes(Vector.fill(101)(1.toByte)))
          result <- s.recv(100)(exactly(101))
        yield result
      }
    }.map(result => assertEquals(result, Left(NetworkError.IngressLimit(2, 101))))

  test("concatenated messages each within the state limit remain readable"):
    typed {
      linked(protocols = Set(2)).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- c.send(Bytes(Vector.fill(200)(1.toByte)))
          a <- s.recv(100)(exactly(100))
          b <- s.recv(100)(exactly(100))
        yield (a.size, b.size)
      }
    }.map(result => assertEquals(result, Right((100, 100))))

  test("100000 SDUs traverse the timed reader on a256KiB worker stack"):
    Resource
      .make(
        IO(
          Executors.newSingleThreadExecutor((run: Runnable) =>
            new Thread(null, run, "mux-small-stack", 256L * 1024)
          )
        )
      )(pool => IO(pool.shutdown()))
      .map(ExecutionContext.fromExecutorService)
      .use { executor =>
        TestControl
          .executeEmbed(for
            thread <- Ref.of[IO, String]("")
            input = Stream.eval(IO(Thread.currentThread().getName).flatMap(thread.set)).drain ++
              Stream.emits(Vector.fill(100000)(frame(1))).flatMap(bytes)
            result <- read(input)
            worker <- thread.get
          yield
            assertEquals(result._1, NetworkError.BearerClosed(NetworkError.Closure.PeerClosed))
            assert(result._3)
            assertEquals(worker, "mux-small-stack"))
          .evalOn(executor)
      }
