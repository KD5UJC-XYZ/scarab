// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf}

import cats.effect.unsafe.implicits.global
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.HeaderError
import xyz.kd5ujc.scarab.consensus.PraosState
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** Header-first validation (G6) against headers a pool really forged: it accepts exactly what the
  * block rules accept, with the same resulting state, from any recent anchor; and a header changed
  * in any field it signs or proves is refused.
  */
class HeaderRulesSuite extends ScalaCheckSuite:
  private val rules = NodeHeaderRules(ForgedChain.config, ForgedChain.globals)

  private lazy val forged: ForgedChain = ForgedChain.forge(5000 until 5090).unsafeRunSync()

  private def ref(h: Header.Shelley) = BlockRef(h.slot, h.hash, h.blockNo)

  /** The header state before block `i`. */
  private def before(i: Int): PraosState =
    if i == 0 then forged.genesis.praos else forged.states(i - 1).praos

  /** The state and tip of the chain after block `i - 1 - lag` (the genesis for a negative index).
    */
  private def anchor(i: Int, lag: Int): (NodeState, Option[BlockRef]) =
    val j = i - 1 - lag
    if j < 0 then (forged.genesis, None) else (forged.states(j), Some(ref(forged.headers(j))))

  test("the forged chain is long enough to be a test") {
    assert(forged.headers.size >= 30, forged.headers.size.toString)
  }

  test("every header validates from the state after its parent, with the state the block gives") {
    forged.headers.zipWithIndex.foreach { case (h, i) =>
      val (state, tip) = anchor(i, 0)
      rules.validate(state, tip, before(i), h) match
        case Outcome.Valid(next) => assertEquals(next, forged.states(i).praos, s"block $i")
        case other               => fail(s"block $i: $other")
    }
  }

  property("from an anchor a few blocks behind the parent the outcome is the same") {
    forAll(Gen.choose(0, forged.headers.size - 1), Gen.choose(0, 3)) { (i, lag) =>
      val (state, tip) = anchor(i, lag)
      rules.validate(state, tip, before(i), forged.headers(i)) ==
        Outcome.Valid(forged.states(i).praos)
    }
  }

  private def flip(bytes: Bytes, at: Int): Bytes =
    val a = bytes.toArray
    a(at % a.length) = (a(at % a.length) ^ 0x01).toByte
    Bytes.fromArray(a)

  private def invalid(i: Int, h: Header.Shelley): Option[HeaderError] =
    val (state, tip) = anchor(i, 0)
    rules.validate(state, tip, before(i), h) match
      case Outcome.Invalid(e) => Some(e)
      case _                  => None

  private def tampered(h: Header.Shelley, kind: Int, at: Int): Header.Shelley = kind match
    case 0 =>
      h.vrf match
        case HeaderVrf.Praos(c) =>
          h.copy(vrf = HeaderVrf.Praos(c.copy(proof = flip(c.proof, at))))
        case other => fail(s"unexpected $other")
    case 1 =>
      h.vrf match
        case HeaderVrf.Praos(c) =>
          h.copy(vrf = HeaderVrf.Praos(c.copy(output = flip(c.output, at))))
        case other => fail(s"unexpected $other")
    case 2 => h.copy(kesSignature = flip(h.kesSignature, at))
    case 3 => h.copy(opCert = h.opCert.copy(sigma = flip(h.opCert.sigma, at)))
    case 4 => h.copy(opCert = h.opCert.copy(hotVkey = flip(h.opCert.hotVkey, at)))
    case 5 => h.copy(issuerVkey = flip(h.issuerVkey, at))
    case 6 => h.copy(vrfVkey = flip(h.vrfVkey, at))
    case _ => h.copy(slot = h.slot + 1)

  property("a header changed in a field that is proven or signed is refused") {
    forAll(
      Gen.choose(0, forged.headers.size - 1),
      Gen.choose(0, 7),
      Gen.choose(0, 400)
    ) { (i, kind, at) =>
      invalid(i, tampered(forged.headers(i), kind, at)).isDefined
    }
  }

  test("the refusals name the rule family: Praos for keys and proofs") {
    val h = forged.headers(10)
    assert(invalid(10, tampered(h, 0, 3)).exists(_.isInstanceOf[HeaderError.Praos]))
    assert(invalid(10, tampered(h, 2, 3)).exists(_.isInstanceOf[HeaderError.Praos]))
    assert(invalid(10, tampered(h, 5, 3)).exists(_.isInstanceOf[HeaderError.Praos]))
  }

  test("a block body larger than the parameters allow is an envelope error") {
    val h = forged.headers(3).copy(bodySize = BigInt(1) << 40)
    assert(invalid(3, h).exists(_.isInstanceOf[HeaderError.Envelope]))
  }

  test("an operational certificate counter that went backwards is refused") {
    val i = 12
    val (state, tip) = anchor(i, 0)
    val ahead = before(i).copy(counters = before(i).counters.map { case (k, v) => k -> (v + 5) })
    assert(before(i).counters.nonEmpty)
    rules.validate(state, tip, ahead, forged.headers(i)) match
      case Outcome.Invalid(HeaderError.Praos(r)) => assert(r.contains("counter"), r)
      case other                                 => fail(other.toString)
  }

  test("a header beyond the stability window of the anchor has to wait for the chain to advance") {
    val i = forged.headers.indexWhere(_.slot > 5000 + ForgedChain.geometry.stabilityWindow + 5)
    assert(i > 3)
    val (later, laterTip) = (forged.states(0), Some(ref(forged.headers(0))))
    assert(
      rules.validate(later, laterTip, before(i), forged.headers(i)).isInstanceOf[Outcome.Later[?]]
    )
    // from an anchor within the window the same header is checked
    val (near, nearTip) = anchor(i, 2)
    assertEquals(
      rules.validate(near, nearTip, before(i), forged.headers(i)),
      Outcome.Valid(forged.states(i).praos)
    )
  }

  test("a header whose epoch precedes the ledger's is a structure error") {
    val (state, tip) = anchor(5, 0)
    val ahead = state.copy(ledger = state.ledger.copy(epoch = state.ledger.epoch + 1))
    assert(
      rules.validate(ahead, tip, before(5), forged.headers(5)) match
        case Outcome.Invalid(HeaderError.Structure(_)) => true
        case _                                         => false
    )
  }

  test("Byron headers are not checked header-first and leave the state as it is") {
    val h = Header.Byron(
      Bytes.empty,
      Bytes.empty,
      boundary = false,
      epoch = 0,
      slotInEpoch = 1,
      epochSlots = 100,
      blockNo = 1,
      prevHash = None
    )
    assertEquals(
      rules.validate(forged.genesis, None, forged.genesis.praos, h),
      Outcome.Unchecked("Byron header: validated with its block", Some(forged.genesis.praos))
    )
  }

  private lazy val crossing: ForgedChain =
    ForgedChain.forge(5950 until 6050, seed = 3).unsafeRunSync()

  test("across an epoch boundary the next epoch's headers are validated from the previous epoch") {
    val geometry = ForgedChain.geometry
    val firstOfNext = crossing.headers.indexWhere(h => geometry.epochOf(h.slot) == 6)
    assert(firstOfNext > 0, "the forged chain must cross the boundary")
    // every header of epoch 6 within the window of the last block of epoch 5
    val last = firstOfNext - 1
    val anchorState = crossing.states(last)
    val anchorTip = Some(
      BlockRef(
        crossing.headers(last).slot,
        crossing.headers(last).hash,
        crossing.headers(last).blockNo
      )
    )
    var prev = anchorState.praos
    val window = (firstOfNext until crossing.headers.size)
      .takeWhile(i =>
        crossing.headers(i).slot <= crossing.headers(last).slot + geometry.stabilityWindow
      )
    assert(window.nonEmpty)
    window.foreach { i =>
      rules.validate(anchorState, anchorTip, prev, crossing.headers(i)) match
        case Outcome.Valid(next) =>
          assertEquals(next, crossing.states(i).praos, s"block $i")
          prev = next
        case other => fail(s"block $i: $other")
    }
  }
