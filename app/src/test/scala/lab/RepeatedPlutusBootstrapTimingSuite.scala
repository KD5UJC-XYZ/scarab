// SPDX-License-Identifier: Apache-2.0
package lab

import lab.ledger.ConwayEpochBoundary as B
import scala.compiletime.testing.typeCheckErrors

/** Generated source-shaped fixtures only; no live or retained native comparison. */
class RepeatedPlutusBootstrapTimingSuite extends NativeLedgerSeedFixtures:
  test("bootstrap reward profile derives 4k/f from its checked source instead of consensus 3k/f") {
    val source = get(bundle().join)
    val profile = get(RepeatedPlutusBootstrap.boundaryProfile(source))
    assertEquals(source.globals.stabilityWindow, BigInt(300))
    assertEquals(source.globals.randomnessStabilisationWindow, BigInt(400))
    assertEquals(profile.window, source.globals.randomnessStabilisationWindow)
    assert(profile.globals eq source.globals)
    assertEquals(profile.roles.id, source.parameterRoles.id)
    for epochFirst <- Vector[BigInt](0, 1000) do
      for offset <- Vector[BigInt](300, 301, 319, 399, 400) do
        assertEquals(
          get(B.rewardTiming(epochFirst, profile.window, epochFirst + offset)),
          B.Timing.TooEarly
        )
      for offset <- Vector[BigInt](401, 799, 800) do
        assertEquals(
          get(B.rewardTiming(epochFirst, profile.window, epochFirst + offset)),
          B.Timing.StartOrPulse
        )
      assertEquals(
        get(B.rewardTiming(epochFirst, profile.window, epochFirst + 801)),
        B.Timing.ForceCompletion
      )
  }

  test("typed reward profiles have no caller-supplied timing override") {
    assert(
      typeCheckErrors(
        "lab.CoherentSequence.syntheticBoundaryProfile(null, null, null, BigInt(300))"
      ).nonEmpty
    )
    assert(RepeatedPlutusBootstrap.boundaryProfile(null).isLeft)
    assert(CoherentSequence.syntheticBoundaryProfile(null, null, null).isLeft)
    val incompatible = get(bundle(epochLength = 500).join)
    assert(
      RepeatedPlutusBootstrap
        .boundaryProfile(incompatible)
        .left
        .exists(
          _.toString.contains("source-bound reward timing bounds")
        )
    )
  }

  test("matching window scalars do not admit foreign supplied globals into the checked source") {
    val source = bundle()
    val foreign = bundle(slot = 37)
    assertEquals(source.globals.stabilityWindow, foreign.globals.stabilityWindow)
    assertEquals(
      source.globals.randomnessStabilisationWindow,
      foreign.globals.randomnessStabilisationWindow
    )
    assertNotEquals(source.globals.id, foreign.globals.id)
    val rejected = NativeLedgerSeed.bind(
      source.epoch,
      source.governance,
      foreign.globals,
      source.epoch.point,
      source.epoch.id
    )
    assert(rejected.left.exists(_.contains("globals source/temporal roles splice")))
  }
