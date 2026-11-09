// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, MirrorState, PraosState}
import xyz.kd5ujc.scarab.ledger.{Block, LeiosHeader, Nonce}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  Conway,
  NewEpochState,
  FuturePParams,
  ProtVer,
  ShelleyBasedEra,
  SlotNo,
  Sorted,
  Word64,
  WithOrigin
}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word16, Word32}

/** The protocol's crossing from Conway into Dijkstra ([[DijkstraCrossing]]) on the reference
  * cluster's chain of `fixtures/dijkstra-devnet` (the Leios testnet build, cardano-node b004fe0c;
  * provenance in its README), which forks into Dijkstra at epoch 1, slot 200.
  *
  * The Conway blocks 0 to 32 go through the node's rules from genesis. From the state after the
  * last one, the chain-dependent state is translated and the ledger view of each Dijkstra block's
  * slot forecast across the boundary; every Dijkstra header the reference adopted must then pass
  * Praos2's validation, its VRF proof against the epoch nonce the translated state ticks into and
  * its leader check against the forecast stake.
  *
  * The ledger of this chain is at major 12, which this build's maximum of 11 refuses (#54); the
  * suite's own configurations raise it to 12, as the reference's `ExperimentalHardForksEnabled`
  * does. No node path is changed.
  */
class DijkstraCrossingSuite extends munit.FunSuite:
  private val devnet = Path.of("fixtures", "dijkstra-devnet")
  private val setup = NodeSetup
    .fromConfigFile(devnet.resolve("genesis/config.json"))
    .fold(e => fail(e.message), identity)
  private val praos = setup.praos.copy(forks = setup.praos.forks.copy(maxMajorProtVer = 12))
  private val globals = Globals(setup.globals, praos.geometry)
  private val transition = SlotNo.fromUnsigned(200)

  private def rows(file: String): Vector[Array[String]] =
    Files.readAllLines(devnet.resolve(file)).asScala.toVector.filter(_.nonEmpty).map(_.split('\t'))
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val conwayBlocks = rows("blocks.tsv").filter(_(0) == "7").map { r =>
    val raw = hex(r(4))
    (Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity), raw)
  }
  private val dijkstraHeaders = rows("headers.tsv").filter(_(0) == "8").map { r =>
    LeiosHeader.decode(hex(r(4))).fold(e => fail(e.message), identity)
  }

  private lazy val crossing: MirrorState =
    conwayBlocks.foldLeft(setup.mirrorStart) { case (state, (block, raw)) =>
      MirrorSteps
        .block(setup, state, block.header, raw, Some(praos))
        .fold(f => fail(s"block ${block.header.blockNo} refused: ${f.reason}"), identity)
    }
  private lazy val ledger: NewEpochState[Conway] = MirrorSteps.ledger(crossing) match
    case Some(i: InEra.Of[e]) =>
      i.era match
        case ShelleyBasedEra.SConway => i.read.nes.fold(e => fail(e.toString), identity)
        case other                   => fail(s"not in Conway: $other")
    case None => fail("no Shelley-based ledger")
  private lazy val praosState: PraosState = crossing.protocol.dep match
    case ChainDepState.Praos(p) => p
    case other                  => fail(s"not Praos: $other")
  private lazy val last = conwayBlocks.last._1.header
  private lazy val tip = WithOrigin.At(SlotNo.fromUnsigned(last.slot.toLong))

  private def slotOf(h: LeiosHeader) = SlotNo.fromUnsigned(h.slot.toLong)
  private def forecast(
      slot: SlotNo,
      g: Globals = globals,
      tipAt: WithOrigin[SlotNo] = tip,
      state: => NewEpochState[Conway] = ledger
  ) =
    DijkstraCrossing.forecast(g, g, transition, slot, tipAt, state)

  /** The replayed state with a mark snapshot that has lost a pool, and the future parameters `f` of
    * a larger header limit: on the devnet's chain the snapshots are all the genesis one and no
    * update is pending, so these are what tell the rotation and the next parameters apart.
    */
  private def altered(f: xyz.kd5ujc.scarab.ledger.ProtocolParams => FuturePParams) =
    val ls = ledger.es.ls
    val gov = ls.utxo.gov
    val snapshots = ledger.es.snapshots
    ledger.copy(es =
      ledger.es.copy(
        ls = ls.copy[Conway](utxo =
          ls.utxo.copy[Conway](gov =
            gov.copy(futurePParams = f(gov.curPParams.copy(maxBlockHeaderSize = 1200)))
          )
        ),
        snapshots = snapshots.copy(mark = snapshots.mark.copy(pools = snapshots.mark.pools.drop(1)))
      )
    )
  private def headerLimit(v: Either[CrossingForecastFailure, Praos2.LedgerView]): Int =
    v.fold(e => fail(e.toString), _.maxHeaderSize.toInt)
  private def word16(n: Int) = Word16.from(n).fold(e => fail(e.toString), identity)
  private def word32(n: Long) = Word32.from(n).fold(e => fail(e.toString), identity)

  test("the chain crosses at slot 200, after the 33 Conway blocks") {
    assertEquals(conwayBlocks.size, 33)
    assertEquals(last.slot, BigInt(199))
    assertEquals(dijkstraHeaders.map(_.blockNo), (33 to 40).map(BigInt(_)).toVector)
  }

  test("the chain-dependent state carries every Praos field and no announcement") {
    val p = PraosState(
      WithOrigin.At(SlotNo.fromUnsigned(7)),
      Sorted.empty,
      Nonce.Neutral,
      Nonce.Hash(hex("11" * 32)),
      Nonce.Hash(hex("22" * 32)),
      Nonce.Neutral,
      Nonce.Hash(hex("33" * 32)),
      Nonce.Hash(hex("44" * 32))
    )
    assertEquals(DijkstraCrossing.chainDepState(p), Praos2.State(p, None))
  }

  test("every Dijkstra header the reference adopted passes Praos2 from the translated state") {
    val config = Praos2.Config(
      praos,
      Word64.fromUnsigned(12),
      Praos2.EpochInfo.fromGeometry(praos.geometry)
    )
    val atTip = Praos2.ChainState(
      Some(
        Praos2.Tip(last.hash, BlockNo.fromUnsigned(last.blockNo.toLong), SlotNo.fromUnsigned(199))
      ),
      DijkstraCrossing.chainDepState(praosState)
    )
    val end = dijkstraHeaders.foldLeft(atTip) { (state, h) =>
      val view = forecast(slotOf(h)).fold(e => fail(s"block ${h.blockNo}: $e"), identity)
      val ticked = Praos2.tick(config, view, slotOf(h), state).fold(e => fail(e.toString), identity)
      Praos2
        .validate(config, h, ticked)
        .fold(e => fail(s"block ${h.blockNo} refused: $e"), identity)
    }
    assertEquals(end.tip.map(_.blockNo.toBigInt), Some(BigInt(40)))
    assertNotEquals(end.protocol.praos.epochNonce, praosState.epochNonce)
  }

  test("the view holds the Dijkstra genesis's Leios parameters and the epoch's stake") {
    val view = forecast(slotOf(dijkstraHeaders.head)).fold(e => fail(e.toString), identity)
    assertEquals(view.announcementPeriodMillis, word32(1000))
    assertEquals(view.votePeriodMillis, word32(4000))
    assertEquals(view.diffusionPeriodMillis, word32(7000))
    assertEquals(view.maxEbReferencesSize, word32(40000))
    assertEquals(view.protocolVersion, ProtVer(Word64.fromUnsigned(12), Word64.zero))
    assertEquals(view.maxHeaderSize, word16(1100))
    assertEquals(view.pools.size, 3)
    assertEquals(
      view.pools.view.mapValues(_.share).toMap,
      ledger.es.snapshots.mark.pools.unsorted.collect {
        case (id, p) if !p.numDelegators.isZero => id -> p.stakeRatio
      }.toMap
    )
  }

  test("past the stability window after the transition the forecast is out of range") {
    // the tip's successor is the transition, so only the next era's window bounds it: 200 + 75
    val bound = SlotNo.fromUnsigned(275)
    assertEquals(praos.geometry.stabilityWindow.toBigInt, BigInt(75))
    assert(forecast(SlotNo.fromUnsigned(274)).isRight)
    assertEquals(
      forecast(bound),
      Left(CrossingForecastFailure.OutsideForecastRange(tip, bound, bound))
    )
  }

  test("from an earlier tip the current era's window bounds the forecast first") {
    val earlier = WithOrigin.At(SlotNo.fromUnsigned(150))
    val bound = SlotNo.fromUnsigned(226)
    assertEquals(
      forecast(bound, tipAt = earlier),
      Left(CrossingForecastFailure.OutsideForecastRange(earlier, bound, bound))
    )
  }

  test("crossEraForecastBound, case by case") {
    def bound(tip: WithOrigin[SlotNo], transition: Long, current: Int, next: Int): Long =
      DijkstraCrossing
        .forecastBound(tip, SlotNo.fromUnsigned(transition), current, next)
        .toBigInt
        .toLong
    def at(s: Long) = WithOrigin.At(SlotNo.fromUnsigned(s))
    // more blocks of this era can come: the lesser of the two bounds
    assertEquals(bound(at(150), 200, 75, 75), 226L)
    assertEquals(bound(at(150), 200, 75, 10), 210L)
    assertEquals(bound(WithOrigin.Origin, 200, 75, 75), 75L)
    // no block of this era can come: the next era's alone
    assertEquals(bound(at(199), 200, 75, 75), 275L)
    assertEquals(bound(at(199), 200, 0, 30), 230L)
    // a next era of no lookahead cannot be forecast into at all
    assertEquals(bound(at(199), 200, 75, 0), 200L)
  }

  test("without a Dijkstra genesis the translation is refused, as the node refuses it") {
    val bare = globals.copy(ledger = globals.ledger.copy(dijkstra = None))
    assertEquals(
      forecast(slotOf(dijkstraHeaders.head), g = bare),
      Left(CrossingForecastFailure.Translation(TickFailure.NoDijkstraGenesis))
    )
  }

  test("into the next epoch the forecast reads the rotated mark and the definite parameters") {
    val state = altered(FuturePParams.DefinitePParamsUpdate(_))
    val view =
      forecast(slotOf(dijkstraHeaders.head), state = state).fold(e => fail(e.toString), identity)
    assertEquals(view.pools.keySet, state.es.snapshots.mark.pools.keySet)
    assertEquals(view.pools.size, 2)
    assertEquals(view.maxHeaderSize, word16(1200))
    // the Leios parameters are those of the next parameters upgraded by the Dijkstra genesis
    assertEquals(view.diffusionPeriodMillis, word32(7000))
  }

  test("a potential update is adopted only from the slot of no return of the slot's epoch") {
    // the slot of no return of epoch 1 is 400 - 2 * 75; a crossing tip past epoch 0's (50) has
    // ticked its own state past it, so a tip holding a potential update is synthetic
    val state = altered(pp => FuturePParams.PotentialPParamsUpdate(Some(pp)))
    assertEquals(headerLimit(forecast(SlotNo.fromUnsigned(249), state = state)), 1100)
    assertEquals(headerLimit(forecast(SlotNo.fromUnsigned(250), state = state)), 1200)
  }
