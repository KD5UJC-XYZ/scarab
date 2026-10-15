// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import munit.FunSuite
import scala.concurrent.duration.*

/** The fuzz run: for each decoder entry point, mutations of its seeds (fixtures and CDDL samples)
  * under the properties of `Probe`. Cases, first seed and seed count come from the `fuzz` command.
  */
class FuzzSuite extends FunSuite:
  override val munitTimeout: Duration = 2.hours

  for target <- Targets.all do
    test(s"fuzz ${target.name}") {
      val before = Option(Targets.accepted.get(target.name)).fold(0L)(_.sum())
      Runner.run(target.name, Mutate.generator(target.corpus().toIndexedSeq))(target.check)
      val accepted = Option(Targets.accepted.get(target.name)).fold(0L)(_.sum()) - before
      println(s"  ${target.name}: ${FuzzConfig.cases * FuzzConfig.seeds} cases, $accepted decoded")
    }
