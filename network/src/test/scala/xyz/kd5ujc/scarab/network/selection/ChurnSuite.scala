// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import scala.concurrent.duration.*

import Churn.Step.*

/** The churn arithmetic against `peerChurnGovernor` (`Cardano/Network/PeerSelection/Churn.hs`): the
  * `decrease` table, the twelve steps in order with their timeouts, the targets of each step in the
  * three regimes, and the sleep between rounds. The reference's own test of churn runs the whole
  * diffusion (`Test.Ouroboros.Network.Diffusion.Testnet`); here the pure part is checked on its own
  * and the running loop by `ChurnLoopSuite` in `node`.
  */
class ChurnSuite extends ScalaCheckSuite:

  private def ctx(kind: Regime.Kind, localHot: Int = 2, base: Targets = Targets.relay) =
    Churn.Context(kind, localHot, base)

  // ------------------------------------------------------------------ decrease

  test("decrease: 20% or at least one, never below zero (692-697)") {
    val table = List(0 -> 0, 1 -> 0, 2 -> 1, 4 -> 3, 5 -> 4, 9 -> 8, 10 -> 8, 20 -> 16, 100 -> 80)
    table.foreach((n, expected) => assertEquals(Churn.decrease(n), expected, s"decrease $n"))
    assertEquals(Churn.decrease(-3), 0)
  }

  test("decreaseWithMin: the larger of 20% and the minimum (696-697)") {
    assertEquals(Churn.decreaseWithMin(4)(10), 6)
    assertEquals(Churn.decreaseWithMin(4)(100), 80)
    assertEquals(Churn.decreaseWithMin(50)(10), 0)
    assertEquals(Churn.decreaseWithMin(0)(5), 4)
  }

  property("decrease replaces at least one and at most a fifth (rounded up to one)") {
    forAll(Gen.choose(1, 10000)) { n =>
      val lost = n - Churn.decrease(n)
      lost >= 1 && lost == n.min((n / 5).max(1))
    }
  }

  // ------------------------------------------------------------------ the round

  test("a round has twelve steps in the reference's order, six without big ledger peers") {
    assertEquals(
      Churn.round(bigLedger = true),
      List(
        DecreaseActiveBig,
        IncreaseActiveBig,
        DecreaseEstablishedBig,
        DecreaseKnownBig,
        IncreaseKnownBig,
        IncreaseEstablishedBig,
        DecreaseActive,
        IncreaseActive,
        DecreaseEstablished,
        DecreaseKnown,
        IncreaseKnown,
        IncreaseEstablished
      )
    )
    assertEquals(
      Churn.round(bigLedger = false),
      List(
        DecreaseActive,
        IncreaseActive,
        DecreaseEstablished,
        DecreaseKnown,
        IncreaseKnown,
        IncreaseEstablished
      )
    )
    assertEquals(Churn.round(true).map(_.number), (1 to 12).toList)
  }

  test("the timeouts of the steps (Churn.hs 568-650, Policies.hs 31-50)") {
    val t = Churn.Timeouts()
    assertEquals(
      Churn.round(true).map(_.timeout(t).toSeconds),
      // 300, 60, 1 + 120, 60, 2 * 10 + 60, 60, 300, 60, 1 + 120, 60, 2 * 10 + 60, 60
      List(300L, 60, 121, 60, 80, 60, 300, 60, 121, 60, 80, 60)
    )
  }

  test("each decrease takes its minimum from the step the reference passes it") {
    assertEquals(DecreaseEstablishedBig.minimumFrom, Some(DecreaseActiveBig))
    assertEquals(DecreaseKnownBig.minimumFrom, Some(DecreaseEstablishedBig))
    assertEquals(DecreaseEstablished.minimumFrom, Some(DecreaseActive))
    assertEquals(DecreaseKnown.minimumFrom, Some(DecreaseEstablished))
    assertEquals(DecreaseActive.minimumFrom, None)
  }

  /** Run a round: every step achieves `achieved` peers. */
  private def walk(
      c: Churn.Context,
      achieved: Int,
      bigLedger: Boolean = true
  ): List[(Churn.Step, Targets)] =
    Churn
      .round(bigLedger)
      .foldLeft((List.empty[(Churn.Step, Targets)], Churn.steady(c))) {
        case ((done, current), step) =>
          val next = Churn.apply(step, c, current, _ => achieved)
          (done :+ (step -> next), next)
      }
      ._1

  test("a round at the relay defaults in the default regime (the table of 1.10)") {
    // each step achieved 1 (the minimum), so 20% rules wherever it is more
    val steps = walk(ctx(Regime.Kind.Default), achieved = 1).toMap
    val relay = Targets.relay
    assertEquals(steps(DecreaseActiveBig).activeBig, 4)
    assertEquals(steps(IncreaseActiveBig).activeBig, 5)
    assertEquals(steps(DecreaseEstablishedBig).establishedBig, 9) // 5 - 1 above the 5 active
    assertEquals(steps(DecreaseKnownBig).knownBig, 14) // 5 - 1 above the 10 established
    assertEquals(steps(IncreaseKnownBig).knownBig, relay.knownBig)
    assertEquals(steps(IncreaseEstablishedBig).establishedBig, relay.establishedBig)
    assertEquals(steps(DecreaseActive).active, 16)
    assertEquals(steps(IncreaseActive).active, 20)
    assertEquals(steps(DecreaseEstablished).established, 28) // 10 - 2 above the 20 active
    val known = steps(DecreaseKnown)
    assertEquals(known.known, 126) // 120 - 24 above the 30 established
    assertEquals(known.root, 54) // 30 above the established, 24 fewer, kept <= 60
    assertEquals(steps(IncreaseKnown).root, 60)
    assertEquals(steps(IncreaseKnown).known, 150)
    assertEquals(steps(IncreaseEstablished), relay)
  }

  test("a decrease of the established set also loses what the active set lost (decreaseWithMin)") {
    // the active set lost 9 peers: the established set loses at least 9, not 20% of 10 (= 2)
    val c = ctx(Regime.Kind.Default)
    val after = Churn.apply(
      DecreaseEstablished,
      c,
      Targets.relay,
      {
        case DecreaseActive => 9
        case _              => 0
      }
    )
    assertEquals(after.established, 21) // 10 - max(9, 2) = 1 above the 20 active
  }

  test("PraosSync: the active target follows the local roots, big ledger peers one at a time") {
    val c = ctx(Regime.Kind.PraosSync, localHot = 2)
    assertEquals(
      Churn.apply(DecreaseActive, c, Targets.relay, _ => 1).active,
      2
    ) // min(max 1 2, 20 - 1)
    assertEquals(Churn.apply(IncreaseActive, c, Targets.relay, _ => 1).active, 3) // min(2 + 1, 20)
    assertEquals(Churn.apply(DecreaseActiveBig, c, Targets.relay, _ => 1).activeBig, 1)
    assertEquals(Churn.apply(IncreaseActiveBig, c, Targets.relay, _ => 1).activeBig, 1)
    // the established decrease is the ordinary one in this regime
    assertEquals(Churn.apply(DecreaseEstablished, c, Targets.relay, _ => 1).established, 28)
  }

  test(
    "BootstrapPraosSync: the established target stays within one of the active target (322-385)"
  ) {
    val c = ctx(Regime.Kind.BootstrapPraosSync, localHot = 1)
    val cut = Churn.steady(c)
    assertEquals(cut.active, 2) // min(max 1 1 + 1, 20)
    assertEquals(cut.activeBig, 1)
    val decreased = Churn.apply(DecreaseActive, c, cut, _ => 1)
    assertEquals(decreased.active, 1) // min(max 1 1, 19)
    val increased = Churn.apply(IncreaseActive, c, decreased, _ => 1)
    assertEquals(increased.active, 2)
    val lowered = Churn.apply(DecreaseEstablished, c, increased, _ => 1)
    assertEquals(lowered.established, 2) // min(active 2, 30 - 1)
    val raised = Churn.apply(IncreaseEstablished, c, lowered, _ => 1)
    assertEquals(raised.established, 3) // min(active 2 + 1, 30)
  }

  test("the head start and the targets after it (Churn.hs 176-211)") {
    val d = ctx(Regime.Kind.Default)
    assertEquals(Churn.head(d).root, 0)
    assertEquals(Churn.steady(d), Targets.relay)
    val b = ctx(Regime.Kind.BootstrapPraosSync, localHot = 2)
    assertEquals(Churn.head(b).root, Targets.relay.root)
    assertEquals(Churn.head(b).active, 3)
    assertEquals(Churn.steady(b), Churn.head(b))
  }

  test(
    "a step is reached when the counters meet its targets, a root decrease does not wait for roots"
  ) {
    val c = Targets.relay
    val empty = GovernorState
      .initial[Int](1L, LocalRoots.empty[Int], Targets.zero, Sensitivity.initial(false))
      .view(Duration.Zero)
      .counters
    def counters(known: Int, root: Int) = empty.copy(known = known, root = root)
    assert(
      DecreaseKnown.reached(counters(known = 150, root = 99), c.revised(known = 150).toOption.get)
    )
    assert(!DecreaseKnown.reached(counters(known = 151, root = 0), c))
    assert(IncreaseKnown.reached(counters(known = 150, root = 60), c))
    assert(!IncreaseKnown.reached(counters(known = 150, root = 59), c))
  }

  // ------------------------------------------------------------------ properties

  private val saneTargets: Gen[Targets] =
    for
      activeBig <- Gen.choose(0, 12)
      establishedBig <- Gen.choose(activeBig, 40)
      knownBig <- Gen.choose(establishedBig, 100)
      active <- Gen.choose(0, 30)
      established <- Gen.choose(active, 200)
      known <- Gen.choose(established, 600)
      root <- Gen.choose(0, known)
      targets <- Targets
        .validated(root, known, established, active, knownBig, establishedBig, activeBig)
        .fold(_ => Gen.fail, Gen.const)
    yield targets

  private val kinds = Gen.oneOf(Regime.Kind.values.toList)

  property(
    "every step of a round yields sane targets, for any sane base and any amounts achieved"
  ) {
    forAll(saneTargets, kinds, Gen.choose(0, 40), Gen.listOfN(12, Gen.choose(0, 60))) {
      (base, kind, localHot, amounts) =>
        val c = Churn.Context(kind, localHot, base)
        val achieved = Churn.round(true).zip(amounts).toMap
        val sane =
          Churn.round(true).foldLeft(Right(Churn.steady(c)): Either[Targets.Problem, Targets]) {
            (acc, step) => acc.flatMap(current => Churn.plan(step, c, current, achieved))
          }
        sane.isRight :| s"$sane"
    }
  }

  property("a round ends at the targets it began with (after the head start), in every regime") {
    forAll(saneTargets, kinds, Gen.choose(0, 40), Gen.choose(0, 60)) {
      (base, kind, localHot, amount) =>
        val c = Churn.Context(kind, localHot, base)
        val last = walk(c, amount).last._2
        // the increases restore the base targets, except in the sync regimes where the active
        // and the big ledger targets are the regime's own
        val steady = Churn.steady(c)
        last.root == base.root && last.known == base.known && last.knownBig == base.knownBig &&
        last.establishedBig == base.establishedBig &&
        (if kind == Regime.Kind.Default then
           last.active == base.active && last.activeBig == base.activeBig
         else last.active <= steady.active && last.activeBig <= base.activeBig.min(1))
    }
  }

  property("the targets a step asks for never exceed the base targets in the default regime") {
    forAll(saneTargets, Gen.choose(0, 60)) { (base, amount) =>
      val c = Churn.Context(Regime.Kind.Default, 2, base)
      walk(c, amount).forall { (_, x) =>
        x.root <= base.root && x.known <= base.known && x.established <= base.established &&
        x.active <= base.active && x.knownBig <= base.knownBig &&
        x.establishedBig <= base.establishedBig && x.activeBig <= base.activeBig
      }
    }
  }

  // ------------------------------------------------------------------ the sleep

  test("the sleep: 3300 s plus up to 600 s in deadline mode, 900 s plus up to 60 s in bulk sync") {
    val intervals = Churn.Intervals()
    val draws = (1L to 300L).map(seed =>
      Churn.delay(FetchMode.Deadline, intervals, Duration.Zero, Rng(seed))._1
    )
    assert(draws.forall(d => d >= 3300.seconds && d <= 3900.seconds), draws.toString)
    assert(draws.exists(_ > 3600.seconds) && draws.exists(_ < 3600.seconds))
    val bulk = (1L to 300L).map(seed =>
      Churn.delay(FetchMode.BulkSync, intervals, Duration.Zero, Rng(seed))._1
    )
    assert(bulk.forall(d => d >= 900.seconds && d <= 960.seconds), bulk.toString)
  }

  property(
    "the sleep subtracts the time the round took, never goes negative, and is a function of the seed"
  ) {
    forAll(Gen.choose(0L, 10000L), Gen.choose(0L, 7200L), Gen.oneOf(FetchMode.values.toList)) {
      (seed, took, mode) =>
        val (a, next) = Churn.delay(mode, Churn.Intervals(), took.seconds, Rng(seed))
        val (b, _) = Churn.delay(mode, Churn.Intervals(), took.seconds, Rng(seed))
        val (whole, _) = Churn.delay(mode, Churn.Intervals(), Duration.Zero, Rng(seed))
        a == b && a >= Duration.Zero && a == (whole - took.seconds).max(Duration.Zero) &&
        next != Rng(seed)
    }
  }
