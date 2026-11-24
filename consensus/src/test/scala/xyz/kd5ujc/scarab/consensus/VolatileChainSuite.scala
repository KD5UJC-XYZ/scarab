// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.{ChainError, Fixtures, Header, HeaderStructureProblem}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.ChainSync
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
    val number = HeaderStructureProblem.BlockNumber(fork.blockNo, headers(2).blockNo + 1)
    assertEquals(
      c.extend(fork, 0).map(_._1),
      Left(ChainError.NotConnected(fork.point, c.tip, number))
    )
  }

  // The envelope's checks in its order (`validateEnvelope`, ouroboros-consensus 82ecba32
  // `Ouroboros/Consensus/HeaderValidation.hs:364-376`), in the words of header-first validation: a
  // header that fails two of them is refused for the first (#1403)
  test("a header that does not extend the tip is refused for the first check it fails") {
    val c = build(Fixtures.chain(3))
    val tip = Fixtures.chain(3).last
    val other = Fixtures.chain(1, 7).head.hash
    val (number, slot) = (tip.blockNo + 1, tip.slot + 1)
    val rows: Vector[(String, Header, HeaderStructureProblem)] = Vector(
      (
        "block number",
        Fixtures.header(number + 1, slot, Some(tip.hash)),
        HeaderStructureProblem.BlockNumber(number + 1, number)
      ),
      (
        "slot",
        Fixtures.header(number, tip.slot, Some(tip.hash)),
        HeaderStructureProblem.SlotNumber(tip.slot, slot)
      ),
      (
        "previous hash",
        Fixtures.header(number, slot, Some(other)),
        HeaderStructureProblem.PreviousHash(Some(other), Some(tip.hash))
      ),
      (
        "block number before the slot and the previous hash",
        Fixtures.header(number - 1, tip.slot - 1, Some(other)),
        HeaderStructureProblem.BlockNumber(number - 1, number)
      ),
      (
        "slot before the previous hash",
        Fixtures.header(number, tip.slot - 1, Some(other)),
        HeaderStructureProblem.SlotNumber(tip.slot - 1, slot)
      )
    )
    assert(c.extend(Fixtures.header(number, slot, Some(tip.hash)), 0).isRight, "the next block")
    rows.foreach { (name, header, cause) =>
      assertEquals(
        c.extend(header, 0).left.toOption,
        Some(ChainError.NotConnected(header.point, c.tip, cause)),
        name
      )
    }
  }

  test("the first block after genesis must have no parent") {
    val orphan = Fixtures.header(0, 1, Some(Fixtures.chain(1).head.hash))
    assert(VolatileChain.genesis[Int](k, 0).extend(orphan, 0) match {
      case Left(
            ChainError.NotConnected(
              _,
              ChainSync.Point.Origin,
              HeaderStructureProblem.PreviousHash(_, _)
            )
          ) =>
        true
      case _ => false
    })
    assertEquals(VolatileChain.genesis[Int](k, 0).tip, ChainSync.Point.Origin)
  }

  /** A Byron header of `epoch` and slot count `count` after `prev`, numbered `blockNo`. */
  private def byron(
      boundary: Boolean,
      epoch: BigInt,
      count: BigInt,
      blockNo: BigInt,
      prev: Option[xyz.kd5ujc.scarab.codec.Bytes],
      tag: Byte
  ): Header.Byron =
    Header.Byron(
      xyz.kd5ujc.scarab.codec.Bytes(Vector(tag)),
      xyz.kd5ujc.scarab.codec.Bytes(Vector.fill(32)(tag)),
      boundary,
      epoch,
      count,
      10,
      blockNo,
      prev,
      xyz.kd5ujc.scarab.ledger.Unsigned
        .from[xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32](1)
        .getOrElse(fail("size hint"))
    )

  test("a Byron boundary block after a boundary block has the next number and slot (#818)") {
    // `expectedNextBlockNo` and `minimumNextSlotNo` (`Byron/Ledger/HeaderValidation.hs:42-52`,
    // ouroboros-consensus 82ecba32): only a boundary block after a main block repeats its number,
    // and only a main block after a boundary block may share its slot
    val first = byron(boundary = true, 4, 0, 7, None, 1)
    val chain =
      VolatileChain.genesis[BigInt](k, -1).extend(first, 0).fold(e => fail(e.message), _._1)
    val prev = Some(first.hash)
    assert(chain.extend(byron(boundary = true, 6, 0, 8, prev, 2), 1).isRight, "the next number")
    assert(
      chain.extend(byron(boundary = true, 6, 0, 7, prev, 3), 1) match {
        case Left(ChainError.NotConnected(_, _, HeaderStructureProblem.BlockNumber(_, _))) => true
        case _                                                                             => false
      },
      "its own number"
    )
    assert(
      chain.extend(byron(boundary = true, 4, 0, 8, prev, 4), 1) match {
        case Left(ChainError.NotConnected(_, _, HeaderStructureProblem.SlotNumber(_, _))) => true
        case _                                                                            => false
      },
      "its own slot"
    )
    assert(chain.extend(byron(boundary = false, 4, 0, 8, prev, 5), 1).isRight, "a main block")
  }
