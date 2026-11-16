// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.protocol.NtcVersion

/** What the negotiated node-to-client version does to a query, as cardano-node 11.1.3 has it: it
  * selects the encoding of some answers, and refuses `DebugLedgerConfig` below version 20
  * (`QueryVersion3`, `Ledger/Query.hs:483-501`). Its server decodes a block query alike at every
  * version (`decodeNodeToClient _ _ = decodeShelleyQuery`, `Shelley/Node/Serialisation.hs:372`) and
  * answers what it decodes (`MiniProtocol/LocalStateQuery/Server.hs:79-81`); the supported-version
  * table (`blockQueryIsSupportedOnVersion`, `Shelley/Ledger/Query.hs:541-593`) binds its client
  * encoder only (`Serialisation.hs:368`). The version selects the encoding of `GetGenesisConfig`
  * and of the old ledger-peer snapshot (`encodeShelleyResult`, `Query.hs:1000-1043`), and `GetCBOR`
  * writes at the latest (`Query.hs:424-428`), ouroboros-consensus 82ecba32. Live byte comparisons
  * at every version against the pinned cardano-node are part of the `ntc` scenario
  * (`ntcversions.py`).
  */
class QueryVersionSuite extends FunSuite:
  import LedgerQuery.*
  import NtcVersion.*

  private def query(q: LedgerQuery): NtcQuery = NtcQuery.IfCurrent(QueryEra.Conway, q)

  private def inEra(era: QueryEra)(ledger: CborOut => Unit): Bytes =
    CborOut.build { w =>
      w.array(2).uint(0).array(2).uint(0).array(2).uint(era.index)
      ledger(w)
    }

  /** The block queries of Conway that the reference's client table allows at some versions only. */
  private val limited: Vector[CborOut => Unit] = Vector(
    _.array(1).uint(4),
    _.array(1).uint(5),
    _.array(2).uint(21).array(0),
    _.array(2).uint(31).set(0),
    _.array(1).uint(32),
    _.array(1).uint(33),
    _.array(1).uint(34),
    _.array(2).uint(34).uint(0),
    _.array(2).uint(34).uint(1),
    _.array(2).uint(35).bytes(Bytes(Vector.fill(28)(0.toByte))),
    _.array(2).uint(36).array(0),
    _.array(1).uint(37),
    _.array(1).uint(38),
    _.array(2).uint(39).set(0)
  )

  test("a block query decodes alike at every negotiated version, also inside GetCBOR") {
    val forms = limited.flatMap(form => Vector(form, (w: CborOut) => form(w.array(2).uint(9))))
    forms.map(inEra(QueryEra.Conway)).foreach { raw =>
      val plain = NtcQuery.decode(raw)
      assert(plain.isRight, s"${raw.hex}: $plain")
      NtcVersion.All.foreach(v => assertEquals(NtcQuery.decode(raw, v), plain, s"${raw.hex} / $v"))
    }
  }

  test(
    "a top-level query is read from its query version: DebugLedgerConfig from V20, the others at every version"
  ) {
    def top(tag: Int): Bytes = CborOut.build(_.array(1).uint(tag))
    val readEverywhere =
      Vector(1 -> NtcQuery.SystemStart, 2 -> NtcQuery.ChainBlockNo, 3 -> NtcQuery.ChainPoint)
    NtcVersion.All.foreach { v =>
      readEverywhere.foreach((tag, q) =>
        assertEquals(NtcQuery.decode(top(tag), v), Right(q), s"$tag / $v")
      )
      val expected =
        if v.number >= 20 then Right(NtcQuery.DebugLedgerConfig)
        else
          Left(
            NtcQuery.Error
              .RequiresQueryVersion(NtcQuery.DebugLedgerConfig, QueryVersion.QueryVersion3)
          )
      assertEquals(NtcQuery.decode(top(4), v), expected, s"4 / $v")
    }
  }

  test(
    "every Conway query is answered at every version as at the latest, the genesis config aside"
  ) {
    val (_, state, point, blockNo) =
      NtcGolden.subjects.find(_._1 == "rich").getOrElse(fail("no rich subject"))
    val view = QueryView(point, blockNo, state)
    val utxo = state.utxo.toMap
    val retired = Vector(
      "GetProposedPParamsUpdates" -> GetProposedPParamsUpdates,
      "GetStakeDistribution" -> GetStakeDistribution,
      "GetPoolDistr" -> GetPoolDistr(None)
    )
    (NtcGolden.battery(NtcGolden.drawn(state)) ++ retired).foreach { (label, q) =>
      // the genesis configuration's own encoding is the version's (the next test); in `GetCBOR` it
      // is the latest at every version too
      val forms = if q == GetGenesisConfig then Vector(GetCBOR(q)) else Vector(q, GetCBOR(q))
      forms.foreach { inner =>
        val latest = QueryAnswers.answerOrWhy(NtcGolden.queryEnv, view, utxo, query(inner), V23)
        assert(latest.isRight, s"$label / $inner at V23: $latest")
        NtcVersion.All.foreach { v =>
          assertEquals(
            QueryAnswers.answerOrWhy(NtcGolden.queryEnv, view, utxo, query(inner), v),
            latest,
            s"$label / $inner / $v"
          )
        }
      }
    }
  }

  test("the genesis configuration is the legacy record below V21 and the current one from V21") {
    val (block, state) = NtcGolden.conwayStates.last
    val view = QueryView(
      Point.Block(UInt64.saturating(BigInt(block.header.slot.toLong)), block.header.hash),
      Some(block.header.blockNo),
      state
    )
    def genesis(v: NtcVersion) =
      GenesisConfig
        .encode(NtcGolden.queryEnv.shelleyGenesis, v)
        .fold(e => fail(e.message), identity)
    val (legacy, current) = (genesis(V20), genesis(V21))
    assertNotEquals(legacy, current)
    NtcVersion.All.foreach { v =>
      val expected = if v.number < 21 then legacy else current
      assertEquals(
        QueryAnswers.answerOrWhy(NtcGolden.queryEnv, view, Map.empty, query(GetGenesisConfig), v),
        Right(CborOut.build(_.array(1).raw(expected))),
        s"$v"
      )
    }
  }

  test(
    "a query of an era the ledger is not in has the era mismatch for an answer at every version"
  ) {
    val (block, state) = NtcGolden.conwayStates.last
    val view = QueryView(
      Point.Block(UInt64.saturating(BigInt(block.header.slot.toLong)), block.header.hash),
      Some(block.header.blockNo),
      state
    )
    val mismatch = CborOut.build(
      _.array(2).array(2).uint(5).text("Babbage").array(2).uint(6).text("Conway")
    )
    // the forms that `requireCG` does not refuse before Conway
    Vector[CborOut => Unit](
      _.array(1).uint(4),
      _.array(2).uint(21).array(0),
      _.array(1).uint(34),
      _.array(2).uint(34).uint(0),
      _.array(1).uint(37)
    ).map(inEra(QueryEra.Babbage)).foreach { raw =>
      NtcVersion.All.foreach { v =>
        val q = NtcQuery.decode(raw, v).fold(e => fail(s"${raw.hex} / $v: ${e.message}"), identity)
        assertEquals(
          QueryAnswers.answerOrWhy(NtcGolden.queryEnv, view, Map.empty, q, v),
          Right(mismatch),
          s"${raw.hex} / $v"
        )
      }
    }
  }

  test("Conway protocol parameters are identical at V20 and V21, as LegacyPParams specifies") {
    val (block, state) = NtcGolden.conwayStates.last
    val view = QueryView(
      Point.Block(UInt64.saturating(BigInt(block.header.slot.toLong)), block.header.hash),
      Some(block.header.blockNo),
      state
    )
    val old =
      QueryAnswers.answerOrWhy(NtcGolden.queryEnv, view, Map.empty, query(GetCurrentPParams), V20)
    val current =
      QueryAnswers.answerOrWhy(NtcGolden.queryEnv, view, Map.empty, query(GetCurrentPParams), V21)
    assert(old.isRight)
    assertEquals(old, current)
  }

  test("genesis protocol parameters flatten the protocol version before V21") {
    val old = GenesisConfig
      .encode(NtcGolden.queryEnv.shelleyGenesis, V20)
      .fold(e => fail(e.message), identity)
    val current =
      GenesisConfig
        .encode(NtcGolden.queryEnv.shelleyGenesis, V21)
        .fold(e => fail(e.message), identity)
    def array(n: Node): Vector[Node] = n.value match
      case Value.Arr(xs) => xs
      case _             => fail("not an array")
    def fields(b: Bytes) = array(Cbor.decode(b).fold(e => fail(e.message), identity))
    val before = fields(old)
    val after = fields(current)
    assertEquals(before.size, 15)
    assertEquals(after.size, 15)
    assertEquals(before.take(11).map(_.original), after.take(11).map(_.original))
    assertEquals(before.drop(12).map(_.original), after.drop(12).map(_.original))
    val legacyParams = array(before(11))
    val currentParams = array(after(11))
    assertEquals(legacyParams.size, 18)
    assertEquals(currentParams.size, 17)
    assertEquals(legacyParams.take(14).map(_.original), currentParams.take(14).map(_.original))
    assertEquals(legacyParams.slice(14, 16).map(_.value), array(currentParams(14)).map(_.value))
    assertEquals(legacyParams.drop(16).map(_.original), currentParams.drop(15).map(_.original))
  }

  test("GetCBOR uses the latest inner result encoding even on a V20 connection") {
    val (block, state) = NtcGolden.conwayStates.last
    val view = QueryView(
      Point.Block(UInt64.saturating(BigInt(block.header.slot.toLong)), block.header.hash),
      Some(block.header.blockNo),
      state
    )
    val latest =
      GenesisConfig
        .encode(NtcGolden.queryEnv.shelleyGenesis, V23)
        .fold(e => fail(e.message), identity)
    val expected = CborOut.build(_.array(1).tag(24).bytes(latest))
    assertEquals(
      QueryAnswers
        .answerOrWhy(NtcGolden.queryEnv, view, Map.empty, query(GetCBOR(GetGenesisConfig)), V20),
      Right(expected)
    )
  }

  test("the old pool distribution omits each pool's stake and the total stake") {
    val (block, state) = NtcGolden.conwayStates.last
    val view = QueryView(
      Point.Block(UInt64.saturating(BigInt(block.header.slot.toLong)), block.header.hash),
      Some(block.header.blockNo),
      state
    )
    val old = QueryAnswers
      .answerOrWhy(NtcGolden.queryEnv, view, Map.empty, query(GetPoolDistr(None)), V20)
      .fold(e => fail(e.toString), identity)
    val current = QueryAnswers
      .answerOrWhy(NtcGolden.queryEnv, view, Map.empty, query(GetPoolDistr2(None)), V21)
      .fold(e => fail(e.toString), identity)
    def parsed(b: Bytes) = Cbor.decode(b).fold(e => fail(e.message), identity)
    def array(n: Node): Vector[Node] = n.value match
      case Value.Arr(xs) => xs
      case _             => fail("expected array")
    def pools(n: Node): Vector[(Node, Node)] = n.value match
      case Value.Map(xs) => xs
      case _             => fail("expected pool map")
    val legacyPools = pools(array(parsed(old)).head)
    val currentPools = pools(array(array(parsed(current)).head).head)
    assert(legacyPools.nonEmpty)
    assertEquals(legacyPools.map(_._1.original), currentPools.map(_._1.original))
    legacyPools.zip(currentPools).foreach { case ((_, oldShare), (_, newShare)) =>
      val before = array(oldShare)
      val after = array(newShare)
      assertEquals(before.size, 2)
      assertEquals(after.size, 3)
      assertEquals(before.map(_.original), Vector(after(0).original, after(2).original))
    }
  }
