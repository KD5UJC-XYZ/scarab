// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Handshake, LocalStateQuery, LocalTxSubmission, NtcVersion}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Role, UInt64}
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Failure, Message, State, Target}
import munit.CatsEffectSuite

/** A node-to-client LocalStateQuery client against the server over an in-memory bearer, with a toy
  * ledger: the "state" is a number, a query answers with it, and the chain has three points.
  */
class LocalStateQueryServerSuite extends FailsOnUncaught:
  private val magic = 42L
  private def point(n: Int): Point =
    Point.Block(UInt64.saturating(n), Bytes(Vector.fill(32)(n.toByte)))
  private def item(n: Int): Bytes = Bytes(Vector(0x18, n.toByte))

  /** The chain `point(1)`, `point(2)`, `point(3)` (tip); the immutable tip is `point(1)`; the state
    * at a point is its number. `acquired` records the targets asked for.
    */
  private final class Toy(
      val acquired: Ref[IO, Vector[Target]],
      val tip: Ref[IO, Int],
      val versions: Ref[IO, Vector[NtcVersion]]
  ):
    val source: LocalStateQueryServer.Source[IO] = new LocalStateQueryServer.Source[IO]:
      override def forVersion(version: NtcVersion): LocalStateQueryServer.Source[IO] =
        new LocalStateQueryServer.Source[IO]:
          def acquire(target: Target) = versions.update(_ :+ version) *> source.acquire(target)

      def acquire(target: Target) =
        acquired.update(_ :+ target) *> tip.get.map { current =>
          val chosen: Either[Failure, Int] = target match
            case Target.VolatileTip  => Right(current)
            case Target.ImmutableTip => Right(1)
            case Target.Specific(p) =>
              (1 to current).find(point(_) == p).toRight {
                if p == Point.Origin then Failure.PointTooOld else Failure.PointNotOnChain
              }
          chosen.map { state =>
            new LocalStateQueryServer.Session[IO]:
              def answer(query: Bytes) =
                IO.pure(
                  if query == Bytes(Vector(0x01)) then Right(item(state))
                  else Left(LocalStateQueryServer.Refusal.Unsupported("unknown query"))
                )
          }
        }

  private def toy: IO[Toy] =
    (
      Ref.of[IO, Vector[Target]](Vector.empty),
      Ref.of[IO, Int](3),
      Ref.of[IO, Vector[NtcVersion]](Vector.empty)
    ).mapN(new Toy(_, _, _))

  private def send(ch: MuxChannel[IO], state: State, m: Message)(using
      Handle[IO, NetworkError]
  ): IO[Unit] =
    IO.fromEither(
      LocalStateQuery.encode(state, Role.Client, m).left.map(e => RuntimeException(e.toString))
    ).flatMap(ch.send)

  private def receive(ch: MuxChannel[IO], state: State)(using Handle[IO, NetworkError]) =
    ch.recv(LocalStateQuery.MaxMessageBytes)(LocalStateQuery.decodePrefix(state, Role.Server, _))

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
          Handle
            .allow[NetworkError] {
              Mux.resource(clientEnd, Set(0, 6, 7), Mux.Config()).use { mux =>
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
                      .map(e => RuntimeException(e.toString))
                  )
                  _ <- tx.send(done)
                  _ <- lsq.flush
                  _ <- tx.flush
                yield out
              }
            }
            .rescue(e => IO.raiseError[A](RuntimeException(e.message)))
            .flatMap(out => server.joinWithNever.map(out -> _))
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
      }.map { case ((a1, r1, a2, r2, f1, a3, r3, f2), ended) =>
        assertEquals(a1, Message.Acquired)
        assertEquals(r1, Message.Result(item(3)))
        assertEquals(a2, Message.Acquired)
        assertEquals(r2, Message.Result(item(2)))
        assertEquals(f1, Message.Failed(Failure.PointNotOnChain))
        assertEquals(a3, Message.Acquired)
        assertEquals(r3, Message.Result(item(1)))
        assertEquals(f2, Message.Failed(Failure.PointTooOld))
        assert(ended.nonEmpty)
      }
    }
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
      }.map { case (_, ended) =>
        assert(ended.contains("query not answered: unsupported query unknown query"), ended)
      }
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
