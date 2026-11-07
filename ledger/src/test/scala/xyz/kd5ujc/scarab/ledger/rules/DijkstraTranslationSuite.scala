// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  DecoderVersion,
  NativeScript,
  PoolParams,
  PreprodDeepTx,
  ProtocolParams,
  RewardAccount,
  ScriptKind,
  TxIn,
  TxOut
}
import xyz.kd5ujc.scarab.ledger.GenesisState.InjectionSource
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraFixture, DijkstraScript}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era}

/** The translation of a Conway new-epoch state into Dijkstra (`TranslateEra DijkstraEra`,
  * cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs`):
  * [[Translate.conwayToDijkstra]]. The outputs are compared with the reference's `upgradeTxOut` on
  * the cases of `fixtures/reference/dijkstra-translation`; the other parts with what the
  * reference's translation does to each field.
  */
class DijkstraTranslationSuite extends FunSuite:
  import RewardPulserFixture.*

  private val genesis = DijkstraFixture.genesis
  private val withGenesis =
    globals.copy(ledger = globals.ledger.copy(dijkstra = Some(genesis)))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val rows: Vector[Json] =
    Files
      .readAllLines(Path.of("fixtures/reference/dijkstra-translation/cases.jsonl"))
      .toArray(Array.empty[String])
      .toVector
      .filter(_.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  private def string(j: Json, path: String*): String =
    path
      .foldLeft(j.hcursor: io.circe.ACursor)(_.downField(_))
      .as[String]
      .fold(e => fail(s"${path.mkString(".")}: ${e.message}"), identity)

  private def conwayOutput(raw: Bytes): TxOut =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))
    CborRead
      .decode(raw)
      .flatMap(n =>
        TxOut.decode(
          n,
          Address.Mode.of(Era.Conway),
          TxOut.Forms.of(Era.Conway),
          ScriptKind.newest(Era.Conway)
        )
      )
      .fold(e => fail(e.toString), identity)

  test("the fixture holds every case the reference accepted") {
    assertEquals(rows.size, 19)
    rows.foreach(r =>
      assert(r.hcursor.downField("reference").get[Boolean]("accepted").contains(true), r.noSpaces)
    )
  }

  for row <- rows do
    val name = string(row, "name")
    test(s"$name: upgradeTxOut as the reference") {
      val out = conwayOutput(hex(string(row, "txOut")))
      val reference = row.hcursor.downField("reference")
      val upgraded = Translate.upgradeTxOut(out)
      reference.downField("conwayScript").focus.filterNot(_.isNull) match
        case None =>
          assertEquals(upgraded, out)
        case Some(conway) =>
          // the Conway output reads as the reference reads it
          assertEquals(out.scriptRef.map(_.hash), Some(hex(string(conway, "hash"))))
          val dijkstra = reference.downField("dijkstraScript").focus.getOrElse(fail("no script"))
          val bytes = hex(string(dijkstra, "bytes"))
          assertEquals(upgraded.scriptRef.map(_.hash), Some(hex(string(dijkstra, "hash"))))
          out.scriptRef.flatMap(_.native) match
            case Some(native) =>
              assertEquals(DijkstraScript.upgradeTimelock(native), bytes)
              assertEquals(upgraded.raw, hex(string(row, "reference", "dijkstraTxOut")))
              assertEquals(upgraded.referenceScriptSize, bytes.size)
              assertEquals(upgraded.scriptRef.flatMap(_.native), Some(native))
              assertEquals(upgraded.address, out.address)
              assertEquals(upgraded.value, out.value)
              assertEquals(upgraded.datum, out.datum)
            case None => assertEquals(upgraded, out)
          // `upgradeTxOut` of an upgraded output changes nothing more
          assertEquals(Translate.upgradeTxOut(upgraded), upgraded)
    }

  test("a native script nested 100,000 deep is encoded anew on a 256 KiB stack") {
    val depth = 100000
    val sig = NativeScript.Sig(Bytes(Vector.fill(28)(1.toByte)))
    val deep = (1 to depth).foldLeft[NativeScript](sig)((s, _) => NativeScript.AllOf(Vector(s)))
    val encoded = PreprodDeepTx.onSmallStack(DijkstraScript.upgradeTimelock(deep))
    // each level `82 01 81`, then the signature `82 00 58 1c` and its 28 bytes
    assertEquals(encoded.size, 3 * depth + 4 + 28)
  }

  // ---- the pools

  private def vrf(n: Int): VRFVerKeyHash = ok(VRFVerKeyHash.fromBytes(bytes(n, 32)))

  private def reRegistration(p: Int, vrfOf: Int): PoolParams =
    PoolParams(
      pool(p).bytes,
      bytes(vrfOf, 32),
      pledge = 0,
      cost = 340000000,
      margin = Rational(1, 100),
      rewardAccount = RewardAccount(0, node.Credential.Key(bytes(p, 28))),
      owners = Set.empty,
      relays = Bytes.empty,
      metadata = None
    )

  private def count(n: Long): NonZero[Word64] =
    NonZero.from(Word64.fromUnsigned(n)).fold(e => fail(e.toString), identity)

  test(
    "populateVRFKeyHashes counts the pools afresh, a waiting re-registration only for a new key"
  ) {
    // pools 0, 1 and 2 use the VRF keys 0, 1 and 2; pool 0 re-registers with its own key, pool 1
    // with key 2, and pool 9, not registered, with key 9; the map held a stale entry
    val stale = PState(
      Sorted.map(Vector(vrf(7) -> count(3))),
      conway(5, 6, 3).es.ls.cert.pState.stakePools,
      Sorted.map(
        Vector(
          pool(0) -> reRegistration(0, 0),
          pool(1) -> reRegistration(1, 2),
          pool(9) -> reRegistration(9, 9)
        )
      ),
      Sorted.empty
    )
    val counted = Translate.populateVRFKeyHashes(stale).fold(e => fail(e.toString), identity)
    assertEquals(
      counted.vrfKeyHashes,
      Sorted.map(
        Vector(vrf(0) -> count(1), vrf(1) -> count(1), vrf(2) -> count(2), vrf(9) -> count(1))
      )
    )
    assertEquals(counted.copy(vrfKeyHashes = stale.vrfKeyHashes), stale)
    // Conway's of f649f975 counts pool 0's re-registration too and keeps what the map held
    assertEquals(HardFork.populateVRFKeyHashes(stale).vrfKeyHashes.get(vrf(0)), Some(count(2)))
  }

  // ---- the state

  private val nativeOut: TxOut =
    conwayOutput(
      hex(
        rows
          .find(r => string(r, "name") == "refScript/allOf/indefinite-short")
          .map(string(_, "txOut"))
          .getOrElse(fail("no case"))
      )
    )

  private val plainOut: TxOut =
    TxOut(
      Address.decode(hex("61" + "00" * 28)).fold(e => fail(e.toString), identity),
      node.Value(5000000, Map.empty)
    )

  /** A Conway state with parameters of every kind, a pool waiting to re-register, a definite update
    * for the next epoch and two outputs, one with a native reference script.
    */
  private def before: NewEpochState[Conway] =
    val c = conway(5, 10, 2)
    val gov = c.es.ls.utxo.gov
    val next = gov.curPParams.copy(minFeeA = 99)
    val enact = gov.drepPulsing.ratify.enact
    val dirty = gov.copy(
      prevPParams = gov.curPParams.copy(minFeeA = 7),
      futurePParams = FuturePParams.DefinitePParamsUpdate(next),
      drepPulsing = gov.drepPulsing.copy(ratify =
        gov.drepPulsing.ratify.copy(enact = enact.copy(params = next, prevParams = gov.curPParams))
      )
    )
    val utxo = node.Utxo.fromMap(
      Map(TxIn(bytes(1, 32), 0) -> nativeOut, TxIn(bytes(2, 32), 1) -> plainOut)
    )
    val pState =
      c.es.ls.cert.pState.copy(future = Sorted.map(Vector(pool(0) -> reRegistration(0, 0))))
    c.copy(es =
      c.es.copy(ls =
        c.es.ls.copy[Conway](
          cert = c.es.ls.cert.copy(pState = pState),
          utxo = c.es.ls.utxo.copy[Conway](utxo = UtxoTables(utxo), gov = dirty)
        )
      )
    )

  private def translated: NewEpochState[Dijkstra] =
    Translate.conwayToDijkstra(withGenesis, before).fold(e => fail(e.toString), identity)

  /** The fields Dijkstra adds are the genesis values and Conway's are kept, but the PlutusV4 model.
    */
  private def upgradedFrom(conway: ProtocolParams, dijkstra: ProtocolParams): Unit =
    assertEquals(dijkstra, genesis.upgrade(conway))
    assertEquals(dijkstra.costModels(3), genesis.plutusV4CostModel.map(BigInt(_)))
    assertEquals(dijkstra.leiosCommitteeSize, genesis.leiosCommitteeSize)
    assertEquals(dijkstra.minFeeA, conway.minFeeA)

  test("every set of parameters is upgraded by the Dijkstra genesis") {
    val (c, d) = (before.es.ls.utxo.gov, translated.es.ls.utxo.gov)
    upgradedFrom(c.curPParams, d.curPParams)
    upgradedFrom(c.prevPParams, d.prevPParams)
    (c.futurePParams, d.futurePParams) match
      case (FuturePParams.DefinitePParamsUpdate(a), FuturePParams.DefinitePParamsUpdate(b)) =>
        upgradedFrom(a, b)
      case other => fail(s"the future parameters: $other")
    upgradedFrom(c.drepPulsing.ratify.enact.params, d.drepPulsing.ratify.enact.params)
    upgradedFrom(c.drepPulsing.ratify.enact.prevParams, d.drepPulsing.ratify.enact.prevParams)
    assertEquals(
      FuturePParams.map(FuturePParams.PotentialPParamsUpdate(Some(c.curPParams)))(genesis.upgrade),
      FuturePParams.PotentialPParamsUpdate(Some(genesis.upgrade(c.curPParams)))
    )
  }

  test("the governance, the accounts, the votes, the snapshots and the pots carry over") {
    val (b, a) = (before, translated)
    val (c, d) = (b.es.ls.utxo.gov, a.es.ls.utxo.gov)
    assertEquals(d.proposals, c.proposals)
    assertEquals(d.committee, c.committee)
    assertEquals(d.constitution, c.constitution)
    assertEquals(d.drepPulsing.snapshot, c.drepPulsing.snapshot)
    assertEquals(d.drepPulsing.ratify.enacted, c.drepPulsing.ratify.enacted)
    assertEquals(d.drepPulsing.ratify.enact.committee, c.drepPulsing.ratify.enact.committee)
    assertEquals(d.drepPulsing.ratify.enact.prevIds, c.drepPulsing.ratify.enact.prevIds)
    assertEquals(a.es.ls.cert.dState, b.es.ls.cert.dState)
    assertEquals(a.es.ls.cert.vState, b.es.ls.cert.vState)
    assertEquals(
      a.es.ls.cert.pState.copy(vrfKeyHashes = b.es.ls.cert.pState.vrfKeyHashes),
      b.es.ls.cert.pState
    )
    assertEquals(a.es.snapshots, b.es.snapshots)
    assertEquals(a.es.chainAccount, b.es.chainAccount)
    assertEquals(a.es.nonMyopic, b.es.nonMyopic)
    assertEquals(a.es.ls.utxo.instantStake, b.es.ls.utxo.instantStake)
    assertEquals(
      (a.es.ls.utxo.deposited, a.es.ls.utxo.fees, a.es.ls.utxo.donation),
      (b.es.ls.utxo.deposited, b.es.ls.utxo.fees, b.es.ls.utxo.donation)
    )
    assertEquals((a.epoch, a.bprev, a.bcur, a.ru, a.pd), (b.epoch, b.bprev, b.bcur, b.ru, b.pd))
    // pool 0 re-registers with its own key, which is counted once
    assertEquals(
      a.es.ls.cert.pState.vrfKeyHashes,
      Sorted.map(Vector(vrf(0) -> count(1), vrf(1) -> count(1)))
    )
  }

  test("the UTxO's native reference scripts are encoded anew, the totals unchanged") {
    val (b, a) = (before.es.ls.utxo.utxo.utxo, translated.es.ls.utxo.utxo.utxo)
    assertEquals(a.get(TxIn(bytes(1, 32), 0)), Some(Translate.upgradeTxOut(nativeOut)))
    assertNotEquals(a.get(TxIn(bytes(1, 32), 0)), Some(nativeOut))
    assertEquals(a.get(TxIn(bytes(2, 32), 1)), Some(plainOut))
    assertEquals((a.coin, a.count, a.stake, a.ptrStake), (b.coin, b.count, b.stake, b.ptrStake))
  }

  test("the next era of Conway is Dijkstra; with no Dijkstra genesis the translation is refused") {
    assertEquals(
      Translate
        .next(withGenesis, EraNes.InEra(ShelleyBasedEra.SConway, before))
        .map(_.map(_.toEra)),
      Right(Some(Era.Dijkstra))
    )
    assertEquals(
      Translate.next(globals, EraNes.InEra(ShelleyBasedEra.SConway, before)),
      Left(TickFailure.NoDijkstraGenesis)
    )
    assertEquals(
      Translate.next(withGenesis, EraNes.InEra(ShelleyBasedEra.SDijkstra, translated)),
      Right(None)
    )
  }

  private val none = GenesisInjections(
    false,
    InjectionSource.legacy(Vector.empty),
    InjectionSource.legacy(Vector.empty),
    InjectionSource.legacy(Vector.empty),
    InjectionSource.legacy(Vector.empty),
    InjectionSource.legacy(Vector.empty),
    Map.empty
  )

  test("a chain still cannot start in Dijkstra: its injection is refused") {
    assertEquals(
      GenesisStart.inject(none, translated)(using ShelleyBasedEra.SDijkstra),
      Left(GenesisStartError.NotYet(DijkstraNotYet.Genesis))
    )
  }
