// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Outcome, Ref, Resource}
import cats.effect.testkit.TestControl
import fs2.{Chunk, Stream}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Mux as WireMux

class MuxEgressSuite extends FailsOnUncaught:
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.message), identity)
  private def frame(bytes: Chunk[Byte]): WireMux.Sdu =
    WireMux.decode(Bytes.wrap(bytes.toArray)).fold(e => fail(e.message), identity)
  private def finished[A](clock: TestControl[A]): IO[A] = clock.results.map {
    case Some(Outcome.Succeeded(value)) => value
    case other                          => fail(s"controlled work did not succeed: $other")
  }

  test("SDU turns preserve duplex FIFO and a global flush fence excludes later traffic"):
    for
      entered <- Deferred[IO, Unit]
      first <- Deferred[IO, Unit]
      allowLate <- Deferred[IO, Unit]
      lastPrior <- Deferred[IO, Unit]
      lateEntered <- Deferred[IO, Unit]
      last <- Deferred[IO, Unit]
      snapshot <- Deferred[IO, Vector[WireMux.Sdu]]
      trace <- Ref.of[IO, Vector[WireMux.Sdu]](Vector.empty)
      bearer = new Bearer[IO]:
        def reads = Stream.never[IO]
        def close = IO.unit
        def write(bytes: Chunk[Byte]) =
          val sdu = frame(bytes)
          val wait = sdu.payload match
            case p if p == hex("0001") => entered.complete(()).void *> first.get
            case p if p == hex("0607") => lastPrior.get
            case p if p == hex("0c0d") => lateEntered.complete(()).void *> last.get
            case _                     => IO.unit
          wait *> trace.update(_ :+ sdu)
      clock <- TestControl.execute(Peers.typed {
        Mux
          .resource(
            bearer,
            Set(2, 3),
            Mux.Config(duplex = true, maxSduPayload = 2, egressQueueMessages = 8)
          )
          .use { mux =>
            for
              bulk <- mux.channel(2)
              control <- mux.channel(3)
              duplex <- mux.channel(2, Mux.Role.Responder)
              _ <- bulk.send(hex("000102030405"))
              _ <- entered.get
              _ <- bulk.send(hex("0607"))
              _ <- control.send(hex("0809"))
              _ <- duplex.send(hex("0a0b"))
              result <- Resource
                .make((control.flush *> trace.get.flatMap(snapshot.complete)).start)(_.cancel)
                .use { fence =>
                  for
                    _ <- allowLate.get
                    _ <- control.send(hex("0c0d"))
                    _ <- fence.joinWithNever
                    _ <- control.flush
                    written <- trace.get
                  yield written
                }
            yield result
          }
      })
      _ <- Resource
        .make(IO.pure(clock))(_ =>
          first.complete(()).void *> allowLate.complete(()).void *> lastPrior
            .complete(())
            .void *> last.complete(()).void *> clock.tickAll
        )
        .use { c =>
          for
            // Quiescence puts flush in the inbox while the first SDU is still blocked.
            _ <- c.tick
            _ <- allowLate.complete(()) *> c.tick
            _ <- first.complete(()) *> c.tick
            early <- snapshot.tryGet
            prematureLate <- lateEntered.tryGet
            _ <- IO {
              assertEquals(early, None, "the last pre-fence message has not finished writing")
              assertEquals(
                prematureLate,
                None,
                "post-fence bytes cannot pass its unfinished prefix"
              )
            }
            _ <- lastPrior.complete(()) *> c.tick
            observed <- snapshot.tryGet
            late <- lateEntered.tryGet
            _ <- IO {
              val expected = Vector(
                (2, WireMux.Direction.Initiator, hex("0001")),
                (2, WireMux.Direction.Initiator, hex("0203")),
                (3, WireMux.Direction.Initiator, hex("0809")),
                (2, WireMux.Direction.Responder, hex("0a0b")),
                (2, WireMux.Direction.Initiator, hex("0405")),
                (2, WireMux.Direction.Initiator, hex("0607"))
              )
              assertEquals(
                observed.map(_.map(s => (s.protocol, s.direction, s.payload))),
                Some(expected)
              )
              assertEquals(late, Some(()), "flush completed without waiting for later traffic")
            }
            _ <- last.complete(()) *> c.tickAll
            result <- finished(c)
            _ <- IO(assertEquals(result.map(_.lastOption.map(_.payload)), Right(Some(hex("0c0d")))))
          yield ()
        }
    yield ()

  test(
    "staged messages retain admission credits and cancelled waiters neither send nor leak credit"
  ):
    for
      entered <- Deferred[IO, Unit]
      first <- Deferred[IO, Unit]
      second <- Deferred[IO, Unit]
      cancel <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      replacement <- Deferred[IO, Unit]
      trace <- Ref.of[IO, Vector[WireMux.Sdu]](Vector.empty)
      bearer = new Bearer[IO]:
        def reads = Stream.never[IO]
        def close = IO.unit
        def write(bytes: Chunk[Byte]) =
          val sdu = frame(bytes)
          val wait = sdu.payload match
            case p if p == hex("0001") => entered.complete(()).void *> first.get
            case p if p == hex("0203") => second.get
            case _                     => IO.unit
          wait *> trace.update(_ :+ sdu)
      clock <- TestControl.execute(Peers.typed {
        Mux
          .resource(bearer, Set(2, 3), Mux.Config(maxSduPayload = 2, egressQueueMessages = 1))
          .use { mux =>
            for
              bulk <- mux.channel(2)
              control <- mux.channel(3)
              _ <- bulk.send(hex("000102030405"))
              _ <- entered.get
              _ <- bulk.send(hex("0607"))
              result <- Resource.make(Peers.typed(control.send(hex("0809"))).start)(_.cancel).use {
                waiting =>
                  for
                    _ <- cancel.get *> waiting.cancel *> cancelled.complete(())
                    _ <- control.send(hex("0a0b")) *> replacement.complete(())
                    _ <- bulk.flush
                    written <- trace.get
                  yield written
              }
            yield result
          }
      })
      _ <- Resource
        .make(IO.pure(clock))(_ =>
          first.complete(()).void *> second.complete(()).void *> cancel
            .complete(())
            .void *> clock.tickAll
        )
        .use { c =>
          for
            _ <- c.tick
            _ <- cancel.complete(()) *> c.tick
            stopped <- cancelled.tryGet
            before <- replacement.tryGet
            _ <- first.complete(()) *> c.tick
            staged <- replacement.tryGet
            _ <- IO {
              assertEquals(stopped, Some(()))
              assertEquals(before, None)
              assertEquals(
                staged,
                None,
                "draining the inbox into staged work must not free capacity"
              )
            }
            _ <- second.complete(()) *> c.tickAll
            admitted <- replacement.tryGet
            result <- finished(c)
            _ <- IO {
              assertEquals(
                admitted,
                Some(()),
                "the cancelled waiter did not retain an admission credit"
              )
              val written = result.fold(e => fail(e.message), identity)
              assertEquals(
                written.filter(_.protocol == 2).foldLeft(Bytes.empty)(_ ++ _.payload),
                hex("0001020304050607")
              )
              assertEquals(written.filter(_.protocol == 3).map(_.payload), Vector(hex("0a0b")))
            }
          yield ()
        }
    yield ()

  test("negative queue capacity is rejected instead of constructing a zero-credit outbox"):
    IO.defer(MuxEgress.create[IO](-1, 2)).attempt.map {
      case Left(_: IllegalArgumentException) => ()
      case other => fail(s"expected the concurrency primitive's capacity rejection: $other")
    }
