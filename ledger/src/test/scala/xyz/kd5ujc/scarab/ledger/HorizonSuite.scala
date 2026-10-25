// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.Horizon.{Edge, Era, History, Params, Transition}

/** The forecast horizon of the era history at a ledger tip: `reconstructSummary` and the
  * `slotToWallclock` query of ouroboros-consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40`
  * (`HardFork/Combinator/State/Infra.hs`, `HardFork/History/Qry.hs`); the byte-for-byte text of the
  * reference's refusal is checked against the recorded refusals by `PastHorizonReferenceSuite`.
  */
class HorizonSuite extends FunSuite:
  // 1 s slots, 100-slot epochs, a safe zone of 30 slots
  private val params = Params(100, 1000, 30, 30)
  private val start = Edge(0, 0, 0)
  private val history = History(Vector.empty, start, params, Some(params))

  private def horizon(transition: Transition) = history.at(transition)

  test("an unknown transition ends the era at the epoch after the tip plus the safe zone") {
    // tip 150: 151 + 30 = 181, rounded up to the epoch boundary 200
    val h = horizon(Transition.Unknown(Some(150)))
    assertEquals(h.eras.last.end.map(_.slot), Some(BigInt(200)))
    assert(h.timePs(199).isDefined)
    assertEquals(h.timePs(199), Some(BigInt(199) * BigInt("1000000000000")))
  }

  test("the slot at which the era ends is past the horizon, the one before it is inside") {
    val h = horizon(Transition.Unknown(Some(150)))
    assertEquals(h.timePs(200), None)
    assertEquals(h.timePs(201), None)
  }

  test("a safe zone that ends on an epoch boundary does not round up past it") {
    // tip 169: 170 + 30 = 200
    val h = horizon(Transition.Unknown(Some(169)))
    assertEquals(h.eras.last.end.map(_.slot), Some(BigInt(200)))
    // tip 170: 171 + 30 = 201, the next epoch
    assertEquals(
      horizon(Transition.Unknown(Some(170))).eras.last.end.map(_.slot),
      Some(BigInt(300))
    )
  }

  test("at Origin the safe zone starts at slot zero") {
    val h = horizon(Transition.Unknown(None))
    assertEquals(h.eras.last.end.map(_.slot), Some(BigInt(100)))
    assert(h.timePs(99).isDefined)
    assertEquals(h.timePs(100), None)
  }

  test("a known transition ends the era there and the next era's safe zone starts at its start") {
    // the next era starts at epoch 3 (slot 300); its horizon is 300 + 30 rounded up: slot 400
    val h = horizon(Transition.Known(3))
    assertEquals(
      h.eras.toVector.map(_.end.map(_.slot)),
      Vector(Some(BigInt(300)), Some(BigInt(400)))
    )
    // the last slot of the first era and the first of the second are both inside
    assert(h.timePs(299).isDefined)
    assertEquals(h.timePs(300), Some(BigInt(300) * BigInt("1000000000000")))
    assert(h.timePs(399).isDefined)
    assertEquals(h.timePs(400), None)
  }

  test("a known transition without a next era in the code ends the history at the transition") {
    val h = History(Vector.empty, start, params, None).at(Transition.Known(3))
    assertEquals(h.eras.length, 1)
    assert(h.timePs(299).isDefined)
    assertEquals(h.timePs(300), None)
  }

  test("an era that starts after others is placed by its own start") {
    val slot = Params(10, 20000, 6, 6)
    val past = Era(Edge(0, 0, 0), Some(Edge(BigInt(200) * BigInt("1000000000000"), 10, 1)), slot)
    val started = Edge(BigInt(200) * BigInt("1000000000000"), 10, 1)
    val h = History(Vector(past), started, params, None).at(Transition.Unknown(Some(20)))
    // 21 + 30 - 10 = 41 slots from the start of the era: epochs of 100, so its end is 100 slots on
    assertEquals(h.eras.last.end.map(_.slot), Some(BigInt(110)))
    // a slot of the first era is 20 s long
    assertEquals(h.timePs(5), Some(BigInt(100) * BigInt("1000000000000")))
    assertEquals(h.timePs(11), Some(BigInt(201) * BigInt("1000000000000")))
  }

  test("seconds are shown as Haskell shows a NominalDiffTime") {
    assertEquals(Horizon.showSeconds(0), "0s")
    assertEquals(Horizon.showSeconds(BigInt("150000000000000")), "150s")
    assertEquals(Horizon.showSeconds(BigInt("100000000000")), "0.1s")
    assertEquals(Horizon.showSeconds(BigInt("1234500000000")), "1.2345s")
  }

  test("the clock of a horizon fails past it with the horizon, and starts from the system start") {
    val clock = PosixClock.Forecast(BigInt(1000000), horizon(Transition.Unknown(Some(150))))
    assertEquals(clock.posix(199), Right(BigInt(1000000 + 199000)))
    clock.posix(200) match
      case Left(TranslationError.PastHorizon(slot, _)) => assertEquals(slot, BigInt(200))
      case other                                       => fail(s"$other")
  }

  test("Alonzo's clock extends the history linearly from the slot of the block, past the horizon") {
    val base = PosixClock.Forecast(BigInt(1000000), horizon(Transition.Unknown(Some(150))))
    val clock = PosixClock.Extended(160, base)
    // up to the basis slot the history answers; past the horizon the slot length at the basis goes on
    assertEquals(clock.posix(160), Right(BigInt(1000000 + 160000)))
    assertEquals(clock.posix(200), Right(BigInt(1000000 + 200000)))
    assertEquals(clock.posix(100000), Right(BigInt(1000000 + 100000000)))
    // a basis the history does not cover cannot be extended from
    PosixClock.Extended(250, base).posix(251) match
      case Left(TranslationError.PastHorizon(slot, _)) => assertEquals(slot, BigInt(250))
      case other                                       => fail(s"$other")
  }
