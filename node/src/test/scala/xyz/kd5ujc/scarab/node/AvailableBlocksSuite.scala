// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.{Header, PreprodDeepTx}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Header-field graph tests: signature/admission checking happens before this pure helper. */
class AvailableBlocksSuite extends FunSuite:
  private def hash(id: Int): Bytes =
    Bytes.fromArray(
      Array.tabulate[Byte](32)(i => if i < 28 then 0 else (id >>> (8 * (31 - i))).toByte)
    )
  private def header(
      id: Int,
      parent: Option[Bytes],
      number: Int,
      slot: Int,
      boundary: Boolean = false
  ): Header.Byron =
    Header
      .UnsizedByron(hash(id), hash(id), boundary, 0, slot, 100000, number, parent)
      .withSizeHint(1)
  private def chain(headers: Header*): Map[Bytes, Header] = headers.map(h => h.hash -> h).toMap
  private val origin =
    ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
  private def local(anchor: Header, headers: Header*): ChainSelection.Local =
    ChainSelection.Local(
      anchor.point,
      SelectView.of(anchor),
      headers.toVector.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )
  private def candidates(
      selected: ChainSelection.Local,
      bodies: Map[Bytes, Header],
      invalid: Set[Bytes] = Set.empty,
      containing: Option[Bytes] = None,
      genesis: Option[Bytes] = None
  ): Vector[ChainSelection.Candidate] =
    AvailableBlocks
      .candidates(selected, bodies, invalid, containing, genesis)
      .fold(e => fail(e.toString), identity)

  test("maximal paths preserve every original header and omit intermediary prefixes") {
    val a = header(1, None, 0, 1)
    val b = header(2, Some(a.hash), 1, 2)
    val c = header(3, Some(b.hash), 2, 3)
    val found = candidates(origin, chain(a, b, c))
    assertEquals(found, Vector(ChainSelection.Candidate(Point.Origin, Vector(a, b, c))))
    assertEquals(found.flatMap(_.headers.map(_.raw)), Vector(a.raw, b.raw, c.raw))
  }

  test("each fork is anchored at its most recent retained selected point") {
    val a = header(1, None, 0, 1)
    val b = header(2, Some(a.hash), 1, 2)
    val x = header(3, Some(a.hash), 1, 3)
    val y = header(4, Some(x.hash), 2, 4)
    val z = header(5, Some(a.hash), 1, 5)
    val end = header(6, Some(b.hash), 2, 6)
    val selected = origin.copy(blocks =
      Vector(a, b).map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )
    assertEquals(
      candidates(selected, chain(a, b, x, y, z, end)),
      Vector(
        ChainSelection.Candidate(a.point, Vector(x, y)),
        ChainSelection.Candidate(a.point, Vector(z)),
        ChainSelection.Candidate(b.point, Vector(end))
      )
    )
  }

  test("invalid ancestors cut off their complete suffix while the valid prefix remains maximal") {
    val a = header(1, None, 0, 1)
    val b = header(2, Some(a.hash), 1, 2)
    val c = header(3, Some(b.hash), 2, 3)
    assertEquals(
      candidates(origin, chain(a, b, c), Set(b.hash)),
      Vector(ChainSelection.Candidate(Point.Origin, Vector(a)))
    )
    assertEquals(candidates(origin, chain(a, b, c), Set(a.hash)), Vector.empty)
  }

  test("a later processed parent unlocks stored successors, never later queued bodies") {
    val a = header(1, None, 0, 1)
    val b = header(2, Some(a.hash), 1, 2)
    val c = header(3, Some(b.hash), 2, 3)
    assertEquals(candidates(origin, chain(c)), Vector.empty)
    assertEquals(
      candidates(origin, chain(a, c)),
      Vector(ChainSelection.Candidate(Point.Origin, Vector(a)))
    )
    assertEquals(
      candidates(origin, chain(a, b, c), containing = Some(b.hash)),
      Vector(ChainSelection.Candidate(Point.Origin, Vector(a, b, c)))
    )
    assertEquals(candidates(origin, chain(a, b), containing = Some(c.hash)), Vector.empty)
  }

  test("first-admission candidates contain their cause; reprocessing considers all branches") {
    val a = header(1, None, 0, 1)
    val b = header(2, Some(a.hash), 1, 2)
    val c = header(3, Some(a.hash), 1, 3)
    assertEquals(
      candidates(origin, chain(a, b, c), containing = Some(b.hash)),
      Vector(ChainSelection.Candidate(Point.Origin, Vector(a, b)))
    )
    assertEquals(candidates(origin, chain(a, b, c)).size, 2)
  }

  test("immutable boundaries prevent deep forks and preserve the same-number EBB exception") {
    val anchor = header(10, Some(hash(9)), 5, 10)
    val selected = header(11, Some(anchor.hash), 6, 21)
    val ebb = header(12, Some(anchor.hash), 5, 20, boundary = true)
    val main = header(13, Some(ebb.hash), 6, 20)
    val oldMain = header(14, Some(anchor.hash), 5, 11)
    val beforeAnchor = header(15, Some(hash(9)), 6, 22)
    assertEquals(
      candidates(local(anchor, selected), chain(ebb, main, oldMain, beforeAnchor)),
      Vector(ChainSelection.Candidate(anchor.point, Vector(ebb, main)))
    )
  }

  test(
    "root and child enumeration follows native Set hash order, independently of VRF preference"
  ) {
    val a = header(1, None, 0, 1)
    val low = header(127, Some(a.hash), 1, 2)
    val high = header(128, Some(a.hash), 1, 2)
    val expected = Vector(
      ChainSelection.Candidate(Point.Origin, Vector(a, low)),
      ChainSelection.Candidate(Point.Origin, Vector(a, high))
    )
    assertEquals(candidates(origin, chain(high, a, low)), expected)
    assertEquals(candidates(origin, chain(low, high, a)), expected)
  }

  test("Origin requires the exact configured Byron predecessor alias") {
    val genesis = hash(100)
    val ebb = header(1, Some(genesis), 0, 0, boundary = true)
    assertEquals(candidates(origin, chain(ebb)), Vector.empty)
    assertEquals(
      candidates(origin, chain(ebb), genesis = Some(genesis)),
      Vector(ChainSelection.Candidate(Point.Origin, Vector(ebb)))
    )
    assertEquals(candidates(origin, chain(ebb), genesis = Some(hash(101))), Vector.empty)
  }

  test("inconsistent hash keys and cycles are closed invariant errors") {
    val a = header(1, None, 0, 1)
    assertEquals(
      AvailableBlocks.candidates(origin, Map(hash(9) -> a), Set.empty),
      Left(AvailableBlocks.Error.HashMismatch(hash(9), a.hash))
    )
    val x = header(2, Some(hash(3)), 1, 2)
    val y = header(3, Some(x.hash), 2, 3)
    assertEquals(
      AvailableBlocks.candidates(origin, chain(x, y), Set.empty),
      Left(AvailableBlocks.Error.Cycle(x.hash))
    )
  }

  test("deep forests remain stack safe on a 256 KiB thread") {
    val headers = Vector.tabulate(30000)(i => header(i + 1, Option.when(i > 0)(hash(i)), i, i))
    PreprodDeepTx.onSmallStack {
      val found = candidates(origin, headers.map(h => h.hash -> h).toMap)
      assertEquals(found.size, 1)
      assertEquals(found.headOption.map(_.headers), Some(headers))
    }
  }

  test("wide forests remain stack safe and preserve every maximal branch") {
    val headers = Vector.tabulate(10000)(i => header(i + 1, None, 0, i))
    PreprodDeepTx.onSmallStack {
      val found = candidates(origin, headers.map(h => h.hash -> h).toMap)
      assertEquals(found.map(_.headers), headers.map(Vector(_)))
    }
  }
