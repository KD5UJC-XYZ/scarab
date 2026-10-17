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
      first <- pool.claim(Vector(hash(1), hash(2)))
      second <- pool.claim(Vector(hash(1), hash(2), hash(3)))
    yield
      assertEquals(first, Vector(true, true))
      assertEquals(second, Vector(false, false, true))
  }

  test("a filled block is taken once, a block nobody claimed is not there") {
    for
      pool <- BlockPool.create[IO]
      _ <- pool.claim(Vector(hash(1)))
      _ <- pool.fill(Vector(hash(1) -> block(1)))
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
      _ <- pool.claim(Vector(hash(1)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- pool.fill(Vector(hash(1) -> block(1)))
      got <- waiting.joinWithNever
    yield assertEquals(got, Some(block(1)))
  }

  test("a released claim sends the waiter to fetch the block itself, and it can be claimed again") {
    for
      pool <- BlockPool.create[IO]
      _ <- pool.claim(Vector(hash(1)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- pool.release(Vector(hash(1)))
      got <- waiting.joinWithNever
      again <- pool.claim(Vector(hash(1)))
    yield
      assertEquals(got, None)
      assertEquals(again, Vector(true))
  }

  test("releasing a filled block leaves it") {
    for
      pool <- BlockPool.create[IO]
      _ <- pool.claim(Vector(hash(1)))
      _ <- pool.fill(Vector(hash(1) -> block(1)))
      _ <- pool.release(Vector(hash(1)))
      got <- pool.take(hash(1))
    yield assertEquals(got, Some(block(1)))
  }

  test("blocks nobody takes are dropped, oldest first, past the retained count") {
    val n = 2 * BlockPool.Retained + 10
    def key(i: Int): Bytes = Bytes.wrap(java.nio.ByteBuffer.allocate(32).putInt(i).array)
    for
      pool <- BlockPool.create[IO]
      hashes = Vector.range(0, n).map(key)
      _ <- hashes.grouped(100).toVector.traverse_ { g =>
        pool.claim(g) *> pool.fill(g.map(h => h -> block(1)))
      }
      held <- pool.size
      oldest <- pool.take(key(0))
      newest <- pool.take(key(n - 1))
    yield
      assert(held <= 2 * BlockPool.Retained + 100, s"held $held")
      assertEquals(oldest, None)
      assertEquals(newest, Some(block(1)))
  }
