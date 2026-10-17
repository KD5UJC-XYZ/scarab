// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.{Fixtures, Header}
import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** The window chain selection reads is kept incrementally by the follower (`Follower.commit`); it
  * must equal the one rebuilt from the whole chain ([[ChainSelection.Local.of]], what
  * `Follower.local` did for every header) after any sequence of extensions and rollbacks of at most
  * `k` blocks.
  */
class LocalWindowSuite extends ScalaCheckSuite:
  import ChainSelection.{Local, LocalBlock}

  /** A step of the walk: append a block (a different `salt` makes a different fork) or roll back.
    */
  private enum Op:
    case Extend(salt: Int)
    case Rollback(depth: Int)

  private val ops: Gen[Op] = Gen.frequency(
    8 -> Gen.choose(0, 5).map(Op.Extend(_)),
    1 -> Gen.choose(0, 40).map(Op.Rollback(_))
  )

  /** The chain, the headers of its blocks, the views recorded so far and the kept window. */
  private final case class Walk(
      chain: VolatileChain[Unit],
      headers: Vector[Header],
      views: Map[Bytes, SelectView],
      window: Local
  )

  private def viewOf(known: Map[Bytes, SelectView])(ref: BlockRef): SelectView =
    known.getOrElse(ref.hash, SelectView(ref.blockNo, ref.slot, None, 0, None))

  private def start(k: Int): Walk =
    val chain = VolatileChain.genesis[Unit](k, ())
    Walk(chain, Vector.empty, Map.empty, Local.of(chain, viewOf(Map.empty)))

  /** One step, updating the window as `Follower.commit` does. */
  private def step(w: Walk, op: Op): Walk = op match
    case Op.Extend(salt) =>
      val header = Fixtures.chain(1, salt, w.headers.lastOption).head
      val (grown, immutable) = w.chain.extend(header, ()).fold(e => fail(e.message), identity)
      val ref = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
      val view = SelectView.of(header)
      Walk(
        grown,
        w.headers :+ header,
        w.views.updated(header.hash, view),
        w.window.extended(LocalBlock(ref.point, view), immutable.size)
      )
    case Op.Rollback(depth) =>
      val back = depth.min(w.chain.blocks.size)
      val target = w.chain.candidates(back)
      val rolled = w.chain.rollback(target).fold(e => fail(e.message), identity)
      Walk(
        rolled,
        w.headers.dropRight(back),
        w.views,
        w.window.rolledBackTo(target).getOrElse(fail("rollback target not in the window"))
      )

  private def check(w: Walk): Unit =
    assertEquals(w.window, Local.of(w.chain, viewOf(w.views)))

  property("the kept window equals the one rebuilt, over extensions and rollbacks of at most k") {
    forAll(Gen.choose(1, 12), Gen.listOfN(120, ops)) { (k, walk) =>
      walk.foldLeft(start(k)) { (w, op) =>
        val next = step(w, op)
        check(next)
        next
      }
      true
    }
  }

  property("k = 0: the block is its own anchor") {
    forAll(Gen.listOfN(30, ops)) { walk =>
      walk.foldLeft(start(0)) { (w, op) =>
        val next = step(w, op)
        check(next)
        next
      }
      true
    }
  }

  test("the full window of mainnet's k = 2160, over 5,000 blocks and a rollback of k") {
    val full = (0 until 5000).foldLeft(start(2160))((w, _) => step(w, Op.Extend(0)))
    check(full)
    assertEquals(full.window.blocks.size, 2160)
    check(step(full, Op.Rollback(2160)))
  }
