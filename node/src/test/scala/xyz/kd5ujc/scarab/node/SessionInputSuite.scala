// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Header, PeerError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.ChainSync

class SessionInputSuite extends FailsOnUncaught:
  private val event = SyncEvent.RollBackward(ChainSync.Point.Origin, ChainSync.Tip.Origin)
  private def peer(read: Raise[IO, PeerError] => IO[SyncEvent]): Peer[IO] = new Peer[IO]:
    def intersect(@scala.annotation.unused points: Vector[ChainSync.Point])(using
        Raise[IO, PeerError]
    ) = IO.pure(Some(ChainSync.Point.Origin))
    def next(using errors: Raise[IO, PeerError]) = read(errors)
    def fetch(@scala.annotation.unused header: Header)(using Raise[IO, PeerError]): IO[Bytes] =
      IO.raiseError(new AssertionError("no block request expected"))

  test(
    "reintersection joins the previous queue consumer before draining and resumes a fresh read"
  ) {
    TestControl.executeEmbed {
      for
        entered <- Deferred[IO, Unit]
        active <- Ref.of[IO, Boolean](false)
        count <- Ref.of[IO, Int](0)
        source = peer(_ =>
          count.getAndUpdate(_ + 1).flatMap {
            case 0 =>
              (active.set(true) *> entered.complete(()).void *> IO.never[SyncEvent])
                .guarantee(active.set(false))
            case _ => IO.pure(event)
          }
        )
        _ <- SessionInput.resource(source).use { input =>
          for
            reader <- Fault.fiber(input.next).start
            _ <- entered.get
            _ <- Fault
              .fiber(input.reintersect(active.get.map(value => assert(!value))))
              .flatMap(result => IO(assertEquals(result, Right(()))))
            result <- reader.joinWithNever
            _ <- result match
              case Right(read) => input.current(read).map(current => assert(current))
              case Left(fault) => IO(fail(fault.toString))
            calls <- count.get
            _ <- IO(assertEquals(calls, 2))
          yield ()
        }
      yield ()
    }
  }

  test("an already dequeued old event cannot cross the new intersection epoch") {
    SessionInput.resource(peer(_ => IO.pure(event))).use { input =>
      Fault
        .fiber {
          for
            old <- input.next
            _ <- input.reintersect(IO.unit)
            stale <- input.current(old)
            fresh <- input.next
            current <- input.current(fresh)
          yield
            assert(!stale)
            assert(current)
        }
        .flatMap(result => IO(assertEquals(result, Right(()))))
    }
  }

  test("ordinary reselection leaves a pending read running") {
    TestControl.executeEmbed {
      for
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        cancelled <- Ref.of[IO, Int](0)
        calls <- Ref.of[IO, Int](0)
        source = peer(_ =>
          (calls.update(_ + 1) *> entered.complete(()).void *> release.get.as(event))
            .onCancel(cancelled.update(_ + 1))
        )
        _ <- SessionInput.resource(source).use { input =>
          for
            reader <- Fault.fiber(input.next).start
            _ <- entered.get
            before <- cancelled.get
            _ <- IO(assertEquals(before, 0))
            _ <- release.complete(())
            result <- reader.joinWithNever
            _ <- result match
              case Right(read) => input.current(read).map(value => assert(value))
              case Left(fault) => IO(fail(fault.toString))
            after <- cancelled.get
            reads <- calls.get
            _ <- IO { assertEquals(after, 0); assertEquals(reads, 1) }
          yield ()
        }
      yield ()
    }
  }

  test("a completed typed failure is retained when reintersection pauses its consumer") {
    val failure = PeerError.NoIntersection
    TestControl.executeEmbed {
      for
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        completed <- Deferred[IO, Unit]
        restarted <- Ref.of[IO, Boolean](false)
        source = peer(errors =>
          (entered.complete(()).void *> release.get *> errors.raise[PeerError, SyncEvent](failure))
            .guarantee(completed.complete(()).void)
        )
        _ <- SessionInput.resource(source).use { input =>
          for
            consumer <- Fault.fiber(input.next).start
            _ <- entered.get
            _ <- consumer.cancel
            _ <- release.complete(())
            _ <- completed.get
            _ <- IO.cede
            result <- Fault.fiber(input.reintersect(restarted.set(true)))
            changed <- restarted.get
          yield
            assertEquals(result, Left(Fault.Failed(failure)))
            assert(!changed)
        }
      yield ()
    }
  }
