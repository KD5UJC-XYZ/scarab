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

  /** A tagged ratio of two integers of any sign. */
  private def signedRatio(w: Writer, num: BigInt, den: BigInt): Unit =
    w.tag(30); w.arrayHead(2); w.int(num); w.int(den)

  private val unsignedOf = (actual: String) => Kind.TypeMismatch("unsigned integer", actual)
  private val word32Max = (BigInt(1) << 32) - 1
  private val word16Max = (BigInt(1) << 16) - 1
  private val word32Too = (w: Writer) => w.uint(word32Max + 1)

  test("every field Dijkstra adds is read from its own position, as its own value") {
    val all = items(at(gov, 3))
    val record = StateGen.item { w =>
      w.arrayHead(54)
      all.take(31).foreach(i => w.raw(i.original))
      w.uint(1001L) // 31 maxRefScriptSizePerBlock
      w.uint(1002L) // 32 maxRefScriptSizePerTx
      w.uint(1003L) // 33 refScriptCostStride
      ratio(w, 1004, 1) // 34 refScriptCostMultiplier
      ratio(w, 1005, 1) // 35 maxPledgeLeverage
      ratio(w, 1, 1006) // 36 minPoolMargin
      w.uint(1007L) // 37 leiosAnnouncementPeriodLength
      w.uint(1008L) // 38 leiosVotePeriodLength
      w.uint(1009L) // 39 leiosDiffusionPeriodLength
      w.uint(1010L) // 40 leiosCommitteeSize
      ratio(w, 1, 1011) // 41 leiosQuorumStakeThreshold
      w.uint(1012L) // 42 maxEndorserBlockReferencesSize
      w.uint(1013L) // 43 maxEndorserBlockTxsSize
      w.arrayHead(2); w.uint(1014L); w.uint(1015L) // 44 maxEndorserBlockExecutionUnits
      w.uint(1016L) // 45 maxRefScriptSizePerEndorserBlock
      w.uint(1017L) // 46 perasMinCandidateBlockAge
      ratio(w, 1018, 1) // 47 perasHealingFactor
      w.uint(1019L) // 48 perasCertBoost
      w.uint(1020L) // 49 perasTargetCommitteeSize
      w.arrayHead(1); w.uint(1021L) // 50 perasBootstrapRound
      ratio(w, 1, 1022) // 51 perasQuorumThresholdSafetyMargin
      w.uint(1023L) // 52 refInputsCostPerMultiAssetPolicy
      w.uint(1024L) // 53 refInputsCostPerDatumByte
    }
    val p = read(codec, tree(record))
    assertEquals(p.maxRefScriptSizePerBlock, BigInt(1001))
    assertEquals(p.maxRefScriptSizePerTx, BigInt(1002))
    assertEquals(p.refScriptCostStride, BigInt(1003))
    assertEquals(p.refScriptCostMultiplier, Rational(1004))
    assertEquals(p.maxPledgeLeverage, Some(Rational(1005)))
    assertEquals(p.minPoolMargin, Rational(1, 1006))
    assertEquals(p.leiosAnnouncementPeriodLength, BigInt(1007))
    assertEquals(p.leiosVotePeriodLength, BigInt(1008))
    assertEquals(p.leiosDiffusionPeriodLength, BigInt(1009))
    assertEquals(p.leiosCommitteeSize, BigInt(1010))
    assertEquals(p.leiosQuorumStakeThreshold, Rational(1, 1011))
    assertEquals(p.maxEndorserBlockReferencesSize, BigInt(1012))
    assertEquals(p.maxEndorserBlockTxsSize, BigInt(1013))
    assertEquals(p.maxEndorserBlockExUnits, ExUnits(BigInt(1014), BigInt(1015)))
    assertEquals(p.maxRefScriptSizePerEndorserBlock, BigInt(1016))
    assertEquals(p.perasMinCandidateBlockAge, BigInt(1017))
    assertEquals(p.perasHealingFactor, Rational(1018))
    assertEquals(p.perasCertBoost, BigInt(1019))
    assertEquals(p.perasTargetCommitteeSize, BigInt(1020))
    assertEquals(p.perasBootstrapRound, Some(BigInt(1021)))
    assertEquals(p.perasQuorumThresholdSafetyMargin, Rational(1, 1022))
    assertEquals(p.refInputsCostPerMultiAssetPolicy, BigInt(1023))
    assertEquals(p.refInputsCostPerDatumByte, BigInt(1024))
    // Conway's fields are the slot's, and the record is written as it was read
    assertEquals(p.minFeeA, BigInt(44))
    assertEquals(codec.write(p), Right(record))
  }

  test("each field Dijkstra adds refuses a value outside its type, under its own name") {
    val epsilonBelow = (w: Writer) => ratio(w, 1, BigInt(10).pow(19) + 1)
    val aboveOne = (w: Writer) => ratio(w, 2, 1)
    val bignum = (w: Writer) =>
      w.tag(2); w.bytes(Bytes.fromHex("010000000000000000").fold(e => fail(e.toString), identity))
    val cases: Vector[(Int, String, Writer => Unit, Kind)] = Vector(
      (31, "maxRefScriptSizePerBlock", word32Too, Kind.OutOfRange(s"0..$word32Max", word32Max + 1)),
      (32, "maxRefScriptSizePerTx", word32Too, Kind.OutOfRange(s"0..$word32Max", word32Max + 1)),
      (33, "refScriptCostStride", word32Too, Kind.OutOfRange(s"1..$word32Max", word32Max + 1)),
      (34, "refScriptCostMultiplier", epsilonBelow, Kind.OutOfRange("a positive ratio", 1)),
      (35, "maxPledgeLeverage", signedRatio(_, -1, 1), unsignedOf("negative integer")),
      (36, "minPoolMargin", aboveOne, Kind.OutOfRange("a ratio in 0..1", 2)),
      (
        37,
        "leiosAnnouncementPeriodLength",
        word32Too,
        Kind.OutOfRange(s"0..$word32Max", word32Max + 1)
      ),
      (38, "leiosVotePeriodLength", word32Too, Kind.OutOfRange(s"0..$word32Max", word32Max + 1)),
      (
        39,
        "leiosDiffusionPeriodLength",
        word32Too,
        Kind.OutOfRange(s"0..$word32Max", word32Max + 1)
      ),
      (
        40,
        "leiosCommitteeSize",
        _.uint(word16Max + 1),
        Kind.OutOfRange(s"0..$word16Max", word16Max + 1)
      ),
      (41, "leiosQuorumStakeThreshold", aboveOne, Kind.OutOfRange("a ratio in 0..1", 2)),
      (
        42,
        "maxEndorserBlockReferencesSize",
        word32Too,
        Kind.OutOfRange(s"0..$word32Max", word32Max + 1)
      ),
      (43, "maxEndorserBlockTxsSize", word32Too, Kind.OutOfRange(s"0..$word32Max", word32Max + 1)),
      (
        44,
        "maxEndorserBlockExecutionUnits",
        w => { w.arrayHead(2); w.uint(BigInt(Long.MaxValue) + 1); w.uint(0L) },
        Kind.OutOfRange(s"0..${Long.MaxValue}", BigInt(Long.MaxValue) + 1)
      ),
      (
        45,
        "maxRefScriptSizePerEndorserBlock",
        word32Too,
        Kind.OutOfRange(s"0..$word32Max", word32Max + 1)
      ),
      (
        46,
        "perasMinCandidateBlockAge",
        word32Too,
        Kind.OutOfRange(s"0..$word32Max", word32Max + 1)
      ),
      (47, "perasHealingFactor", epsilonBelow, Kind.OutOfRange("a positive ratio", 1)),
      (
        48,
        "perasCertBoost",
        _.uint(word16Max + 1),
        Kind.OutOfRange(s"0..$word16Max", word16Max + 1)
      ),
      (
        49,
        "perasTargetCommitteeSize",
        _.uint(word16Max + 1),
        Kind.OutOfRange(s"0..$word16Max", word16Max + 1)
      ),
      (
        50,
        "perasBootstrapRound",
        w => { w.arrayHead(1); word32Too(w) },
        Kind.OutOfRange(s"0..$word32Max", word32Max + 1)
      ),
      (51, "perasQuorumThresholdSafetyMargin", aboveOne, Kind.OutOfRange("a ratio in 0..1", 2)),
      (52, "refInputsCostPerMultiAssetPolicy", bignum, unsignedOf("tag 2")),
      (53, "refInputsCostPerDatumByte", bignum, unsignedOf("tag 2"))
    )
    assertEquals(cases.map(_._1), (31 to 53).toVector)
    cases.foreach { (index, name, put, kind) =>
      val e = failure(codec, withItem(index)(put))
      assertEquals(e.kind, kind, name)
      assertEquals(e.path.headOption, Some(name), name)
    }
  }

  test("a bounded ratio with a negative number: refused from version 12, read before it") {
    val all = items(at(gov, 3))
    val minusOne = (w: Writer) => signedRatio(w, -1, -1)
    // every bounded ratio of Dijkstra's parameters, Conway's among them, the first of a record's
    val ratios: Vector[(Int, String, Writer => Unit)] = Vector(
      (9, "a0", minusOne),
      (10, "rho", minusOne),
      (11, "tau", minusOne),
      (16, "prices", w => { w.arrayHead(2); minusOne(w); w.raw(items(all(16))(1).original) }),
      (
        22,
        "poolVotingThresholds",
        w => { w.arrayHead(5); minusOne(w); items(all(22)).drop(1).foreach(i => w.raw(i.original)) }
      ),
      (
        23,
        "drepVotingThresholds",
        w => {
          w.arrayHead(10); minusOne(w); items(all(23)).drop(1).foreach(i => w.raw(i.original))
        }
      ),
      (30, "minFeeRefScriptCostPerByte", minusOne),
      (34, "refScriptCostMultiplier", minusOne),
      (35, "maxPledgeLeverage", minusOne),
      (36, "minPoolMargin", minusOne),
      (41, "leiosQuorumStakeThreshold", minusOne),
      (47, "perasHealingFactor", minusOne),
      (51, "perasQuorumThresholdSafetyMargin", minusOne)
    )
    ratios.foreach { (index, name, put) =>
      val e = failure(codec, withItem(index)(put))
      assertEquals(e.kind, unsignedOf("negative integer"), name)
      assertEquals(e.path.headOption, Some(name), name)
    }
    // Conway's state is read at version 9, where the numbers are `Integer`s: `[-1, -1]` is 1
    val conway = StateGen.item { w =>
      w.arrayHead(31)
      all
        .take(31)
        .zipWithIndex
        .foreach((i, n) => if n == 10 then minusOne(w) else w.raw(i.original))
    }
    assertEquals(read(Params.conwayCodec, tree(conway)).rho, Rational.one)
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
