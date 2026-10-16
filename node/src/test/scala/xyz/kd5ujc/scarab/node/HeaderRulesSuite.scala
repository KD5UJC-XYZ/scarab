// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf}
import cats.effect.unsafe.implicits.global
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.HeaderError
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  ConsensusMirrorEnv,
  MirrorState,
  ProtocolState
}
import xyz.kd5ujc.scarab.ledger.state.MirrorEnv
import xyz.kd5ujc.scarab.node.ntc.EraHistory
import xyz.kd5ujc.scarab.node.read.ChainRead
import xyz.kd5ujc.scarab.ledger.state.Word64
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** Header-first validation (G6) against headers a pool really forged: it accepts exactly what the
  * block rules accept, with the same resulting state, from any recent anchor; and a header changed
  * in any field it signs or proves is refused.
  */
abstract class HeaderRulesBehaviour[S: ChainRead] extends ScalaCheckSuite:
  /** The anchor in the form of the state under test. */
  protected def lift(state: NodeState, tip: Option[BlockRef]): S

  private val rules = NodeHeaderRules[S](ForgedChain.config, ForgedChain.globals)

  private lazy val forged: ForgedChain = ForgedChain.forge(5000 until 5090).unsafeRunSync()

  private def ref(h: Header.Shelley) = BlockRef(h.slot, h.hash, h.blockNo)

  /** The header state before block `i`. */
  private def before(i: Int): ProtocolState =
    if i == 0 then forged.genesis.protocol else forged.states(i - 1).protocol

  /** The state and tip of the chain after block `i - 1 - lag` (the genesis for a negative index).
    */
  private def anchor(i: Int, lag: Int): (S, Option[BlockRef]) =
    val j = i - 1 - lag
    val (state, tip) =
      if j < 0 then (forged.genesis, None) else (forged.states(j), Some(ref(forged.headers(j))))
    (lift(state, tip), tip)

  test("the forged chain is long enough to be a test") {
    assert(forged.headers.size >= 30, forged.headers.size.toString)
  }

  test("every header validates from the state after its parent, with the state the block gives") {
    forged.headers.zipWithIndex.foreach { case (h, i) =>
      val (state, tip) = anchor(i, 0)
      rules.validate(state, tip, before(i), h) match
        case Outcome.Valid(next) => assertEquals(next, forged.states(i).protocol, s"block $i")
        case other               => fail(s"block $i: $other")
    }
  }

  property("from an anchor a few blocks behind the parent the outcome is the same") {
    forAll(Gen.choose(0, forged.headers.size - 1), Gen.choose(0, 3)) { (i, lag) =>
      val (state, tip) = anchor(i, lag)
      rules.validate(state, tip, before(i), forged.headers(i)) ==
        Outcome.Valid(forged.states(i).protocol)
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
    val ahead = before(i).dep match
      case ChainDepState.Praos(p) =>
        before(i).copy(dep =
          ChainDepState.Praos(
            p.copy(ocertCounters =
              p.ocertCounters.transform((_, v) => Word64.fromUnsigned(v.bits + 5))
            )
          )
        )
      case other => fail(s"not a Praos state: $other")
    assert(before(i).counters.nonEmpty)
    rules.validate(state, tip, ahead, forged.headers(i)) match
      case Outcome.Invalid(HeaderError.Praos(r)) => assert(r.contains("counter"), r)
      case other                                 => fail(other.toString)
  }

  test("a header beyond the stability window of the anchor has to wait for the chain to advance") {
    val i = forged.headers.indexWhere(_.slot > 5000 + ForgedChain.geometry.stabilityWindow + 5)
    assert(i > 3)
    val laterTip = Some(ref(forged.headers(0)))
    val later = lift(forged.states(0), laterTip)
    assert(
      rules.validate(later, laterTip, before(i), forged.headers(i)).isInstanceOf[Outcome.Later[?]]
    )
    // from an anchor within the window the same header is checked
    val (near, nearTip) = anchor(i, 2)
    assertEquals(
      rules.validate(near, nearTip, before(i), forged.headers(i)),
      Outcome.Valid(forged.states(i).protocol)
    )
  }

  test("a header whose epoch precedes the ledger's is a structure error") {
    val tip = Some(ref(forged.headers(4)))
    val state = forged.states(4)
    val ahead = lift(state.copy(ledger = state.ledger.copy(epoch = state.ledger.epoch + 1)), tip)
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
      rules.validate(lift(forged.genesis, None), None, forged.genesis.protocol, h),
      Outcome.Unchecked("Byron header: validated with its block", Some(forged.genesis.protocol))
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
    val anchorTip = Some(
      BlockRef(
        crossing.headers(last).slot,
        crossing.headers(last).hash,
        crossing.headers(last).blockNo
      )
    )
    val anchorState = lift(crossing.states(last), anchorTip)
    var prev = crossing.states(last).protocol
    val window = (firstOfNext until crossing.headers.size)
      .takeWhile(i =>
        crossing.headers(i).slot <= crossing.headers(last).slot + geometry.stabilityWindow
      )
    assert(window.nonEmpty)
    window.foreach { i =>
      rules.validate(anchorState, anchorTip, prev, crossing.headers(i)) match
        case Outcome.Valid(next) =>
          assertEquals(next, crossing.states(i).protocol, s"block $i")
          prev = next
        case other => fail(s"block $i: $other")
    }
  }

/** The header rules on the node's flat state (`--rules old`). */
class HeaderRulesSuite extends HeaderRulesBehaviour[NodeState]()(using HeaderRulesSuite.flat):
  protected def lift(state: NodeState, tip: Option[BlockRef]): NodeState = state

object HeaderRulesSuite:
  /** The read environment of the forged chain's configuration. */
  val env: ConsensusMirrorEnv = ConsensusMirrorEnv(
    MirrorEnv(ForgedChain.globals.network, ForgedChain.geometry, ForgedChain.globals.updateQuorum),
    ForgedChain.config.forks,
    EraHistory.ByronParams(
      BigInt(ForgedChain.geometry.securityParam) * 10,
      BigInt(20000),
      BigInt(ForgedChain.geometry.securityParam)
    )
  )

  val flat: ChainRead[NodeState] = ChainRead.nodeState(env)

/** The same rules and the same headers, from the mirror of each anchor: the answers are the ones
  * the flat state gives.
  */
class HeaderRulesMirrorSuite extends HeaderRulesBehaviour[MirrorState]:
  protected def lift(state: NodeState, tip: Option[BlockRef]): MirrorState =
    MirrorRun
      .run(HeaderRulesSuite.env)(_.toMirror(tip, state))
      .fold(e => fail(e.toString), _.value)
