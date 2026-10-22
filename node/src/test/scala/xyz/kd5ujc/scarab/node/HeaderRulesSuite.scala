// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf}
import cats.effect.unsafe.implicits.global
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{HeaderError, PraosError, OCertError, PoolDistribution, StakeView}
import xyz.kd5ujc.scarab.consensus.Praos
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}
import xyz.kd5ujc.scarab.ledger.state.VocabError
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, EraLedgerState, MirrorState, ProtocolState}
import xyz.kd5ujc.scarab.ledger.state.EpochNo
import xyz.kd5ujc.scarab.node.read.ChainRead.given
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
class HeaderRulesSuite extends ScalaCheckSuite:
  private val rules = NodeHeaderRules[MirrorState](ForgedChain.config, ForgedChain.globals)

  private lazy val forged: ForgedChain = ForgedChain.forge(0 until 90, epoch = 0).unsafeRunSync()

  private def ref(h: Header.Shelley) = BlockRef(h.slot, h.hash, h.blockNo)

  /** The header state before block `i`. */
  private def before(i: Int): NodeHeaderState.Shelley =
    NodeHeaderState.Shelley(
      if i == 0 then forged.genesis.protocol else forged.states(i - 1).protocol,
      forged.headers.take(i).lastOption.map(ref),
      forged.genesis.consensus.byron.firstShelleyEpoch,
      None
    )

  private def expected(i: Int): NodeHeaderState.Shelley =
    before(i).copy(state = forged.states(i).protocol, previous = Some(ref(forged.headers(i))))

  /** The state and tip of the chain after block `i - 1 - lag` (the genesis for a negative index).
    */
  private def anchor(i: Int, lag: Int): (MirrorState, Option[BlockRef]) =
    val j = i - 1 - lag
    val (state, tip) =
      if j < 0 then (forged.genesis, None) else (forged.states(j), Some(ref(forged.headers(j))))
    (state, tip)

  test("the forged chain is long enough to be a test") {
    assert(forged.headers.size >= 30, forged.headers.size.toString)
  }

  test("every header validates from the state after its parent, with the state the block gives") {
    forged.headers.zipWithIndex.foreach { case (h, i) =>
      val (state, tip) = anchor(i, 0)
      rules.validate(state, tip, before(i), h) match
        case Outcome.Valid(next) => assertEquals(next, expected(i), s"block $i")
        case other               => fail(s"block $i: $other")
    }
  }

  property("from an anchor a few blocks behind the parent the outcome is the same") {
    forAll(Gen.choose(0, forged.headers.size - 1), Gen.choose(0, 3)) { (i, lag) =>
      val (state, tip) = anchor(i, lag)
      rules.validate(state, tip, before(i), forged.headers(i)) ==
        Outcome.Valid(expected(i))
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
    val ahead = before(i).state.dep match
      case ChainDepState.Praos(p) =>
        before(i).state.copy(dep =
          ChainDepState.Praos(
            p.copy(ocertCounters =
              p.ocertCounters.transform((_, v) => Word64.fromUnsigned(v.bits + 5))
            )
          )
        )
      case other => fail(s"not a Praos state: $other")
    assert(before(i).state.counters.nonEmpty)
    rules.validate(state, tip, before(i).copy(state = ahead), forged.headers(i)) match
      case Outcome.Invalid(
            HeaderError.Praos(PraosError.OCert(OCertError.CounterOutOfRange(current, got)))
          ) =>
        assertEquals(current, forged.headers(i).opCert.sequence + 5)
        assertEquals(got, forged.headers(i).opCert.sequence)
      case other => fail(other.toString)
  }

  test("a header beyond the stability window of the anchor has to wait for the chain to advance") {
    val i = forged.headers.indexWhere(
      _.slot > forged.headers.head.slot + ForgedChain.geometry.stabilityWindow + 5
    )
    assert(i > 3)
    val laterTip = Some(ref(forged.headers(0)))
    val later = forged.states(0)
    assert(
      rules.validate(later, laterTip, before(i), forged.headers(i)).isInstanceOf[Outcome.Later[?]]
    )
    // from an anchor within the window the same header is checked
    val (near, nearTip) = anchor(i, 2)
    assertEquals(
      rules.validate(near, nearTip, before(i), forged.headers(i)),
      Outcome.Valid(expected(i))
    )
  }

  test("an Origin ledger forecast excludes its native stability-window endpoint") {
    val limit = ForgedChain.geometry.stabilityWindow
    val header = forged.headers.head.copy(slot = limit)
    assertEquals(
      rules.validate(forged.genesis, None, rules.at(forged.genesis), header),
      Outcome.Later(xyz.kd5ujc.scarab.consensus.HeaderRules.ForecastReason.Horizon(limit, 0))
    )
  }

  test("the former far-slot Origin bypass now waits for a ledger forecast") {
    val original = ForgedChain.forge(5000 until 5008).unsafeRunSync()
    val header = original.headers.head
    assert(header.slot >= 5000)
    assertEquals(
      rules.validate(original.genesis, None, rules.at(original.genesis), header),
      Outcome.Later(xyz.kd5ujc.scarab.consensus.HeaderRules.ForecastReason.Horizon(header.slot, 0))
    )
  }

  /** The state with its new-epoch state `by` epochs later. */
  private def withEpoch(m: MirrorState, by: Long): MirrorState =
    m.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        val epoch = EpochNo
          .fromBigInt(i.state.nes.epoch.toBigInt + by)
          .fold(v => fail(v.toString), identity)
        m.copy(ext =
          m.ext.copy(ledgerState =
            m.ext.ledgerState.withState(
              EraLedgerState.InShelleyEra(
                i.era,
                i.state.copy(nes = i.state.nes.copy(epoch = epoch))
              )
            )
          )
        )
      case _ => fail("the state is in Byron")

  test("a header whose epoch precedes the ledger's is a structure error") {
    val tip = Some(ref(forged.headers(4)))
    val state = forged.states(4)
    val ahead = withEpoch(state, 1)
    assert(
      rules.validate(ahead, tip, before(5), forged.headers(5)) match
        case Outcome.Invalid(HeaderError.Structure(_)) => true
        case _                                         => false
    )
  }

  test("a Shelley header state cannot accept a Byron header") {
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
      rules.validate(forged.genesis, None, rules.at(forged.genesis), h),
      Outcome.Invalid(
        HeaderError.Structure(xyz.kd5ujc.scarab.ledger.HeaderStructureProblem.MissingByronLedger)
      )
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
    val anchorState = crossing.states(last)
    var prev = rules.at(crossing.states(last))
    val window = (firstOfNext until crossing.headers.size)
      .takeWhile(i =>
        crossing.headers(i).slot <= crossing.headers(last).slot + geometry.stabilityWindow
      )
    assert(window.nonEmpty)
    window.foreach { i =>
      rules.validate(anchorState, anchorTip, prev, crossing.headers(i)) match
        case Outcome.Valid(next) =>
          assertEquals(next.protocol, Some(crossing.states(i).protocol), s"block $i")
          prev = next
        case other => fail(s"block $i: $other")
    }
  }

  test("the next epoch's parameter-dependent envelope is checked before its body arrives") {
    val first = crossing.headers.indexWhere(h => ForgedChain.geometry.epochOf(h.slot) == 6)
    assert(first > 0)
    val last = first - 1
    val state = crossing.states(last)
    val tip = Some(ref(crossing.headers(last)))
    val tooLarge = crossing.headers(first).copy(bodySize = BigInt(1) << 40)
    rules.validate(state, tip, rules.at(state), tooLarge) match
      case Outcome.Invalid(
            HeaderError.Envelope(_: xyz.kd5ujc.scarab.ledger.EnvelopeError.BlockSizeTooLarge)
          ) =>
        ()
      case other => fail(other.toString)
  }

  test("Praos preserves threshold, registration, vocabulary and certificate causes") {
    val h = forged.headers.head
    val state = before(0).state
    val pool = Blake2b.hash224.hash(h.issuerVkey)
    val vrf = Blake2b.hash256.hash(h.vrfVkey)
    val known = PoolDistribution(Map(pool -> BigInt(1)), Map(pool -> vrf))
    def validate(
        header: Header.Shelley = h,
        pools: PoolDistribution = known,
        config: xyz.kd5ujc.scarab.consensus.PraosConfig = ForgedChain.config
    ) =
      Praos.update(state, header, pools, config)
    assertEquals(
      Praos.update(
        ProtocolState.initial(xyz.kd5ujc.scarab.protocol.Era.Shelley, Bytes.empty),
        h,
        known,
        ForgedChain.config
      ),
      Left(PraosError.WrongState(h.era))
    )
    assertEquals(
      validate(pools = known.copy(stake = Map.empty)),
      Left(PraosError.IssuerWithoutStake(pool))
    )
    assertEquals(
      validate(pools = known.copy(vrf = Map.empty)),
      Left(PraosError.UnregisteredPool(pool))
    )
    val wrong = Bytes(Vector.fill(32)(0.toByte))
    assertEquals(
      validate(pools = known.copy(vrf = Map(pool -> wrong))),
      Left(PraosError.VrfKeyMismatch(pool, vrf, Set(wrong)))
    )
    assertEquals(
      Praos.validate(state, h, StakeView.Unknown(Map.empty), ForgedChain.config),
      Left(PraosError.UnregisteredPool(pool))
    )
    assertEquals(
      Praos.validate(state, h, StakeView.Unknown(Map(pool -> Set(wrong))), ForgedChain.config),
      Left(PraosError.VrfKeyMismatch(pool, vrf, Set(wrong)))
    )
    assertEquals(
      validate(pools = known.copy(stake = Map(pool -> BigInt(-1)))),
      Left(PraosError.LeaderThreshold(Leader.Error.InvalidFraction(-1, -1)))
    )
    val active = Leader.Fraction.checked(3, 4).fold(e => fail(e.message), identity)
    assertEquals(
      validate(config = ForgedChain.config.copy(activeSlotsCoeff = active)),
      Left(PraosError.LeaderThreshold(Leader.Error.UnsupportedActiveSlotsCoefficient(3, 4)))
    )
    assertEquals(
      validate(header = h.copy(slot = -1)),
      Left(PraosError.Vocabulary(VocabError.Negative(VocabError.Kind.SlotNo, -1)))
    )
    assertEquals(
      validate(header = h.copy(opCert = h.opCert.copy(sequence = -1))),
      Left(PraosError.Vocabulary(VocabError.Negative(VocabError.Kind.OcertCounter, -1)))
    )
    assertEquals(
      validate(header = tampered(h, 1, 0)),
      Left(PraosError.VrfRejected(Vrf.Result.OutputMismatch))
    )
    assertEquals(
      validate(header = tampered(h, 2, 0)),
      Left(PraosError.OCert(OCertError.KesSignatureRejected))
    )
  }

  test("certificate and KES failures retain their crypto causes through header validation") {
    val h = forged.headers.head
    val cause = xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate.Failure.Signature(
      xyz.kd5ujc.scarab.crypto.witness.WitnessInputError.MalformedSignatureLength(0)
    )
    assertEquals(
      invalid(0, h.copy(opCert = h.opCert.copy(sigma = Bytes.empty))),
      Some(HeaderError.Praos(PraosError.OCert(OCertError.OpCertFailure(cause))))
    )
    val kesCause = xyz.kd5ujc.scarab.crypto.kes.Sum6Kes.Signature448
      .fromBytes(Bytes.empty)
      .swap
      .toOption
      .getOrElse(fail("empty KES signature accepted"))
    assertEquals(
      invalid(0, h.copy(kesSignature = Bytes.empty)),
      Some(HeaderError.Praos(PraosError.OCert(OCertError.KesFailure(kesCause))))
    )
  }
