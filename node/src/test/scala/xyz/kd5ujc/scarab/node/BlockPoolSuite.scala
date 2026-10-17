// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import scala.concurrent.duration.*

/** A block is claimed by one session, fetched by it, and taken by whichever session applies it. */
class BlockPoolSuite extends CatsEffectSuite:
  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def hash(n: Int): Bytes = Bytes.wrap(Array.fill(32)(n.toByte))
  private def block(n: Int): Bytes = Bytes.wrap(Array.fill(10)((n + 100).toByte))

  test("a second claim on the same hashes owns nothing, and the rest is split") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      other <- pool.session.allocated.map(_._1)
      first <- claims.claim(Vector(hash(1), hash(2)))
      second <- other.claim(Vector(hash(1), hash(2), hash(3)))
    yield
      assertEquals(first, Vector(true, true))
      assertEquals(second, Vector(false, false, true))
  }

  test("a filled block is taken once, a block nobody claimed is not there") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      _ <- claims.claim(Vector(hash(1)))
      _ <- claims.fill(Vector(hash(1) -> block(1)))
      got <- pool.take(hash(1))
      again <- pool.take(hash(1))
      never <- pool.take(hash(9))
      held <- pool.size
    yield
      assertEquals(got, Some(block(1)))
      assertEquals(again, None)
      assertEquals(never, None)
      assertEquals(held, 0)
  }

  test("a session waiting for a block that another one is fetching gets it") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      _ <- claims.claim(Vector(hash(1)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- claims.fill(Vector(hash(1) -> block(1)))
      got <- waiting.joinWithNever
    yield assertEquals(got, Some(block(1)))
  }

  test("a released claim sends the waiter to fetch the block itself, and it can be claimed again") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      _ <- claims.claim(Vector(hash(1)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- claims.release(Vector(hash(1)))
      got <- waiting.joinWithNever
      again <- claims.claim(Vector(hash(1)))
    yield
      assertEquals(got, None)
      assertEquals(again, Vector(true))
  }

  test("releasing a filled block leaves it") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      _ <- claims.claim(Vector(hash(1)))
      _ <- claims.fill(Vector(hash(1) -> block(1)))
      _ <- claims.release(Vector(hash(1)))
      got <- pool.take(hash(1))
    yield assertEquals(got, Some(block(1)))
  }

  test("blocks nobody takes are dropped, oldest first, past the retained count") {
    val n = 2 * BlockPool.Retained + 10
    def key(i: Int): Bytes = Bytes.wrap(java.nio.ByteBuffer.allocate(32).putInt(i).array)
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      hashes = Vector.range(0, n).map(key)
      _ <- hashes.grouped(100).toVector.traverse_ { g =>
        claims.claim(g) *> claims.fill(g.map(h => h -> block(1)))
      }
      held <- pool.size
      oldest <- pool.take(key(0))
      newest <- pool.take(key(n - 1))
    yield
      assert(held <= 2 * BlockPool.Retained + 100, s"held $held")
      assertEquals(oldest, None)
      assertEquals(newest, Some(block(1)))
  }

  test("a session that ends gives up its open claims, and the session waiting for one fetches it") {
    for
      pool <- BlockPool.create[IO]
      ended <- pool.session.allocated
      (claims, end) = ended
      _ <- claims.claim(Vector(hash(1), hash(2)))
      _ <- claims.fill(Vector(hash(2) -> block(2)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- end
      got <- waiting.joinWithNever
      filled <- pool.take(hash(2))
      held <- pool.size
    yield
      assertEquals(got, None)
      // what the session had filled stays for whoever applies it
      assertEquals(filled, Some(block(2)))
      assertEquals(held, 0)
  }

  test("a session that is cancelled while it holds claims gives them up") {
    for
      pool <- BlockPool.create[IO]
      claimed <- cats.effect.Deferred[IO, Unit]
      holder <- pool.session
        .use(claims => claims.claim(Vector(hash(1))) *> claimed.complete(()) *> IO.never)
        .start
      _ <- claimed.get
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- holder.cancel
      got <- waiting.joinWithNever
    yield assertEquals(got, None)
  }

  test("a session cannot fill or release what another session claimed") {
    for
      pool <- BlockPool.create[IO]
      mine <- pool.session.allocated.map(_._1)
      theirs <- pool.session.allocated.map(_._1)
      _ <- mine.claim(Vector(hash(1)))
      _ <- theirs.fill(Vector(hash(1) -> block(1)))
      _ <- theirs.release(Vector(hash(1)))
      stillOurs <- theirs.claim(Vector(hash(1)))
      _ <- mine.fill(Vector(hash(1) -> block(1)))
      got <- pool.take(hash(1))
    yield
      assertEquals(stillOurs, Vector(false))
      assertEquals(got, Some(block(1)))
  }
