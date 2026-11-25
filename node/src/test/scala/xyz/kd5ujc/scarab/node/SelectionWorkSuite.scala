// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.GenesisBlockFetch.Starvation
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

class SelectionWorkSuite extends CatsEffectSuite:
  private val hash = Bytes(Vector(1.toByte))
  private val block: Point.Block = Point.Block(UInt64.saturating(7), hash)

  test("only consuming ready block work ends starvation; cached reconsideration does not") {
    TestControl.executeEmbed {
      for
        work <- SelectionWork.create[IO](IO.unit)
        _ <- work.session.use { session =>
          for
            _ <- session.processing(block).use(_ => work.snapshot.assertEquals(Starvation.Ongoing))
            _ <- IO.sleep(2.seconds)
            _ <- session.ready(Vector(hash))
            _ <- work.snapshot.assertEquals(Starvation.Ongoing)
            _ <- session.processing(block).use { _ =>
              work.snapshot.assertEquals(Starvation.EndedAt(BigInt(2) * 1000000000000L)) *>
                IO.sleep(3.seconds) *>
                work.snapshot.assertEquals(Starvation.EndedAt(BigInt(2) * 1000000000000L))
            }
            _ <- work.snapshot.assertEquals(Starvation.Ongoing)
            _ <- session.processing(block).use(_ => work.snapshot.assertEquals(Starvation.Ongoing))
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
            _ <- first.processing(block).use(_ => IO.unit)
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
        session.ready(Vector(hash, hash)) *> session.processing(block).use { _ =>
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
              .processing(block)
              .use(_ => work.snapshot.assertEquals(Starvation.EndedAt(BigInt(1000000000000L)))) *>
            work.snapshot.assertEquals(Starvation.Ongoing) *>
            source.processing(block).use(_ => work.snapshot.assertEquals(Starvation.Ongoing))
        }
      yield ()
    }
  }

  test("each edge of the starvation is traced as ChainSelStarvation, the end with its block") {
    for
      traced <- Ref.of[IO, Vector[GenesisTrace]](Vector.empty)
      events = new GenesisEvents[IO]:
        def emit(event: GenesisTrace) = traced.update(_ :+ event)
      work <- SelectionWork.create[IO](IO.unit, events = Some(events))
      _ <- work.session.use { session =>
        for
          // starved from the start, as the reference's `ChainSelStarvationOngoing`: no edge yet,
          // and a consideration with no queued block does not end it
          _ <- session.processing(block).use_
          _ <- traced.get.assertEquals(Vector.empty)
          _ <- session.ready(Vector(hash))
          _ <- session
            .processing(block)
            .use(_ =>
              traced.get.assertEquals(
                Vector(GenesisTrace.ChainSelStarvation(GenesisTrace.Edge.FallingWith(block)))
              )
            )
          _ <- session.ready(Vector(hash))
          _ <- session.processing(block).use_
        yield ()
      }
      all <- traced.get
    yield assertEquals(
      all,
      Vector(
        GenesisTrace.Edge.FallingWith(block),
        GenesisTrace.Edge.Rising,
        GenesisTrace.Edge.FallingWith(block),
        GenesisTrace.Edge.Rising
      ).map(GenesisTrace.ChainSelStarvation(_))
    )
  }
