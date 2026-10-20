// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.state.{Likelihood, LogWeight}

class RewardPerformanceSuite extends FunSuite:
  private def likelihood(weights: Vector[Float]): Option[Likelihood] =
    Some(Likelihood(weights.map(LogWeight(_))))

  test("absent pool history has the exact neutral estimate observed on native 11.1.3") {
    assertEquals(RewardQueries.performance(None), Right(0.885))
    assertEquals(RewardQueries.performance(likelihood(Vector.fill(100)(0.0f))), Right(0.885))
  }

  test("extreme finite likelihoods keep Float normalization and the initial-zero CDF position") {
    assertEquals(
      RewardQueries.performance(likelihood(Vector.fill(100)(Float.MaxValue))),
      Right(0.015)
    )
    assertEquals(
      RewardQueries.performance(likelihood(Vector.fill(100)(-Float.MaxValue))),
      Right(0.015)
    )
    val first = Vector.tabulate(100)(i => if i == 0 then Float.MaxValue else -Float.MaxValue)
    val last = Vector.tabulate(100)(i => if i == 99 then Float.MaxValue else -Float.MaxValue)
    assertEquals(RewardQueries.performance(likelihood(first)), Right(0.015))
    assertEquals(RewardQueries.performance(likelihood(last)), Right(1.0))
  }

  test("unusable imported likelihoods return a typed unwritable answer") {
    assertEquals(RewardQueries.performance(likelihood(Vector.empty)), Left(Unanswered.Unwritable))
    assertEquals(
      RewardQueries.performance(likelihood(Vector(Float.NaN))),
      Left(Unanswered.Unwritable)
    )
    assertEquals(
      RewardQueries.performance(likelihood(Vector(Float.PositiveInfinity))),
      Left(Unanswered.Unwritable)
    )
  }
