// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{BlockFailure, ValidationError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.ChainSync

class CachedBlockAttributionSuite extends FailsOnUncaught:
  private def hash(n: Int): Bytes = Bytes.wrap(Array(n.toByte))
  private val block = Bytes.wrap(Array[Byte](1, 2, 3))
  private val refusal = ValidationError(ChainSync.Point.Origin, BlockFailure.HeaderDiffers)

  private def fill(claims: BlockPool.Claims[IO], n: Int): IO[Unit] =
    claims.claim(Vector(hash(n))) *> claims.fill(Vector(hash(n) -> block))

  test("a released source is blamed once; the innocent consumer is never blamed") {
    for
      pool <- BlockPool.create[IO]
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      _ <- pool.session(Some(e => reports.update(_ :+ e))).use(fill(_, 1))
      got <- pool.session.use { consumer =>
        for
          taken <- consumer.take(hash(1))
          origin <- IO.fromOption(taken)(new AssertionError("missing retained block"))
          _ <- consumer.distrust(origin.origin, refusal)
          _ <- consumer.distrust(origin.origin, refusal)
          innocent <- consumer.blamed.as(true).timeoutTo(20.millis, IO.pure(false))
        yield (origin.own, innocent)
      }
      seen <- reports.get
      retained <- pool.retainedOrigins
    yield
      assertEquals(got, (false, false))
      assertEquals(seen, Vector(refusal))
      assertEquals(retained, 0)
  }

  test("a block taken before source release retains attribution until validation") {
    for
      pool <- BlockPool.create[IO]
      reports <- Ref.of[IO, Int](0)
      source <- pool.session(Some(_ => reports.update(_ + 1))).allocated
      _ <- fill(source._1, 1)
      taken <- pool.takeFilled(hash(1))
      _ <- source._2
      retained <- pool.retainedOrigins
      _ <- taken.traverse_(b => pool.session.use(_.distrust(b.origin, refusal)))
      seen <- reports.get
    yield
      assertEquals(retained, 1)
      assertEquals(seen, 1)
  }

  test("distrust releases pending claims and ignores the distrusted source's later deliveries") {
    for
      pool <- BlockPool.create[IO]
      _ <- pool.session.use { source =>
        pool.session.use { consumer =>
          for
            _ <- source.claim(Vector(hash(1), hash(2)))
            _ <- source.fill(Vector(hash(1) -> block))
            taken <- consumer.take(hash(1))
            origin <- IO.fromOption(taken)(new AssertionError("missing block"))
            _ <- consumer.distrust(origin.origin, refusal)
            _ <- source.fill(Vector(hash(2) -> block))
            noMoreClaims <- source.claim(Vector(hash(3)))
            untrusted <- consumer.take(hash(2))
            reclaimed <- consumer.claim(Vector(hash(2)))
          yield
            assertEquals(untrusted, None)
            assertEquals(reclaimed, Vector(true))
            assertEquals(noMoreClaims, Vector(false))
        }
      }
    yield ()
  }

  test("release racing repeated distrust never loses or duplicates source blame") {
    (1 to 50).toList.traverse_ { _ =>
      for
        pool <- BlockPool.create[IO]
        reports <- Ref.of[IO, Int](0)
        source <- pool.session(Some(_ => reports.update(_ + 1))).allocated
        _ <- fill(source._1, 1)
        taken <- pool.takeFilled(hash(1))
        origin <- IO.fromOption(taken)(new AssertionError("missing block"))
        _ <- (
          source._2,
          pool.session.use { consumer =>
            List.fill(10)(consumer.distrust(origin.origin, refusal)).parSequence_
          }
        ).parTupled
        seen <- reports.get
        retained <- pool.retainedOrigins
      yield
        assertEquals(seen, 1)
        assertEquals(retained, 0)
    }
  }

  test("cancelling the consumer during notification cannot lose the source's one refusal") {
    for
      pool <- BlockPool.create[IO]
      started <- Deferred[IO, Unit]
      continue <- Deferred[IO, Unit]
      reports <- Ref.of[IO, Int](0)
      _ <- pool
        .session(Some(_ => started.complete(()) *> continue.get *> reports.update(_ + 1)))
        .use(fill(_, 1))
      _ <- pool.session.use { consumer =>
        for
          taken <- consumer.take(hash(1))
          origin <- IO.fromOption(taken)(new AssertionError("missing block"))
          notify <- consumer.distrust(origin.origin, refusal).start
          _ <- started.get
          cancel <- notify.cancel.start
          _ <- IO.cede
          _ <- continue.complete(())
          _ <- cancel.joinWithNever
          _ <- consumer.distrust(origin.origin, refusal)
        yield ()
      }
      seen <- reports.get
    yield assertEquals(seen, 1)
  }

  test("released attribution is capped independently of already consumed blocks") {
    for
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), BlockPool.AttributionPolicy(retained = 2))
      reports <- Ref.of[IO, Vector[Int]](Vector.empty)
      oldest <- (1 to 3).toVector.traverse { n =>
        pool.session(Some(_ => reports.update(_ :+ n))).use { source =>
          fill(source, n) *> (if n == 1 then pool.takeFilled(hash(n)) else IO.pure(None))
        }
      }
      retained <- pool.retainedOrigins
      _ <- oldest.flatten.traverse_(b => pool.session.use(_.distrust(b.origin, refusal)))
      second <- pool.takeFilled(hash(2))
      _ <- second.traverse_(b => pool.session.use(_.distrust(b.origin, refusal)))
      third <- pool.take(hash(3))
      seen <- reports.get
    yield
      assertEquals(retained, 2)
      assertEquals(seen, Vector(2))
      assertEquals(third, Some(block))
  }

  test("capacity eviction removes the evicted source's untaken blocks") {
    for
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), BlockPool.AttributionPolicy(retained = 1))
      _ <- pool.session.use(fill(_, 1))
      _ <- pool.session.use(fill(_, 2))
      first <- pool.take(hash(1))
      second <- pool.take(hash(2))
      retained <- pool.retainedOrigins
    yield
      assertEquals(first, None)
      assertEquals(second, Some(block))
      assertEquals(retained, 1)
  }

  test("idle resource expiry removes callbacks and untaken blocks without a pool access") {
    TestControl.executeEmbed {
      BlockPool
        .resource[IO](BlockPool.FetchPolicy(), BlockPool.AttributionPolicy(lifetime = 1.second))
        .use { pool =>
          for
            _ <- pool.session.use(fill(_, 1))
            before <- pool.size
            _ <- IO.sleep(2.seconds)
            after <- pool.size
            retained <- pool.retainedOrigins
          yield
            assertEquals(before, 1)
            assertEquals(after, 0)
            assertEquals(retained, 0)
        }
    }
  }

  test("an already taken block's attribution expires and does not retain its callback forever") {
    TestControl.executeEmbed {
      for
        reports <- Ref.of[IO, Int](0)
        _ <- BlockPool
          .resource[IO](BlockPool.FetchPolicy(), BlockPool.AttributionPolicy(lifetime = 1.second))
          .use { pool =>
            for
              _ <- pool.session(Some(_ => reports.update(_ + 1))).use(fill(_, 1))
              taken <- pool.takeFilled(hash(1))
              _ <- IO.sleep(2.seconds)
              _ <- taken.traverse_(b => pool.session.use(_.distrust(b.origin, refusal)))
              seen <- reports.get
              retained <- pool.retainedOrigins
            yield
              assertEquals(retained, 0)
              assertEquals(seen, 0)
          }
      yield ()
    }
  }

  test("active source attribution survives the released-source expiry window") {
    TestControl.executeEmbed {
      for
        pool <- BlockPool
          .create[IO](BlockPool.FetchPolicy(), BlockPool.AttributionPolicy(lifetime = 1.second))
        reports <- Ref.of[IO, Int](0)
        _ <- pool.session(Some(_ => reports.update(_ + 1))).use { source =>
          for
            _ <- fill(source, 1)
            _ <- IO.sleep(2.seconds)
            taken <- pool.takeFilled(hash(1))
            _ <- taken.traverse_(b => pool.session.use(_.distrust(b.origin, refusal)))
            seen <- reports.get
          yield assertEquals(seen, 1)
        }
      yield ()
    }
  }

  test("unfilled released sessions retain no callback or claim") {
    for
      pool <- BlockPool.create[IO]
      _ <- pool.session.use(_.claim(Vector(hash(1))).void)
      retained <- pool.retainedOrigins
      size <- pool.size
    yield
      assertEquals(retained, 0)
      assertEquals(size, 0)
  }

  test("pool resource release clears sources and releases any remaining pending waiter") {
    for
      resource <- BlockPool.resource[IO](BlockPool.FetchPolicy()).allocated
      (pool, close) = resource
      source <- pool.session.allocated
      _ <- source._1.claim(Vector(hash(1)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.cede
      _ <- close
      result <- waiting.joinWithNever
      _ <- source._2
      retained <- pool.retainedOrigins
      size <- pool.size
    yield
      assertEquals(result, None)
      assertEquals(retained, 0)
      assertEquals(size, 0)
  }
