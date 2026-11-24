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
  // the same block numbers and slots as `chain`'s first headers, other hashes
  private val sameSlots = Fixtures.chain(6, salt = 3)

  private def state(known: Boolean, n: BigInt): Option[Int] = Option.when(known)(n.toInt)

  property("HeaderChain.contains is stateAfter's answer for every hash, on and off the chain") {
    forAll(Gen.choose(0, 24), Gen.choose(0, 11), Gen.listOfN(24, Gen.oneOf(true, false))) {
      (prefixSize, entrySize, known) =>
        val prefix = chain
          .take(prefixSize)
          .map(h => HeaderChain.Known(h.point, state(known(h.blockNo.toInt), h.blockNo)))
        // past the end of `chain` the base is Origin
        val base = chain.lift(prefixSize).fold(ChainSync.Point.Origin)(_.point)
        val entries = chain
          .slice(prefixSize + 1, prefixSize + 1 + entrySize)
          .map(h => HeaderChain.Entry(h, state(known(h.blockNo.toInt), h.blockNo)))
        val hc = HeaderChain(base, state(known(0), -1), entries, prefix)
        (chain ++ other ++ sameSlots).forall(h =>
          hc.contains(h.hash) == hc.stateAfter(h.hash).isDefined
        )
    }
  }

  test(
    "HeaderChain.contains is stateAfter's answer with Origin in the prefix before a block base"
  ) {
    // as HeaderGate.startAt captures an Origin anchor when the intersection is a later block
    val prefix = HeaderChain.Known[Int](ChainSync.Point.Origin, Some(0)) +:
      chain.take(3).map(h => HeaderChain.Known(h.point, Option.empty[Int]))
    val hc = HeaderChain(chain(3).point, Some(3), Vector(HeaderChain.Entry(chain(4), None)), prefix)
    (chain ++ other ++ sameSlots).foreach(h =>
      assertEquals(hc.contains(h.hash), hc.stateAfter(h.hash).isDefined, h.hash.hex)
    )
    assert(hc.contains(chain(0).hash) && hc.contains(chain(4).hash) && !hc.contains(other(0).hash))
  }

  property("ChainSelection.Local.contains is the anchor or one of the blocks") {
    forAll(Gen.choose(-1, 23)) { anchorAt =>
      // -1: anchored at Origin, every header a block
      val local = ChainSelection.Local(
        chain.lift(anchorAt).fold(ChainSync.Point.Origin)(_.point),
        chain.lift(anchorAt).fold(SelectView(-1, 0, None, 0, None))(SelectView.of),
        chain.drop(anchorAt + 1).map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
      )
      (ChainSync.Point.Origin +: (chain ++ other ++ sameSlots).map(_.point)).forall(p =>
        local.contains(p) == (p == local.anchor || local.blocks.exists(_.point == p))
      )
    }
  }
