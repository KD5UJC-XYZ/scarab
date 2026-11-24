// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.ledger.Fixtures
import xyz.kd5ujc.scarab.protocol.ChainSync

/** A candidate trimmed to our chain's anchor keeps exactly the part from the anchor on, as the
  * reference's `AF.splitAfterPoint theirFrag (AF.anchorPoint ourFrag')` (issue 810).
  */
class HeaderChainTrimSuite extends ScalaCheckSuite:
  private val chain = Fixtures.chain(30)
  private val other = Fixtures.chain(4, salt = 1)

  private def candidate(prefixSize: Int, entrySize: Int): HeaderChain[Int] =
    HeaderChain(
      chain(prefixSize).point,
      Some(prefixSize),
      chain
        .slice(prefixSize + 1, prefixSize + 1 + entrySize)
        .map(h => HeaderChain.Entry(h, Some(h.blockNo.toInt))),
      chain.take(prefixSize).map(h => HeaderChain.Known(h.point, Some(h.blockNo.toInt)))
    )

  property("the points from the anchor on are kept, with the anchor's state; none before it") {
    forAll(Gen.choose(0, 10), Gen.choose(0, 15), Gen.choose(0, 29)) { (prefixSize, entrySize, at) =>
      val hc = candidate(prefixSize, entrySize)
      val anchor = chain(at).point
      val trimmed = hc.trimmedTo(anchor)
      val points = hc.points
      points.indexOf(anchor) match
        case -1 => trimmed == hc
        case i =>
          trimmed.points == points.drop(i) &&
          trimmed.tipPoint == hc.tipPoint &&
          trimmed.tipState == hc.tipState &&
          (anchor == hc.base || hc.entries.forall(_.header.point != anchor) ||
            (trimmed.base == anchor && trimmed.baseState == Some(chain(at).blockNo.toInt)))
    }
  }

  test("an anchor off the candidate leaves it as it is") {
    val hc = candidate(3, 5)
    assertEquals(hc.trimmedTo(other(2).point), hc)
    assertEquals(hc.trimmedTo(ChainSync.Point.Origin), hc)
  }
