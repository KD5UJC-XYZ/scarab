// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.{Fixtures, Header, HeaderVrf, VrfCert}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.consensus.ChainSelection.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class ChainSelectionSuite extends ScalaCheckSuite:
  private def bytes(n: Int, size: Int = 64) = Bytes(Vector.fill(size)(n.toByte))

  private def view(
      blockNo: Int,
      slot: Int = 100,
      issuer: Int = 1,
      issueNo: Int = 0,
      vrf: Int = 5
  ) = SelectView(blockNo, slot, Some(bytes(issuer, 32)), issueNo, Some(bytes(vrf)))

  private val views: Gen[SelectView] = for
    blockNo <- Gen.choose(0, 4)
    slot <- Gen.choose(0, 40)
    issuer <- Gen.choose(0, 2)
    issueNo <- Gen.choose(0, 2)
    vrf <- Gen.choose(0, 255)
  yield view(blockNo, slot, issuer, issueNo, vrf)

  private val flavors = Gen.oneOf(Tiebreaker.Unrestricted, Tiebreaker.Restricted(5))

  property("irreflexive and antisymmetric: no chain is preferred to itself or both ways") {
    forAll(flavors, views, views) { (f, a, b) =>
      assert(!prefers(f, a, a))
      assert(!(prefers(f, a, b) && prefers(f, b, a)))
    }
  }

  property("the longer chain always wins") {
    forAll(flavors, views, views) { (f, a, b) =>
      if b.blockNo > a.blockNo then assert(prefers(f, a, b) && !prefers(f, b, a))
    }
  }

  property("equal length, same issuer: the higher issue number wins, whatever the VRF") {
    forAll(flavors, Gen.choose(0, 255), Gen.choose(0, 255), Gen.choose(0, 5), Gen.choose(0, 5)) {
      (f, v1, v2, n1, n2) =>
        if n1 != n2 then
          assertEquals(
            prefers(f, view(3, issueNo = n1, vrf = v1), view(3, issueNo = n2, vrf = v2)),
            n2 > n1
          )
    }
  }

  property("equal length, other issuers: the lower VRF output wins (unrestricted)") {
    forAll(Gen.choose(0, 255), Gen.choose(0, 255)) { (v1, v2) =>
      val ours = view(3, issuer = 1, vrf = v1)
      val cand = view(3, issuer = 2, vrf = v2)
      assertEquals(prefers(Tiebreaker.Unrestricted, ours, cand), v2 < v1)
    }
  }

  test("the VRF comparison is on unsigned bytes") {
    val low = view(3, issuer = 1, vrf = 0x10)
    val high = view(3, issuer = 2, vrf = 0xf0) // negative as a signed byte
    assert(prefers(Tiebreaker.Unrestricted, high, low))
    assert(!prefers(Tiebreaker.Unrestricted, low, high))
  }

  test("the restricted flavor only compares VRF outputs of tips within the slot distance") {
    val ours = view(3, slot = 100, issuer = 1, vrf = 200)
    val near = view(3, slot = 103, issuer = 2, vrf = 10)
    val far = view(3, slot = 120, issuer = 2, vrf = 10)
    assert(prefers(Tiebreaker.Restricted(5), ours, near))
    assert(!prefers(Tiebreaker.Restricted(5), ours, far))
    assert(prefers(Tiebreaker.Unrestricted, ours, far))
    assertEquals(
      ChainSelection.tiebreakerFor(xyz.kd5ujc.scarab.protocol.Era.Babbage),
      Tiebreaker.Unrestricted
    )
    assertEquals(
      ChainSelection.tiebreakerFor(xyz.kd5ujc.scarab.protocol.Era.Conway),
      Tiebreaker.Restricted(5)
    )
  }

  // Exact cases from upstream `comparePraos` (ouroboros-consensus 4.2.0.x, Praos/Common.hs:125-168).

  test("restricted: the slot distance bound is inclusive (5 apart compares, 6 apart does not)") {
    val ours = view(3, slot = 100, issuer = 1, vrf = 200)
    val f = Tiebreaker.Restricted(5)
    assert(prefers(f, ours, view(3, slot = 105, issuer = 2, vrf = 10)))
    assert(prefers(f, ours, view(3, slot = 95, issuer = 2, vrf = 10)))
    assert(!prefers(f, ours, view(3, slot = 106, issuer = 2, vrf = 10)))
    assert(!prefers(f, ours, view(3, slot = 94, issuer = 2, vrf = 10)))
  }

  test("equal VRF outputs keep the current chain") {
    for f <- Seq(Tiebreaker.Unrestricted, Tiebreaker.Restricted(5)) do
      assert(!prefers(f, view(3, issuer = 1, vrf = 7), view(3, issuer = 2, vrf = 7)))
  }

  test("the issue number is compared only for the same issuer and the same slot") {
    for f <- Seq(Tiebreaker.Unrestricted, Tiebreaker.Restricted(5)) do
      // same issuer, same slot: the higher issue number wins even with a worse VRF
      assert(prefers(f, view(3, issueNo = 1, vrf = 1), view(3, issueNo = 2, vrf = 200)))
      assert(!prefers(f, view(3, issueNo = 2, vrf = 200), view(3, issueNo = 1, vrf = 1)))
      // same issuer, different slots: the issue number is ignored, the VRF decides
      assert(
        !prefers(
          f,
          view(3, slot = 100, issueNo = 1, vrf = 1),
          view(3, slot = 101, issueNo = 2, vrf = 200)
        )
      )
      assert(
        prefers(
          f,
          view(3, slot = 100, issueNo = 2, vrf = 200),
          view(3, slot = 101, issueNo = 1, vrf = 1)
        )
      )
      // same slot, other issuers: the issue number is ignored
      assert(
        !prefers(
          f,
          view(3, issuer = 1, issueNo = 1, vrf = 1),
          view(3, issuer = 2, issueNo = 9, vrf = 200)
        )
      )
  }

  test("same issuer and slot, equal issue numbers: nothing to prefer") {
    assert(!prefers(Tiebreaker.Unrestricted, view(3, issueNo = 1), view(3, issueNo = 1)))
  }

  test("Byron tips (no VRF) never tie-break") {
    val byron = SelectView(3, 100, None, 0, None)
    assert(!prefers(Tiebreaker.Unrestricted, byron, view(3, issuer = 2, vrf = 0)))
    assert(!prefers(Tiebreaker.Unrestricted, view(3, vrf = 9), byron))
  }

  // ---- decisions over chains

  /** Headers with the given block numbers on top of `from`, issuer/vrf as chosen. */
  private def branch(
      n: Int,
      from: Option[Header],
      salt: Int,
      issuer: Int = 1,
      issueNo: Int = 0,
      vrf: Int = 5
  ): Vector[Header] =
    Fixtures.chain(n, salt, from).map {
      case h: Header.Shelley =>
        h.copy(
          issuerVkey = bytes(issuer, 32),
          opCert = h.opCert.copy(sequence = issueNo),
          vrf = HeaderVrf.Praos(VrfCert(bytes(vrf), bytes(4, 80)))
        )
      case other => other
    }

  /** Our chain after the anchor: `blocks` (oldest first). */
  private def local(anchor: Option[Header], blocks: Vector[Header]): Local =
    Local(
      anchor.fold[Point](Point.Origin)(_.point),
      anchor.fold(SelectView(-1, 0, None, 0, None))(SelectView.of),
      blocks.map(h => LocalBlock(h.point, SelectView.of(h)))
    )

  property("a switch rolls back at most the volatile chain, and the result is preferred") {
    forAll(
      Gen.choose(1, 8),
      Gen.choose(0, 8),
      Gen.choose(0, 10),
      Gen.choose(0, 255),
      Gen.choose(0, 255)
    ) { (volatile, depth, extra, ourVrf, theirVrf) =>
      val anchor = branch(1, None, 0).head
      val ours = branch(volatile, Some(anchor), 1, issuer = 1, vrf = ourVrf)
      val forkAt = (volatile - depth).max(0)
      val base = if forkAt == 0 then anchor else ours(forkAt - 1)
      val theirs = branch(extra, Some(base), 2, issuer = 2, vrf = theirVrf)
      val l = local(Some(anchor), ours)
      val candidate = Candidate(base.point, theirs)
      decide(Tiebreaker.Unrestricted, l, candidate) match
        case Decision.Switch(to, d, take) =>
          assertEquals(to, base.point)
          assert(d <= l.blocks.size)
          assertEquals(d, volatile - forkAt)
          assertEquals(take, theirs)
          assert(prefers(Tiebreaker.Unrestricted, l.tipView, SelectView.of(take.last)))
        case Decision.Keep =>
          if theirs.nonEmpty then
            assert(!prefers(Tiebreaker.Unrestricted, l.tipView, SelectView.of(theirs.last)))
        case Decision.Detached => fail("the base is on our chain")
    }
  }

  test("a candidate that is a prefix of our chain, or our chain itself, is kept") {
    val anchor = branch(1, None, 0).head
    val ours = branch(5, Some(anchor), 1)
    val l = local(Some(anchor), ours)
    assertEquals(
      decide(Tiebreaker.Unrestricted, l, Candidate(ours(1).point, ours.slice(2, 4))),
      Decision.Keep
    )
    assertEquals(
      decide(Tiebreaker.Unrestricted, l, Candidate(ours.last.point, Vector.empty)),
      Decision.Keep
    )
  }

  test("a candidate that joins before the anchor, or anywhere unknown, is detached") {
    val old = branch(3, None, 0)
    val anchor = old.last
    val ours = branch(3, Some(anchor), 1)
    val l = local(Some(anchor), ours)
    val longer = branch(10, Some(old(0)), 2)
    assertEquals(
      decide(Tiebreaker.Unrestricted, l, Candidate(old(0).point, longer)),
      Decision.Detached
    )
  }

  property("which of several candidates wins does not depend on the order they are presented in") {
    forAll(Gen.choose(0, 3), Gen.choose(0, 3), Gen.choose(0, 3), Gen.choose(0, 1000)) {
      (a, b, c, seed) =>
        val anchor = branch(1, None, 0).head
        val base = branch(2, Some(anchor), 1)
        val shared = Vector(base.head)
        val lens = List(a, b, c)
        // distinct issuers and distinct VRF outputs: a strict total order on tips
        val candidates = lens.zipWithIndex.map { case (n, i) =>
          Candidate(
            shared.last.point,
            branch(n, Some(shared.last), 10 + i, issuer = 10 + i, vrf = 20 + i * 7)
          )
        }
        def run(order: List[Candidate]): Point =
          order
            .foldLeft(local(Some(anchor), shared)) { (l, cand) =>
              decide(Tiebreaker.Unrestricted, l, cand) match
                case Decision.Switch(to, _, take) =>
                  val kept = l.blocks.takeWhile(_.point != to) ++ l.blocks.find(_.point == to)
                  l.copy(blocks = kept ++ take.map(h => LocalBlock(h.point, SelectView.of(h))))
                case _ => l
            }
            .blocks
            .last
            .point
        val rng = new scala.util.Random(seed)
        val results = (0 until 6).map(_ => run(rng.shuffle(candidates))).toSet
        assertEquals(results.size, 1)
    }
  }
