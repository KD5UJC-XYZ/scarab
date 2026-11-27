// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import munit.FunSuite
import scala.concurrent.duration.*

/** The fuzz run: for each decoder entry point, mutations of its seeds (fixtures and CDDL samples)
  * under the properties of `Probe`. Cases, first seed and seed count come from the `fuzz` command.
  */
class FuzzSuite extends FunSuite:
  override val munitTimeout: Duration = 2.hours

  test("target names are unique") {
    val names = Targets.all.map(_.name)
    assertEquals(names.distinct, names)
  }

  for target <- Targets.all do
    test(s"fuzz ${target.name}") {
      val before = Option(Targets.accepted.get(target.name)).fold(0L)(_.sum())
      Runner.run(target.name, Mutate.generator(target.corpus().toIndexedSeq))(target.check)
      val accepted = Option(Targets.accepted.get(target.name)).fold(0L)(_.sum()) - before
      println(s"  ${target.name}: ${FuzzConfig.cases * FuzzConfig.seeds} cases, $accepted decoded")
    }

  test("differential target names are unique and are not decoder target names") {
    val names = SodiumTargets.all.map(_.target.name)
    assertEquals(names.distinct, names)
    assertEquals(names.filter(Targets.all.map(_.name).contains(_)), Vector.empty[String])
  }

  // The differential targets against the libsodium the reference links (`SodiumTargets`): skipped when
  // `Sodium.Variable` names no library, failed when it names one that cannot be used. Each run must see
  // a case both sides accept and, for the verifiers, one both refuse and one both accept under a
  // mixed-order key.
  for SodiumTargets.Differential(target, share, verifier) <- SodiumTargets.all do
    test(s"fuzz ${target.name}") {
      Sodium.oracle match
        case Left(Sodium.Unavailable.NotConfigured) =>
          println(s"  ${target.name}: skipped: ${Sodium.notConfigured}")
          assume(false, Sodium.notConfigured)
        case Left(why) =>
          fail(s"the libsodium oracle named by ${Sodium.Variable} cannot be used: $why")
        case Right(_) =>
          val before = SodiumTargets.tally(target.name)
          Runner.run(target.name, Mutate.generator(target.corpus().toIndexedSeq), share)(
            target.check
          )
          val run = SodiumTargets.tally(target.name) - before
          val sides =
            if verifier then
              s"${run.accepted} accepted (${run.mixed} under a mixed-order key) and ${run.refused} refused"
            else s"${run.accepted} accepted"
          val cases = math.max(1, FuzzConfig.cases / share) * FuzzConfig.seeds
          println(s"  ${target.name}: $cases cases, $sides by both")
          assert(run.accepted > 0, s"${target.name}: no case that both sides accept")
          assert(!verifier || run.refused > 0, s"${target.name}: no case that both sides refuse")
          assert(
            !verifier || run.mixed > 0,
            s"${target.name}: no mixed-order key both sides accept"
          )
    }
