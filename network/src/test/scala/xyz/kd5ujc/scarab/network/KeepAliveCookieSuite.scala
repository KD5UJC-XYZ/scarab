// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.Rng
import xyz.kd5ujc.scarab.protocol.{KeepAlive, ProtocolError}

import Peers.*

/** The KeepAlive client draws each cookie from a generator, as the reference's client draws each
  * from its `StdGen` (ouroboros-network 4646039f, `KeepAlive.hs` 55-59 and 100-102), rather than
  * counting from 0. These tests show that answers to the counter's cookies written ahead of the
  * requests end the protocol with a cookie mismatch (`Protocol/KeepAlive/Client.hs` 49-53) and give
  * no round-trip sample, and that a client with a fresh generator is not answered by the cookies
  * recorded on another connection. They do not show more: a cookie is 16 bits wide, so a guess
  * still answers one ping in 65,536 ahead of its request.
  */
class KeepAliveCookieSuite extends FailsOnUncaught:
  private val generator = Rng(0x5eedcafef00dL)
  private val settings =
    KeepAliveClient.Settings(cookies = KeepAliveClient.Cookies.Shared(generator))

  /** The generator's first `n` cookies, as `random` draws a `Word16` from a `StdGen`: the low 16
    * bits of each next 64.
    */
  private def draws(n: Int): List[Int] =
    List.unfold((generator, n)) { (rng, left) =>
      Option.when(left > 0) {
        val (bits, next) = rng.nextLong
        ((bits & 0xffffL).toInt, (next, left - 1))
      }
    }

  private def cookie(n: Int): KeepAlive.Cookie = KeepAlive.Cookie.from(n).toOption.get

  private def response(n: Int): IO[Bytes] =
    IO.fromEither(
      KeepAlive
        .encode(
          KeepAlive.State.Server,
          KeepAlive.Role.Server,
          KeepAlive.Message.Response(cookie(n))
        )
        .leftMap(e => new IllegalStateException(e.message))
    )

  /** A peer that answers each request with its own cookie, and records the cookies it was sent. */
  private def echoing(channel: MuxChannel[IO], seen: Ref[IO, Vector[Int]])(using Net): IO[Unit] =
    channel
      .recv(64)(KeepAlive.incremental(KeepAlive.State.Client, KeepAlive.Role.Client))
      .flatMap {
        case KeepAlive.Message.Request(c) =>
          seen.update(_ :+ c.value) *> response(c.value).flatMap(channel.send)
        case _ => IO.unit
      }
      .foreverM

  /** A peer that never reads a request: it sends the answers to `cookies` at once. */
  private def ahead(channel: MuxChannel[IO], cookies: List[Int] = (0 until 5).toList)(using
      Net
  ): IO[Unit] =
    cookies.traverse_(n => response(n).flatMap(channel.send)) *> channel.flush

  private def connected[A](
      peer: MuxChannel[IO] => Net ?=> IO[Unit]
  )(body: (MuxChannel[IO], Mux[IO]) => Net ?=> IO[A]): IO[Either[NetworkError, A]] =
    TestControl.executeEmbed(typed {
      linked(protocols = Set(Protocols.KeepAlive)).use { (client, server) =>
        for
          c <- client.channel(Protocols.KeepAlive)
          s <- server.channel(Protocols.KeepAlive)
          _ <- typed(peer(s)).start
          result <- body(c, client)
        yield result
      }
    })

  private val firstMismatch: Either[NetworkError, Nothing] =
    Left(
      NetworkError.IllegalTransition(
        Protocols.KeepAlive,
        ProtocolError.KeepAliveCookieMismatch(cookie(draws(1).head), cookie(0))
      )
    )

  test("with a shared generator the cookies are its draws in order, and an echoing peer answers"):
    Ref
      .of[IO, Vector[Int]](Vector.empty)
      .flatMap { seen =>
        connected(echoing(_, seen)) { (channel, _) =>
          KeepAliveClient(channel, settings).flatMap(api => api.ping.replicateA(5))
        }.flatMap(result => seen.get.map((result, _)))
      }
      .map { (result, seen) =>
        assertEquals(result.map(_.size), Right(5))
        assertEquals(seen.toList, draws(5))
      }

  test("answers to the counter's cookies 0 to 4, sent ahead, end the first ping with a mismatch"):
    assert(!(0 until 5).contains(draws(1).head), s"the first draw ${draws(1).head} is a counter's")
    connected(ahead(_))((channel, _) => KeepAliveClient(channel, settings).flatMap(_.ping))
      .map(result => assertEquals(result, firstMismatch))

  test(
    "answers to the counter's cookies, sent ahead, give the running client no sample, and end it"
  ):
    connected(ahead(_)) { (channel, mux) =>
      for
        samples <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
        outcome <- Deferred[IO, Either[NetworkError, Unit]]
        ended <- KeepAliveClient
          .controlled(
            channel,
            mux,
            settings,
            Some(rtt => samples.update(_ :+ rtt)),
            outcome.complete(_).void,
            IO.pure(ProtocolResults.Control.Continue),
            IO.never
          )
          .surround(outcome.get)
        recorded <- samples.get
      yield (ended, recorded)
    }.map(result => assertEquals(result, Right((firstMismatch, Vector.empty[FiniteDuration]))))

  test("an echoing peer gives the running client a sample for each ping"):
    (Ref.of[IO, Vector[Int]](Vector.empty), Ref.of[IO, Int](0)).tupled
      .flatMap { (seen, pings) =>
        connected(echoing(_, seen)) { (channel, mux) =>
          for
            samples <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
            outcome <- Deferred[IO, Either[NetworkError, Unit]]
            ended <- KeepAliveClient
              .controlled(
                channel,
                mux,
                settings,
                Some(rtt => samples.update(_ :+ rtt)),
                outcome.complete(_).void,
                pings
                  .getAndUpdate(_ + 1)
                  .map(n =>
                    if n < 3 then ProtocolResults.Control.Continue
                    else ProtocolResults.Control.Terminate
                  ),
                IO.never
              )
              .surround(outcome.get)
            recorded <- samples.get
          yield (ended, recorded.size)
        }.flatMap(result => seen.get.map((result, _)))
      }
      .map { (result, seen) =>
        assertEquals(result, Right((Right(()), 3)))
        assertEquals(seen.toList, draws(3))
      }

  test("with fresh generators, the cookies recorded on one connection do not answer the next's"):
    // the second client is answered with the five cookies the first one sent: with independently
    // seeded clients it meets a mismatch unless both drew the same 64-bit seed
    Ref
      .of[IO, Vector[Int]](Vector.empty)
      .flatMap { seen =>
        for
          first <- connected(echoing(_, seen)) { (channel, _) =>
            KeepAliveClient(channel).flatMap(_.ping.replicateA(5))
          }
          recorded <- seen.get
          second <- connected(ahead(_, recorded.toList)) { (channel, _) =>
            KeepAliveClient(channel).flatMap(_.ping.replicateA(5))
          }
        yield (first.map(_.size), recorded.size, second)
      }
      .map { (first, recorded, second) =>
        assertEquals(first, Right(5))
        assertEquals(recorded, 5)
        second match
          case Left(
                NetworkError.IllegalTransition(
                  Protocols.KeepAlive,
                  ProtocolError.KeepAliveCookieMismatch(_, _)
                )
              ) =>
            ()
          case other => fail(s"the recorded cookies answered the second connection: $other")
      }
