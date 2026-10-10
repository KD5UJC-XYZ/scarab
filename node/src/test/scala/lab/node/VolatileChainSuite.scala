// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import lab.network.ChainSync
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class VolatileChainSuite extends ScalaCheckSuite:
  private val k = 5
  private def sized(min: Int, max: Int) =
    Gen.choose(min, max).flatMap(n => Gen.choose(0, 5).map(Fixtures.chain(n, _)))

  /** Apply headers, using the block number as the per-block state. */
  private def build(headers: Vector[Header]): VolatileChain[BigInt] =
    headers.foldLeft(VolatileChain.genesis[BigInt](k, -1)) { (c, h) =>
      c.extend(h, h.blockNo).fold(e => fail(e.message), _._1)
    }

  property("never retains more than k blocks; anchor holds the newest immutable block") {
    forAll(Fixtures.chainGen) { headers =>
      val c = build(headers)
      assert(c.length <= k)
      assertEquals(c.length, headers.size.min(k))
      val expectedAnchor =
        headers.dropRight(k).lastOption.map(h => BlockRef(h.slot, h.hash, h.blockNo))
      assertEquals(c.anchor.block, expectedAnchor)
      assertEquals(c.anchor.state, expectedAnchor.fold(BigInt(-1))(_.blockNo))
    }
  }

  property("state is the state of the tip block") {
    forAll(Fixtures.chainGen) { headers =>
      assertEquals(build(headers).state, headers.lastOption.fold(BigInt(-1))(_.blockNo))
    }
  }

  property("rollback to the tip is the identity") {
    forAll(Fixtures.chainGen) { headers =>
      val c = build(headers)
      assert(c.rollback(c.tip).exists(_ === c))
    }
  }

  property("extend then rollback to the previous tip restores the chain while below k") {
    forAll(sized(0, k - 1)) { headers =>
      val c = build(headers)
      val next = Fixtures.chain(1, 0, headers.lastOption).head
      val restored = c.extend(next, next.blockNo).flatMap(_._1.rollback(c.tip))
      assert(restored.exists(_ === c))
    }
  }

  property("rollback restores the exact state retained at the target") {
    forAll(sized(1, 30), Gen.choose(0, k - 1)) { (headers, back) =>
      val c = build(headers)
      val target = c.candidates(back.min(c.candidates.size - 1))
      val rolled = c.rollback(target).fold(e => fail(e.message), identity)
      assertEquals(rolled.tip, target)
      val expected = headers.find(_.point == target).fold(BigInt(-1))(_.blockNo)
      assertEquals(rolled.state, expected)
    }
  }

  property("rollback beyond the anchor is rejected") {
    // Strictly older than the anchor: with k + 1 blocks the first block *is* the anchor.
    forAll(sized(k + 2, 30)) { headers =>
      val c = build(headers)
      val old = headers.head.point
      assertEquals(c.rollback(old), Left(ChainError.RollbackBeyondAnchor(old, c.anchor.point)))
    }
  }

  property("candidates run newest first from the tip to the anchor") {
    forAll(Fixtures.chainGen) { headers =>
      val c = build(headers)
      assertEquals(c.candidates.head, c.tip)
      assertEquals(c.candidates.last, c.anchor.point)
      assertEquals(c.candidates.size, c.length + 1)
    }
  }

  test("a header that does not extend the tip is rejected") {
    val headers = Fixtures.chain(3)
    val c = build(headers)
    val fork = Fixtures.chain(1, 7, Some(headers(1))).head
    assertEquals(c.extend(fork, 0).map(_._1), Left(ChainError.NotConnected(fork.point, c.tip)))
  }

  test("the first block after genesis must have no parent") {
    val orphan = Fixtures.header(0, 1, Some(Fixtures.chain(1).head.hash))
    assert(VolatileChain.genesis[Int](k, 0).extend(orphan, 0).isLeft)
    assertEquals(VolatileChain.genesis[Int](k, 0).tip, ChainSync.Point.Origin)
  }
