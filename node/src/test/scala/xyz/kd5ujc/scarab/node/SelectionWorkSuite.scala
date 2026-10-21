// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.GenesisBlockFetch.Starvation

class SelectionWorkSuite extends CatsEffectSuite:
  private val hash = Bytes(Vector(1.toByte))

  test("only consuming ready block work ends starvation; cached reconsideration does not") {
    TestControl.executeEmbed {
      for
        work <- SelectionWork.create[IO](IO.unit)
        _ <- work.session.use { session =>
          for
            _ <- session.processing(hash).use(_ => work.snapshot.assertEquals(Starvation.Ongoing))
            _ <- IO.sleep(2.seconds)
            _ <- session.ready(Vector(hash))
            _ <- work.snapshot.assertEquals(Starvation.Ongoing)
            _ <- session.processing(hash).use { _ =>
              work.snapshot.assertEquals(Starvation.EndedAt(BigInt(2) * 1000000000000L)) *>
                IO.sleep(3.seconds) *>
                work.snapshot.assertEquals(Starvation.EndedAt(BigInt(2) * 1000000000000L))
            }
            _ <- work.snapshot.assertEquals(Starvation.Ongoing)
            _ <- session.processing(hash).use(_ => work.snapshot.assertEquals(Starvation.Ongoing))
          yield ()
        }
      yield ()
    }
  }

  test("queued work from another session keeps selection busy until considered or canceled") {
    TestControl.executeEmbed {
      for
        work <- SelectionWork.create[IO](IO.unit)
        _ <- work.session.use { first =>
          for
            second <- work.session.allocated
            (other, release) = second
            _ <- first.ready(Vector(hash)) *> other.ready(Vector(hash))
            _ <- first.processing(hash).use(_ => IO.unit)
            _ <- work.snapshot.assertEquals(Starvation.EndedAt(0))
            _ <- release
            _ <- work.snapshot.assertEquals(Starvation.Ongoing)
          yield ()
        }
      yield ()
    }
  }

  test("discarding a drained queue preserves a running consideration until its owner finishes") {
    for
      work <- SelectionWork.create[IO](IO.unit)
      _ <- work.session.use { session =>
        session.ready(Vector(hash, hash)) *> session.processing(hash).use { _ =>
          session.discardPending *> work.snapshot.map(s => assert(s != Starvation.Ongoing))
        } *> work.snapshot.assertEquals(Starvation.Ongoing)
      }
    yield ()
  }

  test("a different following session consumes the source peer's ready block exactly once") {
    TestControl.executeEmbed {
      for
        work <- SelectionWork.create[IO](IO.unit)
        _ <- (work.session, work.session).tupled.use { (source, applying) =>
          source.ready(Vector(hash)) *> IO.sleep(1.second) *>
            applying
              .processing(hash)
              .use(_ => work.snapshot.assertEquals(Starvation.EndedAt(BigInt(1000000000000L)))) *>
            work.snapshot.assertEquals(Starvation.Ongoing) *>
            source.processing(hash).use(_ => work.snapshot.assertEquals(Starvation.Ongoing))
        }
      yield ()
    }
  }
