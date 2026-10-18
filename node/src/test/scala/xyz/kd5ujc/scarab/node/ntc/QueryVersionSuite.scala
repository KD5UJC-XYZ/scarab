// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.protocol.NtcVersion

/** The reference's supported-version table and V20/V21 result boundary. Live byte comparisons
  * against the pinned cardano-node are part of the `ntc` scenario (`ntcversions.py`).
  */
class QueryVersionSuite extends FunSuite:
  import LedgerQuery.*
  import NtcVersion.*

  private def query(q: LedgerQuery): NtcQuery = NtcQuery.IfCurrent(6, q)

  test("all constructor introduction and retirement boundaries follow the reference table") {
    val cases = Vector(
      (GetProposedPParamsUpdates, 16, 19),
      (GetStakeDistribution, 16, 20),
      (GetPoolDistr(None), 16, 20),
      (GetProposals(Set.empty), 17, 23),
      (GetRatifyState, 17, 23),
      (GetFuturePParams, 18, 23),
      (GetLedgerPeerSnapshotLegacy, 19, 23),
      (QueryStakePoolDefaultVote(Bytes(Vector.fill(28)(0.toByte))), 20, 23),
      (GetPoolDistr2(None), 21, 23),
      (GetStakeDistribution2, 21, 23),
      (GetMaxMajorProtocolVersion, 21, 23),
      (GetLedgerPeerSnapshot(0), 23, 23),
      (GetDRepDelegations(Set.empty), 23, 23)
    )
    cases.foreach { (q, first, last) =>
      NtcVersion.All.foreach { v =>
        val expected = v.number >= first && v.number <= last
        assertEquals(QueryVersion.supports(v, query(q)), expected, s"$q / $v")
        assertEquals(QueryVersion.supports(v, query(GetCBOR(q))), expected, s"GetCBOR / $q / $v")
      }
    }
  }

  test("legacy pool and ledger-peer query constructors retain their exact Haskell shapes") {
    def hex(s: String) = Bytes.fromHex(s).fold(e => fail(e.message), identity)
    val pools = hex("820082008206821580") // QueryIfCurrent Conway (GetPoolDistr Nothing)
    assertEquals(NtcQuery.decode(pools, V20), Right(query(GetPoolDistr(None))))
    assertEquals(NtcQuery.decode(pools, V21), Left(NtcQuery.Error.UnsupportedVersion(V21)))
    val legacyPeers = hex("820082008206811822")
    assertEquals(NtcQuery.decode(legacyPeers, V19), Right(query(GetLedgerPeerSnapshotLegacy)))
    assertEquals(NtcQuery.decode(legacyPeers, V18), Left(NtcQuery.Error.UnsupportedVersion(V18)))
    val allPeers = hex("82008200820682182200")
    assertEquals(NtcQuery.decode(allPeers, V23), Right(query(GetLedgerPeerSnapshot(0))))
    assertEquals(NtcQuery.decode(allPeers, V22), Left(NtcQuery.Error.UnsupportedVersion(V22)))
    assert(NtcQuery.decode(hex("82008200820682182202"), V23).isLeft)
  }

  test("the negotiated version gates raw queries before answering") {
    val raw = CborOut.build(_.array(2).uint(0).array(2).uint(0).array(2).uint(6).array(1).uint(37))
    assert(NtcQuery.decode(raw, V20).isLeft)
    assertEquals(NtcQuery.decode(raw, V21), Right(query(GetStakeDistribution2)))
    val (block, state) = NtcGolden.conwayStates.last
    val view = QueryView(
      Point.Block(UInt64.saturating(BigInt(block.header.slot.toLong)), block.header.hash),
      Some(block.header.blockNo),
      state
    )
    assertEquals(
      QueryAnswers
        .answerOrWhy(NtcGolden.queryEnv, view, Map.empty, query(GetStakeDistribution2), V20),
      Left(Unanswered.Unsupported)
    )
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
