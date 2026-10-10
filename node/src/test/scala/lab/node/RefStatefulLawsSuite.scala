// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestInstances
import cats.kernel.Eq
import cats.mtl.laws.discipline.StatefulTests
import munit.DisciplineSuite

/** The `Ref`-backed `Stateful` interpreter used by the node obeys the cats-mtl Stateful laws. */
class RefStatefulLawsSuite extends DisciplineSuite with TestInstances:
  given Ticker = Ticker()

  private val ref = unsafeRun(Ref.of[IO, Int](0)).fold(
    sys.error("canceled"),
    e => throw e,
    _.getOrElse(sys.error("no ref"))
  )

  given eqUnit: Eq[IO[Unit]] = eqIOA[Unit]
  given eqInt: Eq[IO[Int]] = eqIOA[Int]

  checkAll("RefStateful", StatefulTests(RefStateful[IO, Int](ref)).stateful)
