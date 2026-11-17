// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraPParams
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra, ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Writer}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, StateLayout}

import DecodeError.{Arity, Kind}

/** Dijkstra's protocol parameters in the `state` layout of the Leios build (#826): the four
  * parameter records of the first Dijkstra state the build wrote (`fixtures/leios/state/
  * dijkstra-203`), the governance state's current and previous parameters and its enact state's,
  * each read and written again byte for byte, its values named, read at the reference's types
  * ([[DijkstraPParams]]); the governance state and enact state around them read at Dijkstra's
  * parameters; malformed forms refused with their causes; and the laws on generated parameters.
  */
class DijkstraPParamsStateSuite extends ScalaCheckSuite:
  private val codec = Params.codecIn(StateLayout.Leios, ShelleyBasedEra.SDijkstra)

  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SDijkstra)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def at(n: Node, path: Int*): Node = path.foldLeft(n)((m, i) => items(m)(i))

  private val root =
    val bytes = Files.readAllBytes(Path.of("fixtures/leios/state/dijkstra-203/state"))
    Cbor.decode(Bytes.wrap(bytes), HaskellState.limits).fold(e => fail(e.toString), identity)

  /** The `NewEpochState` of the Leios layout: `[1, [ledger, header, Peras]]`, the ledger's
    * telescope at its eighth era, Dijkstra, the current state's `[version, [tip, state]]`.
    */
  private val nes = at(root, 1, 0, 7, 1, 1, 1)

  /** `EpochState` 's ledger state, its `UTxOState`, the governance state: `[proposals, committee,
    * constitution, current, previous, future, DRep pulser]`.
    */
  private val gov = at(nes, 3, 1, 1, 3)

  /** The pulser `[snapshot, ratify state]`, the ratify state's enact state: `[committee,
    * constitution, current, previous, treasury, withdrawals, previous actions]`.
    */
  private val enact = at(gov, 6, 1, 0)

  private val terms = Vector(
    "current" -> at(gov, 3),
    "previous" -> at(gov, 4),
    "enacted current" -> at(enact, 2),
    "enacted previous" -> at(enact, 3)
  )

  private def read[A](c: Codec[A], n: Node): A = c.decode(n).fold(e => fail(e.render), identity)

  private def tree(bytes: Bytes): Node =
    Cbor.decode(bytes, HaskellState.limits).fold(e => fail(e.toString), identity)

  private def failure[A](c: Codec[A], bytes: Bytes): DecodeError =
    c.decodeBytes(bytes, HaskellState.limits).fold(identity, a => fail(s"decoded $a"))

  private val current = read(codec, at(gov, 3))

  /** The current parameters with the item at `index` written by `put`. */
  private def withItem(index: Int)(put: Writer => Unit): Bytes =
    val all = items(at(gov, 3))
    StateGen.item { w =>
      w.arrayHead(all.size)
      all.indices.foreach(i => if i == index then put(w) else w.raw(all(i).original))
    }

  private def ratio(w: Writer, num: BigInt, den: BigInt): Unit =
    w.tag(30); w.arrayHead(2); w.uint(num); w.uint(den)

  test("slot 203's four parameter records: 54 items each, read and written byte for byte") {
    terms.foreach { (name, n) =>
      assertEquals(items(n).size, 54, name)
      assertEquals(codec.write(read(codec, n)), Right(n.original), name)
    }
  }

  test("slot 203's current parameters: Conway's and the fields Dijkstra adds") {
    val p = current
    assertEquals((p.minFeeA, p.minFeeB, p.protocolMajor), (BigInt(44), BigInt(155381), BigInt(12)))
    // the cost models of PlutusV1 to PlutusV4
    assertEquals(p.costModels.keySet, Set(0, 1, 2, 3))
    assertEquals(p.maxRefScriptSizePerBlock, BigInt(1048576))
    assertEquals(p.maxRefScriptSizePerTx, BigInt(204800))
    assertEquals(p.refScriptCostStride, BigInt(25600))
    assertEquals(p.refScriptCostMultiplier, Rational(6, 5))
    assertEquals(p.maxPledgeLeverage, None)
    assertEquals(p.minPoolMargin, Rational(3, 200))
    assertEquals(
      (p.leiosAnnouncementPeriodLength, p.leiosVotePeriodLength, p.leiosDiffusionPeriodLength),
      (BigInt(1000), BigInt(4000), BigInt(7000))
    )
    assertEquals(p.leiosCommitteeSize, BigInt(900))
    assertEquals(p.leiosQuorumStakeThreshold, Rational(3, 4))
    assertEquals(p.maxEndorserBlockReferencesSize, BigInt(40000))
    assertEquals(p.maxEndorserBlockTxsSize, BigInt(260000))
    assertEquals(p.maxEndorserBlockExUnits, ExUnits(BigInt(720000000), BigInt(200000000000L)))
    assertEquals(p.maxRefScriptSizePerEndorserBlock, BigInt(1048576))
    assertEquals(p.perasHealingFactor, Rational.one)
    assertEquals(p.perasBootstrapRound, None)
    assertEquals(p.perasQuorumThresholdSafetyMargin, Rational.zero)
    assertEquals(p.refInputsCostPerDatumByte, BigInt(0))
    // the bootstrap round's `SNothing` is `[]`, the leverage's `null`
    assertEquals(items(at(gov, 3))(50).value, Value.Arr(Vector.empty))
    assertEquals(items(at(gov, 3))(35).value, Value.Null)
  }

  test("each record at the reference's types: DijkstraPParams reads it and writes it back") {
    terms.foreach { (name, n) =>
      val p = read(codec, n)
      val pp = DijkstraPParams.fromStored(p).fold(e => fail(s"$name: $e"), identity)
      assertEquals(DijkstraPParams.toStored(pp, p), p, name)
    }
  }

  test("slot 203's enact state and governance state, read at Dijkstra's parameters") {
    val enacted = LeiosStateCodecs.enactStateWith(codec)
    assertEquals(enacted.write(read(enacted, enact)), Right(enact.original))
    val govState = LeiosStateCodecs.govState[Dijkstra]
    assertEquals(govState.write(read(govState, gov)), Right(gov.original))
    // at Conway's parameters the record is refused at its first parameters
    assertEquals(
      failure(LeiosStateCodecs.conwayGovState, gov.original).kind,
      Kind.BadArity(Arity.Exactly(31), 54)
    )
    // the 11.1 line's Dijkstra parameters are not read yet
    assertEquals(
      failure(HaskellStateCodecs.govState[Dijkstra], gov.original).kind,
      Kind.Domain(DijkstraNotYet.LedgerState)
    )
  }

  test("the 11.1 line's Dijkstra parameters are refused; the Leios build's are completed") {
    val eleven = Params.codecIn(StateLayout.Node11_1, ShelleyBasedEra.SDijkstra)
    assertEquals(failure(eleven, at(gov, 3).original).kind, Kind.Domain(DijkstraNotYet.LedgerState))
    assertEquals(
      Params.completeIn(StateLayout.Node11_1, ShelleyBasedEra.SDijkstra, current, Params.empty),
      Left(DijkstraNotYet.LedgerState)
    )
    // every field Dijkstra has is the one read; the others are genesis's
    val genesis = Params.empty.copy(minUTxOValue = 7, d = Rational.one)
    assertEquals(
      Params.completeIn(StateLayout.Leios, ShelleyBasedEra.SDijkstra, current, genesis),
      Right(current.copy(minUTxOValue = 7, d = Rational.one))
    )
  }

  test("malformed records are refused with their causes") {
    val all = items(at(gov, 3))
    val short = StateGen.item { w =>
      w.arrayHead(53); all.take(53).foreach(i => w.raw(i.original))
    }
    assertEquals(failure(codec, short).kind, Kind.BadArity(Arity.Exactly(54), 53))
    val conway = Params.conwayCodec.encodeUnchecked(current)
    assertEquals(failure(codec, conway).kind, Kind.BadArity(Arity.Exactly(54), 31))

    def refused(index: Int)(put: Writer => Unit): DecodeError = failure(codec, withItem(index)(put))
    val stride = refused(33)(_.uint(0L))
    assertEquals(stride.kind, Kind.OutOfRange("1..4294967295", 0))
    assertEquals(stride.path, List("refScriptCostStride"))
    assertEquals(refused(34)(ratio(_, 0, 1)).kind, Kind.OutOfRange("a positive ratio", 0))
    // below `positiveIntervalEpsilon`, `1 % 10^19`, which is read
    val tenToNineteen = BigInt(10).pow(19)
    assertEquals(
      refused(47)(ratio(_, 1, tenToNineteen + 1)).kind,
      Kind.OutOfRange("a positive ratio", 1)
    )
    val epsilon = read(codec, tree(withItem(47)(ratio(_, 1, tenToNineteen))))
    assertEquals(epsilon.perasHealingFactor, Rational(1, tenToNineteen))
    // the leverage is `null` or the ratio, never a list
    assert(refused(35)(_.arrayHead(0)).kind.isInstanceOf[Kind.TypeMismatch])
    // the bootstrap round is `[]` or `[round]`, never `null`
    assert(refused(50)(_.nul()).kind.isInstanceOf[Kind.TypeMismatch])
    assertEquals(
      refused(50)(w => { w.arrayHead(2); w.uint(1L); w.uint(2L) }).kind,
      Kind.BadArity(Arity.OneOf(0, 1), 2)
    )
    assertEquals(
      refused(40)(_.uint(65536L)).kind,
      Kind.OutOfRange("0..65535", 65536)
    )
  }

  test("a set leverage and bootstrap round are written as the reference writes them") {
    val leverage = withItem(35)(ratio(_, 5, 1))
    val round = withItem(50)(w => { w.arrayHead(1); w.uint(7L) })
    val p = read(codec, tree(leverage))
    assertEquals(p.maxPledgeLeverage, Some(Rational(5)))
    assertEquals(codec.write(p), Right(leverage))
    val q = read(codec, tree(round))
    assertEquals(q.perasBootstrapRound, Some(BigInt(7)))
    assertEquals(codec.write(q), Right(round))
  }

  test("a record holding a value outside a field's type is not written") {
    // the zero stride of a record with no Dijkstra fields, the first such field
    assertEquals(codec.check(Params.empty).map(_.path), Some(List("refScriptCostStride")))
    val stride = current.copy(refScriptCostStride = 1)
    assertEquals(
      codec.check(stride.copy(perasHealingFactor = Rational.zero)).map(_.path),
      Some(List("perasHealingFactor"))
    )
    assertEquals(
      codec.check(stride.copy(maxPledgeLeverage = Some(Rational(-1)))).map(_.path),
      Some(List("maxPledgeLeverage"))
    )
  }

  // ---- the laws on generated parameters

  private def word(bits: Int): Gen[BigInt] =
    Gen.oneOf(BigInt(0), BigInt(1), (BigInt(1) << bits) - 1, BigInt(2) << (bits - 4))

  private val positive: Gen[Rational] = Gen.oneOf(
    Rational(1, BigInt(10).pow(19)),
    Rational(1, 3),
    Rational.one,
    Rational(6, 5),
    Rational((BigInt(1) << 64) - 1)
  )

  private val units: Gen[ExUnits] =
    Gen.zip(word(63), word(63)).map((m, s) => ExUnits(m, s))

  private val dijkstraParams: Gen[ProtocolParams] =
    for
      p <- StateGen.paramsOf[Conway]
      (block, tx, stride, multiplier, leverage, margin) <- Gen.zip(
        word(32),
        word(32),
        word(32).map(_.max(1)),
        positive,
        Gen.option(StateGen.nonNegative),
        StateGen.unit
      )
      (announce, vote, diffuse, committee, quorum, refs, txs) <- Gen.zip(
        word(32),
        word(32),
        word(32),
        word(16),
        StateGen.unit,
        word(32),
        word(32)
      )
      (ebUnits, ebScripts, age, healing, boost, target, round) <- Gen.zip(
        units,
        word(32),
        word(32),
        positive,
        word(16),
        word(16),
        Gen.option(word(32))
      )
      (margin2, policy, datum) <- Gen.zip(StateGen.unit, word(64), word(64))
    yield p.copy(
      maxRefScriptSizePerBlock = block,
      maxRefScriptSizePerTx = tx,
      refScriptCostStride = stride,
      refScriptCostMultiplier = multiplier,
      maxPledgeLeverage = leverage,
      minPoolMargin = margin,
      leiosAnnouncementPeriodLength = announce,
      leiosVotePeriodLength = vote,
      leiosDiffusionPeriodLength = diffuse,
      leiosCommitteeSize = committee,
      leiosQuorumStakeThreshold = quorum,
      maxEndorserBlockReferencesSize = refs,
      maxEndorserBlockTxsSize = txs,
      maxEndorserBlockExUnits = ebUnits,
      maxRefScriptSizePerEndorserBlock = ebScripts,
      perasMinCandidateBlockAge = age,
      perasHealingFactor = healing,
      perasCertBoost = boost,
      perasTargetCommitteeSize = target,
      perasBootstrapRound = round,
      perasQuorumThresholdSafetyMargin = margin2,
      refInputsCostPerMultiAssetPolicy = policy,
      refInputsCostPerDatumByte = datum
    )

  property("Dijkstra's parameters (Leios): decode after encode is the identity, and encode too") {
    forAll(dijkstraParams) { p =>
      assertEquals(codec.check(p), None)
      val bytes = codec.encodeUnchecked(p)
      val back = codec.decodeBytes(bytes, HaskellState.limits).fold(e => fail(e.render), identity)
      assertEquals(back, p)
      assertEquals(codec.encodeUnchecked(back), bytes)
    }
  }
