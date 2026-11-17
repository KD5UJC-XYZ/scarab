// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ConsensusExtras, FollowState, ForkTrigger, HardForkConfig}
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, TestGeometry}
import xyz.kd5ujc.scarab.ledger.state.{MirrorEnv, Word64}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** The era history's bounds in the layout of each build (#826): the Peras round of `initBound` and
  * of `mkUpperBound` (ouroboros-consensus 2416493b `HardFork/History/Summary.hs:103-151`, 82ecba32
  * `:92-141`), the era entered by a block, the summary from the configuration, and a chain that
  * starts in Byron. The rounds an era with rounds adds are hand-derived from `mkUpperBound`: no
  * captured state of the Leios build has a bound after Dijkstra's slots, the only ones that count.
  */
class LeiosBoundsSuite extends FunSuite:
  private val geometry = EpochGeometry(
    java.time.Instant.EPOCH,
    1000,
    epochLength = 500,
    securityParam = TestGeometry.k(5),
    activeSlotsCoeff = TestGeometry.f("0.5")
  )
  private val byron = EraHistory.ByronParams(50, 20000, 5)

  private def round(n: Long): PerasRoundNo = PerasRoundNo(Word64.fromUnsigned(n))
  private def leios(n: Long): Bound.Layout = Bound.Layout.Leios(round(n))
  private val none: Bound.Layout = Bound.Layout.Node11_1(None)
  private def bound(epoch: Int, layout: Bound.Layout) =
    EraHistory.boundAt(geometry, byron, epoch).copy(layout = layout)

  test("only Dijkstra, the last era, has Peras rounds, of 90 slots, and only in the Leios build") {
    // `getShelleyEraPerasRoundLength` (2416493b `Shelley/Eras.hs:147-151,283`), Byron's
    // `NoPerasEnabled`; the 11.1 line's `shelleyEraParams` gives none (82ecba32)
    assertEquals(Era.values.last, Era.Dijkstra)
    assertEquals(
      Era.values.toVector.map(EraHistory.perasRoundLength(StateLayout.Leios, _)),
      Vector.fill(7)(None) :+ Some(BigInt(90))
    )
    assertEquals(
      Era.values.toVector.flatMap(EraHistory.perasRoundLength(StateLayout.Node11_1, _)),
      Vector.empty
    )
  }

  test("mkUpperBound's round: an era with rounds adds the whole rounds of its slots, as a Word64") {
    // 1000 slots of Dijkstra are 11 rounds of 90 (`div`), added to the lower bound's 5
    assertEquals(EraHistory.upperLayout(leios(5), Era.Dijkstra, 1000), leios(16))
    assertEquals(EraHistory.upperLayout(leios(5), Era.Dijkstra, 89), leios(5))
    // `addPerasRounds` is a `Word64` sum (`HardFork/History/Util.hs:31-32`)
    assertEquals(EraHistory.upperLayout(leios(-1L), Era.Dijkstra, 90), leios(0))
    // an era with no rounds keeps the lower bound's next round
    assertEquals(EraHistory.upperLayout(leios(5), Era.Conway, 1000), leios(5))
    // the 11.1 line: no era has rounds, so the round is `NoPerasEnabled` (82ecba32 `Summary.hs:130`)
    assertEquals(
      EraHistory.upperLayout(Bound.Layout.Node11_1(Some(round(3))), Era.Dijkstra, 900),
      none
    )
    assertEquals(EraHistory.upperLayout(none, Era.Conway, 1000), none)
  }

  test("an era a block enters starts in the layout of the era that ends there, its round kept") {
    val placement = Placement(geometry, byron, HardForkConfig(Map.empty, 0))
    val slotInEpoch6 = geometry.firstSlot(6) + 3
    assertEquals(
      MirrorStep.startOf(placement, Era.Dijkstra, slotInEpoch6, bound(2, leios(7))),
      bound(6, leios(7))
    )
    assertEquals(
      MirrorStep.startOf(placement, Era.Alonzo, slotInEpoch6, bound(2, leios(0))),
      bound(6, leios(0))
    )
    assertEquals(
      MirrorStep.startOf(placement, Era.Alonzo, slotInEpoch6, bound(2, none)),
      bound(6, none)
    )
  }

  private val forks = HardForkConfig(
    Map(
      Era.Allegra -> ForkTrigger.AtEpoch(BigInt(0)),
      Era.Mary -> ForkTrigger.AtEpoch(BigInt(0)),
      Era.Alonzo -> ForkTrigger.AtEpoch(BigInt(0)),
      Era.Babbage -> ForkTrigger.AtEpoch(BigInt(0)),
      Era.Conway -> ForkTrigger.AtEpoch(BigInt(1)),
      Era.Dijkstra -> ForkTrigger.AtEpoch(BigInt(3))
    ),
    12
  )

  test("the era history of the Leios build: round zero up to Dijkstra, whose end adds its rounds") {
    val tip = geometry.firstSlot(3) + 10
    val eras =
      EraHistory.summary(geometry, byron, forks, Map.empty, Era.Dijkstra, tip, StateLayout.Leios)
    assertEquals(eras.size, Era.values.length)
    eras.init.foreach { e =>
      assertEquals(e.start.layout, leios(0))
      assertEquals(e.end.map(_.layout), Some(leios(0)))
      assertEquals(e.perasRoundLength, None)
    }
    val dijkstra = eras.last
    assertEquals(dijkstra.start, EraHistory.boundAt(geometry, byron, 3).copy(layout = leios(0)))
    assertEquals(dijkstra.perasRoundLength, Some(BigInt(90)))
    // the safe zone (3k/f = 30 slots) from the slot after the tip ends Dijkstra at epoch 4: 500 of
    // its slots, 5 whole rounds of 90
    val end = dijkstra.end.getOrElse(fail("Dijkstra has an end"))
    assertEquals(end, EraHistory.boundAt(geometry, byron, 4).copy(layout = leios(5)))
  }

  test("the era history of the 11.1 build is unchanged: no round anywhere, no round length") {
    val tip = geometry.firstSlot(3) + 10
    val eras =
      EraHistory.summary(geometry, byron, forks, Map.empty, Era.Dijkstra, tip, StateLayout.Node11_1)
    assert(eras.forall(e => e.start.layout == none && e.end.forall(_.layout == none)))
    assert(eras.forall(_.perasRoundLength.isEmpty))
  }

  private def byronStart(layout: StateLayout): MirrorState =
    val env = ConsensusMirrorEnv(MirrorEnv(42, geometry, 5), forks, byron, layout)
    val protocol = ProtocolState.initial(Era.Byron, Bytes.fromArray(Array.fill(32)(7.toByte)))
    ByronMirror
      .of(
        env,
        None,
        protocol.dep,
        ConsensusExtras(FollowState.initial, ByronState.initial, protocol.extras),
        MirrorProvenance.complete
      )
      .fold(e => fail(e.toString), identity)

  test(
    "a chain that starts in Byron, in the Leios layout: initBound and no Peras context or cert"
  ) {
    // `injectInitialExtLedgerState`'s Peras state of a Byron start (2416493b
    // `HardFork/Combinator/Embed/Nary.hs:347-357`): `initPerasEpochContextResolver` at the origin,
    // `[1, [], []]`, and `SNothing`
    val m = byronStart(StateLayout.Leios)
    assertEquals(
      m.ext.layout,
      ExtLedgerState.Layout.Leios(PerasState(PerasResolver.Disabled, None))
    )
    assertEquals(m.ext.ledgerState.era, Era.Byron)
    assertEquals(m.ext.ledgerState.current.start, Bound(0, 0, 0, leios(0)))
    assertEquals(m.ext.headerState.chainDep.current.start, Bound(0, 0, 0, leios(0)))
  }

  test("a chain that starts in Byron, in the 11.1 layout: as before") {
    val m = byronStart(StateLayout.Node11_1)
    assertEquals(m.ext.layout, ExtLedgerState.Layout.Node11_1)
    assertEquals(m.ext.ledgerState.current.start, Bound(0, 0, 0, none))
    assertEquals(m.ext.headerState.chainDep.current.start, Bound(0, 0, 0, none))
  }

  test(
    "GetInterpreter's summary of a Byron start: the telescope's start, the computed end laid out"
  ) {
    val eras = MirrorClock.interpreter(
      byronStart(StateLayout.Leios),
      Placement(geometry, byron, forks)
    )
    assertEquals(eras.map(_.start.layout).distinct, Vector(leios(0)))
    assertEquals(eras.flatMap(_.end).map(_.layout).distinct, Vector(leios(0)))
    assertEquals(eras.map(_.perasRoundLength).distinct, Vector(None))
  }
