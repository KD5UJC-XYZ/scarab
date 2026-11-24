// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.ledger.Fixtures
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The indexed lookups of issue 1394 answer what the scans they replace answered. */
class IndexedLookupSuite extends ScalaCheckSuite:
  private val chain = Fixtures.chain(24)
  private val other = Fixtures.chain(6, salt = 1)

  property("HeaderChain.contains is stateAfter's answer for every hash, on and off the chain") {
    forAll(Gen.choose(0, 12), Gen.choose(0, 11)) { (prefixSize, entrySize) =>
      val prefix = chain
        .take(prefixSize)
        .map(h => HeaderChain.Known(h.point, Some(h.blockNo.toInt)))
      val base = chain.lift(prefixSize).fold(ChainSync.Point.Origin)(_.point)
      val entries = chain
        .slice(prefixSize + 1, prefixSize + 1 + entrySize)
        .map(h => HeaderChain.Entry(h, Some(h.blockNo.toInt)))
      val hc = HeaderChain(base, Some(-1), entries, prefix)
      (chain ++ other).forall(h => hc.contains(h.hash) == hc.stateAfter(h.hash).isDefined)
    }
  }

  property("ChainSelection.Local.contains is the anchor or one of the blocks") {
    forAll(Gen.choose(0, 23)) { anchorAt =>
      val anchor = chain(anchorAt)
      val local = ChainSelection.Local(
        anchor.point,
        SelectView.of(anchor),
        chain.drop(anchorAt + 1).map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
      )
      (ChainSync.Point.Origin +: (chain ++ other).map(_.point)).forall(p =>
        local.contains(p) == (p == local.anchor || local.blocks.exists(_.point == p))
      )
    }
  }
