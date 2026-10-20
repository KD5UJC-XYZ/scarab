// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Outcome, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

/** Deadline results are observable only after their cancelled operation has released its resources.
  */
class DeadlineCleanupSuite extends FailsOnUncaught:
  private def hex(value: String): Bytes =
    Bytes.fromHex(value).fold(error => fail(error.message), identity)

  private def exactly(n: Int)(bytes: Bytes): DecodeResult[Bytes] =
    if bytes.size >= n then DecodeResult.Decoded(bytes.take(n), n)
    else DecodeResult.NeedMore

  test("writer timeout waits for its cancellation finalizer before publishing mux.closed"):
    for
      writing <- Deferred[IO, Unit]
      finalizing <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      stopped <- Deferred[IO, Unit]
      published <- Deferred[IO, (NetworkError, Option[Unit])]
      bearer = new Bearer[IO]:
        def reads = Stream.never[IO]
        def write(_bytes: Chunk[Byte]): IO[Unit] =
          (writing.complete(()).void *> IO.never[Unit]).onCancel(
            finalizing.complete(()).void *> release.get *> stopped.complete(()).void
          )
        def close = IO.unit
      control <- TestControl.execute(Peers.typed {
        Mux.resource(bearer, Set(1)).use { mux =>
          for
            channel <- mux.channel(1)
            _ <- channel.send(hex("01"))
            reason <- mux.closed
            cleaned <- stopped.tryGet
            // Inspect inside use: resource release could otherwise hide early publication.
            _ <- published.complete(reason -> cleaned)
          yield ()
        }
      })
      _ <- Resource
        .make(IO.pure(control))(_ => release.complete(()).void *> control.tickAll)
        .use { clock =>
          for
            _ <- clock.tick
            entered <- writing.tryGet
            _ <- IO(assertEquals(entered, Some(())))
            _ <- clock.advanceAndTick(30.seconds)
            cancelling <- finalizing.tryGet
            early <- published.tryGet
            _ <- IO {
              assertEquals(cancelling, Some(()))
              assertEquals(early, None, "the write finalizer is still legitimately blocked")
            }
            _ <- release.complete(())
            _ <- clock.tickAll
            observed <- published.tryGet
            result <- clock.results
            _ <- IO {
              assertEquals(
                observed,
                Some(NetworkError.SduTimeout(NetworkError.SduOperation.Write) -> Some(()))
              )
              assert(
                result.exists {
                  case Outcome.Succeeded(Right(())) => true
                  case _                            => false
                },
                result.toString
              )
            }
          yield ()
        }
    yield ()

  test("receive deadline joins cancellation before returning and preserves the partial message"):
    TestControl
      .executeEmbed {
        Peers.typed {
          Peers.linked(protocols = Set(1)).use { (client, server) =>
            for
              c <- client.channel(1)
              s <- server.channel(1)
              cancelled <- Deferred[IO, Unit]
              _ <- c.send(hex("0102"))
              _ <- c.flush
              expired <- Peers.typed {
                Timed.within(1.second, 1, "partial")(
                  s.recv(65536)(exactly(4)).onCancel(cancelled.complete(()).void)
                )
              }
              cleaned <- cancelled.tryGet
              _ <- IO {
                assertEquals(expired, Left(NetworkError.Timeout(1, "partial")))
                assertEquals(cleaned, Some(()), "cancellation has completed before timeout returns")
              }
              // Immediate reuse must acquire the same receive lock and retain its buffered prefix.
              resumed <- (s.recv(65536)(exactly(4)), c.send(hex("0304"))).parTupled.map(_._1)
              _ <- c.send(hex("0506"))
              next <- s.recv(65536)(exactly(2))
            yield (resumed, next)
          }
        }
      }
      .map(result => assertEquals(result, Right((hex("01020304"), hex("0506")))))
