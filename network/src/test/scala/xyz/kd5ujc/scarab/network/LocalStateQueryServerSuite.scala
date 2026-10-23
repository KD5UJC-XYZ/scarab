// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Outcome, Ref, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Handshake, LocalStateQuery, LocalTxSubmission, NtcVersion}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Role, UInt64}
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Failure, Message, State, Target}
import munit.CatsEffectSuite
import scala.concurrent.duration.*

/** A node-to-client LocalStateQuery client against the server over an in-memory bearer, with a toy
  * ledger: the "state" is a number, a query answers with it, and the chain has three points.
  */
class LocalStateQueryServerSuite extends FailsOnUncaught:
  private val magic = 42L
  private def point(n: Int): Point =
    Point.Block(UInt64.saturating(n), Bytes(Vector.fill(32)(n.toByte)))
  private def item(n: Int): Bytes = Bytes(Vector(0x18, n.toByte))

  /** A `#6.24(bytes)` item of `size` bytes: a `GetCBOR` answer, or a query that carries one. */
  private def wrapped(size: Int): Bytes =
    val out = new Array[Byte](7 + size)
    Array(0xd8, 0x18, 0x5a, size >>> 24, (size >>> 16) & 255, (size >>> 8) & 255, size & 255)
      .map(_.toByte)
      .copyToArray(out)
    Bytes.wrap(out)

  /** An answer of tens of megabytes, as `GetCBOR DebugEpochState` on preprod (32.5 MB). */
  private val large: Bytes = wrapped(40 * 1024 * 1024)

  /** A query over the 2.5 MB bound of a node-to-node message. */
  private val largeQuery: Bytes = wrapped(3 * 1024 * 1024)

  /** The chain `point(1)`, `point(2)`, `point(3)` (tip); the immutable tip is `point(1)`; the state
    * at a point is its number. `acquired` records the targets asked for.
    */
  private final class Toy(
      val acquired: Ref[IO, Vector[Target]],
      val tip: Ref[IO, Int],
      val versions: Ref[IO, Vector[NtcVersion]],
      val released: Ref[IO, Int],
      val cancelServer: Ref[IO, Option[IO[Unit]]]
  ):
    val source: LocalStateQueryServer.Source[IO, Nothing] =
      new LocalStateQueryServer.Source[IO, Nothing]:
        override def forVersion(version: NtcVersion): LocalStateQueryServer.Source[IO, Nothing] =
          new LocalStateQueryServer.Source[IO, Nothing]:
            def acquire(target: Target) =
              Resource.eval(versions.update(_ :+ version)) *> source.acquire(target)

        def acquire(target: Target) =
          Resource
            .eval(acquired.update(_ :+ target) *> tip.get.map { current =>
              val chosen: Either[Failure, Int] = target match
                case Target.VolatileTip  => Right(current)
                case Target.ImmutableTip => Right(1)
                case Target.Specific(p) =>
                  (1 to current).find(point(_) == p).toRight {
                    if p == Point.Origin then Failure.PointTooOld else Failure.PointNotOnChain
                  }
              chosen.leftMap(LocalStateQueryServer.AcquireFailure.Rejected(_)).map { state =>
                new LocalStateQueryServer.Session[IO, Nothing]:
                  def answer(query: Bytes) =
                    IO.pure(
                      if query == Bytes(Vector(0x01)) then Right(Right(item(state)))
                      else if query == Bytes(Vector(0x03)) then Right(Right(large))
                      else if query == largeQuery then Right(Right(item(state)))
                      else Right(Left(LocalStateQueryServer.Refusal.Unsupported("unknown query")))
                    )
              }
            })
            .flatMap {
              case Left(failure) =>
                Resource.pure[IO, Either[
                  LocalStateQueryServer.AcquireFailure[Nothing],
                  LocalStateQueryServer.Session[IO, Nothing]
                ]](Left(failure))
              case Right(session) =>
                Resource.make(
                  IO.pure(
                    Right(session): Either[
                      LocalStateQueryServer.AcquireFailure[Nothing],
                      LocalStateQueryServer.Session[IO, Nothing]
                    ]
                  )
                )(_ => released.update(_ + 1))
            }

  private def toy: IO[Toy] =
    (
      Ref.of[IO, Vector[Target]](Vector.empty),
      Ref.of[IO, Int](3),
      Ref.of[IO, Vector[NtcVersion]](Vector.empty),
      Ref.of[IO, Int](0),
      Ref.of[IO, Option[IO[Unit]]](None)
    ).mapN(new Toy(_, _, _, _, _))

  private def send(ch: MuxChannel[IO], state: State, m: Message)(using
      Handle[IO, NetworkError]
  ): IO[Unit] =
    IO.fromEither(
      LocalStateQuery
        .encode(state, Role.Client, m)
        .left
        .map(error => RuntimeException(error.message))
    ).flatMap(ch.send)

  private def receive(ch: MuxChannel[IO], state: State)(using Handle[IO, NetworkError]) =
    ch.recv(LocalStateQuery.MaxResultBytes)(LocalStateQuery.decodePrefix(state, Role.Server, _))

  private def session[A](t: Toy, versions: Vector[Int] = Vector(16, 23))(
      body: (MuxChannel[IO], MuxChannel[IO]) => Handle[IO, NetworkError] ?=> IO[A]
  ): IO[(A, String)] =
    Resource.eval(Bearer.inMemory[IO]()).use { (clientEnd, serverEnd) =>
      Handle
        .allow[NetworkError](
          NodeToClientServer
            .accept(
              serverEnd,
              magic,
              _ => IO.pure(LocalTxSubmissionServer.Response.Accepted),
              Some(t.source)
            )
            .map(_.message)
        )
        .rescue(e => IO.pure(e.message))
        .start
        .flatMap { server =>
          t.cancelServer.set(Some(server.cancel)) *>
            Handle
              .allow[NetworkError] {
                Mux
                  .resource(
                    clientEnd,
                    Set(0, 6, 7),
                    Mux.Config.nodeToClient.copy(role = Mux.Role.Initiator)
                  )
                  .use { mux =>
                    for
                      hs <- mux.channel(0)
                      _ <- HandshakeClient.run(
                        hs,
                        versions.map(v => v -> Handshake.Data(magic)),
                        suite = Handshake.Suite.NodeToClient
                      )
                      lsq <- mux.channel(7)
                      tx <- mux.channel(6)
                      out <- body(lsq, tx)
                      // finish the submission protocol too, so the server ends by itself
                      done <- IO.fromEither(
                        LocalTxSubmission
                          .encode(
                            LocalTxSubmission.State.Idle,
                            Role.Client,
                            LocalTxSubmission.Message.Done
                          )
                          .left
                          .map(error => RuntimeException(error.message))
                      )
                      _ <- tx.send(done)
                      _ <- lsq.flush
                      _ <- tx.flush
                    yield out
                  }
              }
              .rescue(e => IO.raiseError[A](RuntimeException(e.message)))
              .flatMap(out =>
                server.join.flatMap {
                  case Outcome.Succeeded(result) => result.map(out -> _)
                  case Outcome.Errored(error)    => IO.raiseError(error)
                  case Outcome.Canceled()        => IO.pure(out -> "server canceled")
                }
              )
        }
    }

  test("acquire, query, re-acquire, fail on an unknown point, release, done") {
    toy.flatMap { t =>
      session(t) { (ch, _) =>
        for
          _ <- send(ch, State.Idle, Message.Acquire(Target.VolatileTip))
          a1 <- receive(ch, State.Acquiring)
          _ <- send(ch, State.Acquired, Message.Query(Bytes(Vector(0x01))))
          r1 <- receive(ch, State.Querying)
          _ <- send(ch, State.Acquired, Message.ReAcquire(Target.Specific(point(2))))
          a2 <- receive(ch, State.Acquiring)
          _ <- send(ch, State.Acquired, Message.Query(Bytes(Vector(0x01))))
          r2 <- receive(ch, State.Querying)
          // a point the chain does not have: the failure returns the protocol to Idle
          _ <- send(ch, State.Acquired, Message.ReAcquire(Target.Specific(point(9))))
          f1 <- receive(ch, State.Acquiring)
          _ <- send(ch, State.Idle, Message.Acquire(Target.ImmutableTip))
          a3 <- receive(ch, State.Acquiring)
          _ <- send(ch, State.Acquired, Message.Query(Bytes(Vector(0x01))))
          r3 <- receive(ch, State.Querying)
          _ <- send(ch, State.Acquired, Message.Release)
          _ <- send(ch, State.Idle, Message.Acquire(Target.Specific(Point.Origin)))
          f2 <- receive(ch, State.Acquiring)
          _ <- send(ch, State.Idle, Message.Done)
        yield (a1, r1, a2, r2, f1, a3, r3, f2)
      }.flatMap { case ((a1, r1, a2, r2, f1, a3, r3, f2), ended) =>
        t.released.get.map { released =>
          assertEquals(a1, Message.Acquired)
          assertEquals(r1, Message.Result(item(3)))
          assertEquals(a2, Message.Acquired)
          assertEquals(r2, Message.Result(item(2)))
          assertEquals(f1, Message.Failed(Failure.PointNotOnChain))
          assertEquals(a3, Message.Acquired)
          assertEquals(r3, Message.Result(item(1)))
          assertEquals(f2, Message.Failed(Failure.PointTooOld))
          assertEquals(released, 3)
          assert(ended.nonEmpty)
        }
      }
    }
  }

  test(
    "a 40 MiB answer reaches the client through the node-to-client mux in bounded time (#353, #356)"
  ) {
    toy
      .flatMap { t =>
        session(t) { (ch, _) =>
          for
            _ <- send(ch, State.Idle, Message.Acquire(Target.VolatileTip))
            _ <- receive(ch, State.Acquiring)
            _ <- send(ch, State.Acquired, Message.Query(Bytes(Vector(0x03))))
            answer <- ch.recv(LocalStateQuery.MaxResultBytes)(
              LocalStateQuery.decodePrefix(State.Querying, Role.Server, _)
            )
            _ <- send(ch, State.Acquired, Message.Release)
            _ <- send(ch, State.Idle, Message.Done)
          yield answer
        }.map { case (answer, _) => assertEquals(answer, Message.Result(large)) }
      }
      .timeout(60.seconds)
  }

  test("a client query over 2.5 MB reaches the server through the node-to-client mux (#356)") {
    toy
      .flatMap { t =>
        session(t) { (ch, _) =>
          for
            _ <- send(ch, State.Idle, Message.Acquire(Target.VolatileTip))
            _ <- receive(ch, State.Acquiring)
            _ <- send(ch, State.Acquired, Message.Query(largeQuery))
            answer <- receive(ch, State.Querying)
            _ <- send(ch, State.Acquired, Message.Release)
            _ <- send(ch, State.Idle, Message.Done)
          yield answer
        }.map { case (answer, ended) =>
          assertEquals(answer, Message.Result(item(3)))
          assert(!ended.contains("limit"), ended)
        }
      }
      .timeout(60.seconds)
  }

  test("a state stays acquired while the chain moves on") {
    toy.flatMap { t =>
      session(t) { (ch, _) =>
        for
          _ <- send(ch, State.Idle, Message.Acquire(Target.VolatileTip))
          _ <- receive(ch, State.Acquiring)
          _ <- t.tip.set(1) // the chain is rolled back to the immutable tip
          _ <- send(ch, State.Acquired, Message.Query(Bytes(Vector(0x01))))
          held <- receive(ch, State.Querying)
          _ <- send(ch, State.Acquired, Message.ReAcquire(Target.VolatileTip))
          _ <- receive(ch, State.Acquiring)
          _ <- send(ch, State.Acquired, Message.Query(Bytes(Vector(0x01))))
          moved <- receive(ch, State.Querying)
          _ <- send(ch, State.Acquired, Message.Release)
          _ <- send(ch, State.Idle, Message.Done)
        yield (held, moved)
      }.map { case ((held, moved), _) =>
        assertEquals(held, Message.Result(item(3)))
        assertEquals(moved, Message.Result(item(1)))
      }
    }
  }

  test("a query the source does not answer ends the connection, naming the refusal") {
    toy.flatMap { t =>
      session(t) { (ch, _) =>
        for
          _ <- send(ch, State.Idle, Message.Acquire(Target.VolatileTip))
          _ <- receive(ch, State.Acquiring)
          _ <- send(ch, State.Acquired, Message.Query(Bytes(Vector(0x02))))
        yield ()
      }.flatMap { case (_, ended) =>
        t.released.get.map { releases =>
          assert(ended.contains("query not answered: unsupported query unknown query"), ended)
          assertEquals(releases, 1)
        }
      }
    }
  }

  test("canceling the acquired server fiber releases its session resource") {
    toy.flatMap { t =>
      session(t) { (ch, _) =>
        for
          _ <- send(ch, State.Idle, Message.Acquire(Target.VolatileTip))
          _ <- receive(ch, State.Acquiring)
          cancel <- t.cancelServer.get.map(_.getOrElse(IO.unit))
          _ <- cancel
          // the release is recorded by the cancelled fiber, so wait for the record, not for 20 ms
          released <- Wait.until(t.released.get)(_ == 1)
        yield assertEquals(released, 1)
      }.map { case (_, ended) => assertEquals(ended, "server canceled") }
    }
  }

  NtcVersion.All.foreach { version =>
    test(s"the negotiated ${version.number} governs every acquired session") {
      toy.flatMap { t =>
        session(t, Vector(version.number)) { (ch, _) =>
          for
            _ <- send(ch, State.Idle, Message.Acquire(Target.VolatileTip))
            _ <- receive(ch, State.Acquiring)
            _ <- send(ch, State.Acquired, Message.ReAcquire(Target.ImmutableTip))
            _ <- receive(ch, State.Acquiring)
            _ <- send(ch, State.Acquired, Message.Release)
            _ <- send(ch, State.Idle, Message.Done)
          yield ()
        } *> t.versions.get.map(vs => assertEquals(vs, Vector(version, version)))
      }
    }
  }
