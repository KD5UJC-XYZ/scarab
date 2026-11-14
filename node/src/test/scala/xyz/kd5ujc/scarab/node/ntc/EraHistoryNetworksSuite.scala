// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import cats.syntax.all.*
import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{
  AnnTip,
  ChainDepState,
  EraLedgerState,
  GenesisMirror,
  PraosState,
  TipInfo
}
import xyz.kd5ujc.scarab.ledger.{Nonce, TxIn}
import xyz.kd5ujc.scarab.ledger.byron.{ByronOut, ByronVersion, CandidateUpdate}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, GenesisStart, Globals, Handover}
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  EpochNo,
  Hash32,
  ShelleyBasedEra,
  SlotNo,
  Sorted,
  WithOrigin
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import munit.FunSuite

/** The era-history query on a network that starts in Byron, at a tip years after the system start.
  * `cardano-cli query tip` asks for it (to place the tip in time). Two things broke it on preprod:
  * the Byron span of such a chain is open-ended in the configuration until the chain has entered
  * Shelley (an answer written from the configuration alone puts the first Shelley epoch a million
  * epochs out), and a time in picoseconds does not fit 64 bits after about 213 days (the reference
  * writes an `Integer`, a big number beyond 64 bits). Either made the encoder throw, and the
  * connection died without an answer.
  */
class EraHistoryNetworksSuite extends FunSuite:
  private val setup = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(Path.of("fixtures", "preprod", "genesis"))
    .fold(e => fail(e.message), identity)

  private val env = QueryEnv(
    setup.genesis.geometry.systemStart,
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong
  )

  /** The state of a chain in Conway whose Byron era ended at epoch 4, at slot 100,000,000: the
    * genesis Byron UTxO handed over to Shelley at epoch 4 and translated to Conway, with a Praos
    * state, at the origin of telescopes placed on the era history of that Byron span.
    */
  private val view =
    val firstShelley = BigInt(4)
    val neutral = Nonce.Neutral
    val praos = PraosState(
      WithOrigin.Origin,
      Sorted.map(List.empty),
      neutral,
      neutral,
      neutral,
      neutral,
      neutral,
      neutral
    )
    val start = setup.mirrorStart
    val geometry = setup.geometry.resolved(firstShelley)
    val env = setup.mirrorEnv()
    val byronUtxo = start.consensus.byron.ledger.fold(Map.empty[TxIn, ByronOut])(_.utxo)
    val context = setup.handOver.getOrElse(fail("preprod starts in Byron: it has a hand-over"))
    val shelley = Handover
      .toShelley(context, EpochNo.fromUnsigned(firstShelley.toLong), byronUtxo)
      .fold(e => fail(s"the hand-over refuses: $e"), identity)
    val conway = GenesisStart
      .translateTo(
        Globals(setup.globals, geometry),
        Era.Conway,
        EraNes.InEra(ShelleyBasedEra.SShelley, shelley)
      )
      .fold(e => fail(s"no translation to Conway: $e"), identity)
    val state = GenesisMirror
      .of(
        env.copy(ledger = env.ledger.copy(geometry = geometry)),
        conway,
        ChainDepState.Praos(praos),
        start.consensus.copy(
          follow = start.consensus.follow.copy(era = Some(Era.Conway)),
          byron = start.consensus.byron.copy(firstShelleyEpoch = Some(firstShelley))
        )
      )
      .fold(e => fail(s"the state does not mirror: $e"), identity)
    val hash = Bytes(Vector.fill(32)(1.toByte))
    val point = Point.Block(UInt64.saturating(BigInt(100000000)), hash)
    // The tip of the state is the view's point, as for every acquired state of the node.
    val tip = AnnTip(
      SlotNo.fromUnsigned(100000000L),
      BlockNo.fromUnsigned(1L),
      TipInfo.ShelleyBasedTip(
        ShelleyBasedEra.SConway,
        Hash32.fromBytes(hash).fold(e => fail(e.toString), identity)
      )
    )
    val atTip = state.copy(ext =
      state.ext.copy(headerState = state.ext.headerState.copy(tip = WithOrigin.At(tip)))
    )
    QueryView(point, Some(1), atTip)

  private def answer(query: NtcQuery): Bytes =
    QueryAnswers.answer(env, view, Map.empty, query).getOrElse(fail(s"$query is not answered"))

  test("the configuration alone leaves the Byron span open") {
    assert(setup.geometry.byron.derived)
    assertEquals(setup.geometry.firstShelleyEpoch, BigInt(1000000))
  }

  /** The reference's answers for these states, captured from the unchanged ouroboros-consensus
    * (`fixtures/reference/era-history`, #118): `mkInterpreter` of `reconstructSummary` with the
    * production era parameters of the preprod genesis files, encoded with `Serialise`.
    */
  private val reference: Map[String, String] =
    java.nio.file.Files
      .readAllLines(Path.of("fixtures", "reference", "era-history", "cases.jsonl"))
      .toArray(Array.empty[String])
      .iterator
      .filter(_.nonEmpty)
      .map { line =>
        val row = io.circe.parser.parse(line).fold(e => fail(e.toString), identity).hcursor
        (row.get[String]("name"), row.get[String]("interpreter")).tupled
          .fold(e => fail(e.toString), identity)
      }
      .toMap

  /** Byron to epoch 4 (21,600 slots of 20 s); the Shelley-based eras of 432,000 slots of 1 s, all
    * from epoch 4 because no hard fork was observed; Conway ends at the safe zone beyond slot
    * 100,000,000, epoch 236, at 101,952,000,000,000,000,000 ps, which does not fit 64 bits.
    */
  private val interpreter = reference("conway-tip-100000000")

  test("GetInterpreter: Byron ends at epoch 4, and a late time is a tag-2 big number") {
    assertEquals(answer(NtcQuery.HardFork(HardForkQuery.GetInterpreter)).hex, interpreter)
  }

  test("GetInterpreter at the origin of a chain still in Byron: Byron ends at the safe zone") {
    val origin = QueryView(Point.Origin, None, setup.mirrorStart)
    assertEquals(
      QueryAnswers
        .answer(env, origin, Map.empty, NtcQuery.HardFork(HardForkQuery.GetInterpreter))
        .getOrElse(fail("GetInterpreter is not answered at the origin"))
        .hex,
      reference("byron-origin")
    )
  }

  /** In Byron with a candidate update to version 2 at slot 60,000 that is stable (the tip 2k slots
    * later): `byronTransition` reports its adoption epoch, `(60,000 + 4k) / 21,600 + 1 = 4`, so the
    * summary ends Byron there and lists Shelley with its safe zone from its start (#804).
    */
  test(
    "GetInterpreter in Byron with a stable update to Shelley: Byron ends at its adoption epoch"
  ) {
    val start = setup.mirrorStart
    val known = start.ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(byron) =>
        val ledger = byron.ledger.getOrElse(fail("the preprod start has a Byron ledger"))
        val two = ByronVersion.from(2, 0, 0).fold(e => fail(e.message), identity)
        val candidate = CandidateUpdate(60000, two, ledger.update.adoptedParams)
        val updated = ledger.copy(
          lastSlot = 70000,
          update = ledger.update.copy(candidates = Vector(candidate))
        )
        start.copy(ext =
          start.ext.copy(ledgerState =
            start.ext.ledgerState.withState(
              EraLedgerState.ByronCurrent(byron.copy(ledger = Some(updated)))
            )
          )
        )
      case other => fail(s"the preprod start is in Byron: $other")
    val view = QueryView(
      Point.Block(UInt64.saturating(BigInt(70000)), Bytes(Vector.fill(32)(2.toByte))),
      Some(1),
      known
    )
    assertEquals(
      QueryAnswers
        .answer(env, view, Map.empty, NtcQuery.HardFork(HardForkQuery.GetInterpreter))
        .getOrElse(fail("GetInterpreter is not answered in Byron"))
        .hex,
      reference("byron-known-4")
    )
  }
