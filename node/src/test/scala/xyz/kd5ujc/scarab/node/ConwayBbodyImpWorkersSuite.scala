// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.Phase2
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayBbodyImpCases, ConwayPlanned}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** The reference's BBODY cases for Conway (`ConwayBbodyImpCases`, ledger) with every block they
  * check run through the node's own scheduler (`ParallelPlutus`) at one, two, four and eight
  * workers, in place of as many threads; each to the serial run's result (#85). Each case's effect
  * is returned to the test framework.
  */
class ConwayBbodyImpWorkersSuite extends FailsOnUncaught with ConwayBbodyImpCases[IO]:
  // the block of `Conway/Imp/BbodySpec.hs:86` (cardano-ledger f649f975) holds 2,424 transactions,
  // each made by the fixups
  override def munitIOTimeout: Duration = 10.minutes

  protected def runner: ConwayPlanned.Runner[IO] = (body, n) =>
    ParallelPlutus.Workers
      .from(n)
      .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
      .flatMap(_(body))
      .attempt
      .map(_.leftMap(Phase2.Defect(_)))

  protected def register(name: String)(body: => IO[Unit]): Unit = test(name)(IO.defer(body))
