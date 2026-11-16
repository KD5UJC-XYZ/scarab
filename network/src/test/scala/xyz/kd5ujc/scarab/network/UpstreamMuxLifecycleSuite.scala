// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

/** Applicable observable contracts of the pinned Test.Mux close/trailing properties. Haskell's
  * mini-protocol activation API and exact scheduling are not part of this comparison.
  */
class UpstreamMuxLifecycleSuite extends FailsOnUncaught:
  private enum Fault:
    case CleanShutdown, CloseOnWrite, CloseOnRead

  private final case class CloseCase(
      seed: Int,
      fault: Fault,
      sduSize: Int,
      requests: Vector[Bytes],
      responses: Vector[Bytes]
  )
  private final case class TrailingCase(
      seed: Int,
      reminder: Bytes,
      received: Bytes,
      expected: Bytes
  )

  private def hex(value: String): Bytes =
    Bytes.fromHex(value).fold(error => fail(error.message), identity)

  private def exactly(n: Int)(bytes: Bytes): DecodeResult[Bytes] =
    if bytes.size >= n then DecodeResult.Decoded(bytes.take(n), n)
    else DecodeResult.NeedMore

  private def rows: Vector[Vector[String]] =
    val path = "/upstream/mux-lifecycle/lifecycle.tsv"
    val input = Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing $path"))
    val bytes =
      try input.readAllBytes()
      finally input.close()
    val digest =
      MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
    assertEquals(digest, "e6a5dbbcbf7e80abf10d071cb14215cf5ca02dfce0a27d05ffadcdd3e529beaf")
    new String(bytes, StandardCharsets.UTF_8).linesIterator.map(_.split("\t", -1).toVector).toVector

  private def list(value: String): Vector[Bytes] =
    if value.isEmpty then Vector.empty else value.split(",").toVector.map(hex)

  private def closeCases: Vector[CloseCase] = rows.filter(_.head == "mux-close-v1").map { row =>
    assertEquals(row.size, 14)
    assertEquals(row.takeRight(2), Vector("pass", "pass"), s"native IO/IOSim at seed ${row(1)}")
    val fault = Fault.valueOf(row(4))
    val original = if row(6).isEmpty then Vector.empty else row(6).split(",").toVector
    val count = if fault == Fault.CloseOnWrite then (original.size - 1).max(0) else original.size
    val requests = list(row(10))
    val responses = list(row(11))
    assertEquals(requests.size, count)
    assertEquals(responses.size, count)
    assertEquals(
      row(8),
      if fault == Fault.CleanShutdown || (original.isEmpty && fault == Fault.CloseOnRead) then
        "clean"
      else "early"
    )
    CloseCase(row(1).toInt, fault, row(5).toInt, requests, responses)
  }

  /** The most tasks a step of [[simulatedThenReal]] runs before every fiber is blocked: the largest
    * step of its programs here takes 5,578 (300 seeds of each, issue 1110), and a fiber that never
    * waits then fails the case in seconds, not at the suite's time limit.
    */
  private val Steps = 100000L

  /** Runs `program`, and `check` on its result, first on the simulated runtime of [[Settled]],
    * where a hang fails the case once every fiber is blocked, whatever the load of the machine, and
    * a failure prints the scheduling seed that replays it; then on the suite's runtime, whose
    * 5-second bound starts only once the same program has run in this JVM. The first run in a cold
    * JVM loads and first runs the code, which on a starved CPU can take longer than that bound
    * though nothing hangs (issue 1110).
    */
  private def simulatedThenReal[A](program: IO[A])(check: (String, A) => Unit): IO[Unit] =
    Settled.limited(Steps)(_ => program.map(check("simulated", _))) *>
      program.timeout(5.seconds).map(check("real", _))

  test("the close experiments are the 24 upstream seeds and cover all three faults"):
    val cases = closeCases
    assertEquals(cases.map(_.seed), (511001 to 511024).toVector)
    assertEquals(cases.map(_.fault).toSet, Fault.values.toSet)

  // One test per experiment, each with the suite's time limit to itself: run as one test, the 24
  // took most of that limit in a cold JVM on a starved CPU (issue 1110).
  for seed <- 511001 to 511024 do
    test(
      s"native close-on-write/read at upstream seed $seed preserves the complete observed " +
        "response prefix and terminates"
    ):
      val sample = closeCases.find(_.seed == seed).getOrElse(fail(s"no close case at seed $seed"))
      val exchange = Peers
        .typed {
          for
            pair <- Bearer.inMemory[IO]()
            (a, b) = pair
            result <- (
              Mux.resource(a, Set(1), Mux.Config(sduSize = sample.sduSize)),
              Mux.resource(
                b,
                Set(1),
                Mux.Config(role = Mux.Role.Responder, sduSize = sample.sduSize)
              )
            ).tupled.use { (client, server) =>
              for
                c <- client.channel(1)
                s <- server.channel(1)
                serving = Peers.typed {
                  sample.requests.zip(sample.responses).traverse_ { (request, response) =>
                    s.recv(65536)(exactly(request.size)).flatMap { received =>
                      IO(assertEquals(received, request)) *> s.send(response)
                    }
                  } *> s.recv(65536)(exactly(2))
                }
                outcome <- Resource.make(serving.start)(_.cancel).use { fiber =>
                  for
                    observed <- sample.requests.zip(sample.responses).traverse {
                      (request, response) =>
                        c.send(request) *> c.recv(65536)(exactly(response.size))
                    }
                    clean =
                      sample.fault == Fault.CleanShutdown || (sample.fault == Fault.CloseOnRead && sample.requests.isEmpty)
                    _ <- if clean then c.send(hex("8102")) *> c.flush else IO.unit
                    _ <- a.close
                    ended <- fiber.joinWithNever
                  yield (observed, ended, clean)
                }
              yield outcome
            }
          yield result
        }
      simulatedThenReal(exchange) { (runtime, result) =>
        val (observed, ended, clean) = result.fold(e => fail(s"$runtime: ${e.message}"), identity)
        assertEquals(observed, sample.responses, runtime)
        if clean then assertEquals(ended, Right(hex("8102")), runtime)
        else
          assertEquals(
            ended,
            Left(NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)),
            runtime
          )
      }

  private def trailingCases: Vector[TrailingCase] =
    rows.filter(_.head == "mux-trailing-v1").map { row =>
      assertEquals(row.size, 9)
      assertEquals(row.takeRight(2), Vector("pass", "pass"), s"native IO/IOSim at seed ${row(1)}")
      TrailingCase(row(1).toInt, hex(row(4)), hex(row(5)), hex(row(6)))
    }

  test("the trailing-byte experiments are the 24 upstream seeds"):
    assertEquals(trailingCases.map(_.seed), (511001 to 511024).toVector)

  // One test per experiment, as for the close experiments: run as one test, the 24 took up to 18 of
  // the suite's 30 seconds in a cold JVM on a starved CPU (issue 1110).
  for seed <- 511001 to 511024 do
    test(
      s"native trailing-byte order at upstream seed $seed survives ending and resuming a " +
        "channel consumer"
    ):
      val sample =
        trailingCases.find(_.seed == seed).getOrElse(fail(s"no trailing case at seed $seed"))
      val exchange = Peers
        .typed {
          Peers
            .linked(
              protocols = Set(1),
              client = Mux.Config(sduSize = 3),
              server = Mux.Config(role = Mux.Role.Responder, sduSize = 3)
            )
            .use { (client, server) =>
              for
                c <- client.channel(1)
                s <- server.channel(1)
                _ <- c.send(hex("01") ++ sample.reminder)
                _ <- s.recv(65536)(exactly(1))
                _ <- c.send(sample.received)
                resumed <- server.channel(1)
                got <- resumed.recv(65536)(exactly(sample.expected.size))
              yield got
            }
        }
      simulatedThenReal(exchange)((runtime, result) =>
        assertEquals(result, Right(sample.expected), runtime)
      )

  // Controlled local observations supplement, rather than impersonate, native scheduler tests.
  test("abort releases blocked send, flush and receives with the first typed reason"):
    TestControl
      .executeEmbed(
        Peers
          .typed {
            for
              writing <- Deferred[IO, Unit]
              readerStopped <- Deferred[IO, Unit]
              writerStopped <- Deferred[IO, Unit]
              bearer = new Bearer[IO]:
                def reads = Stream.never[IO].onFinalize(readerStopped.complete(()).void)
                def write(bytes: Chunk[Byte]) =
                  (writing.complete(()) *> IO.never).guarantee(writerStopped.complete(()).void)
                def close = IO.unit
              result <- Mux.resource(bearer, Set(1, 2), Mux.Config(egressBufferBytes = 2)).use {
                mux =>
                  for
                    a <- mux.channel(1)
                    b <- mux.channel(2)
                    _ <- a.send(hex("01"))
                    _ <- writing.get
                    _ <- a.send(hex("02"))
                    reason = NetworkError.IngressLimit(1, 65537)
                    results <- (
                      Peers.typed(a.send(hex("03"))),
                      a.flush,
                      Peers.typed(a.recv(10)(exactly(1))),
                      Peers.typed(b.recv(10)(exactly(1))),
                      (IO.sleep(1.millis) *> mux.abort(reason)) *> mux.abort(
                        NetworkError.BearerClosed(NetworkError.Closure.Aborted)
                      )
                    ).parTupled
                    closed <- mux.closed
                  yield (results._1, results._3, results._4, closed, reason)
              }
              _ <- readerStopped.get *> writerStopped.get
            yield result
          }
          .timeout(5.seconds)
      )
      .map { result =>
        val (send, receiveA, receiveB, closed, reason) = result.fold(e => fail(e.message), identity)
        assertEquals(send, Left(reason))
        assertEquals(receiveA, Left(reason))
        assertEquals(receiveB, Left(reason))
        assertEquals(closed, reason)
      }

  test("cancelling the owner joins both blocked Mux workers before the resource returns"):
    val cancelling = for
      ready <- Deferred[IO, Unit]
      reading <- Deferred[IO, Unit]
      writing <- Deferred[IO, Unit]
      readerStopped <- Deferred[IO, Unit]
      writerStopped <- Deferred[IO, Unit]
      bearer = new Bearer[IO]:
        def reads = (Stream.eval(reading.complete(())) >> Stream.never[IO])
          .onFinalize(readerStopped.complete(()).void)
        def write(bytes: Chunk[Byte]) =
          (writing.complete(()) *> IO.never).guarantee(writerStopped.complete(()).void)
        def close = IO.unit
      cancelled <- Resource
        .make(Peers.typed {
          Mux.resource(bearer, Set(1)).use { mux =>
            mux.channel(1).flatMap(_.send(hex("01"))) *> reading.get *> writing.get *>
              ready.complete(()) *> IO.never[Unit]
          }
        }.start)(_.cancel)
        .use { owner =>
          ready.get *> owner.cancel *> owner.join
        }
      reader <- readerStopped.tryGet
      writer <- writerStopped.tryGet
    yield (cancelled.isCanceled, reader, writer)
    simulatedThenReal(cancelling)((runtime, result) =>
      assertEquals(result, (true, Some(()), Some(())), runtime)
    )

  test("cancelling a partial receive releases its lock and preserves buffered bytes"):
    TestControl
      .executeEmbed(
        Peers
          .typed {
            Peers.linked(protocols = Set(1)).use { (client, server) =>
              for
                c <- client.channel(1)
                s <- server.channel(1)
                first <- Resource
                  .make(
                    Peers.typed(s.recv(65536)(exactly(4))).start
                  )(_.cancel)
                  .use { pending =>
                    // Virtual time advances only once runnable work has quiesced: the receive holds
                    // two buffered bytes and is waiting for more before it is cancelled.
                    c.send(hex("0102")) *> c.flush *> IO.sleep(1.millis) *>
                      pending.cancel.as(pending)
                  }
                _ <- c.send(hex("0304"))
                got <- s.recv(65536)(exactly(4))
                state <- first.join
              yield (got, state.isCanceled)
            }
          }
          .timeout(5.seconds)
      )
      .map(result => assertEquals(result, Right((hex("01020304"), true))))
