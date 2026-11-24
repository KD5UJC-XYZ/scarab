// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.Phase2
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBbodyImpCases, DijkstraPlanned}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** The reference's BBODY cases for Dijkstra (`DijkstraBbodyImpCases`, ledger) with every prepared
  * block run through the node's own scheduler (`ParallelPlutus`) at one, two, four and eight
  * workers, in place of as many threads: the eight-script block of `Alonzo/Imp/BbodySpec.hs:49`
  * (cardano-ledger 9df9f98c) and the sub-transaction's batch among them, and the crafted blocks
  * whose scripts read what another batch changes, a script that fails in the middle of a block
  * under each flag, and no verdict at a sub-transaction's script; each to the serial run's result
  * (#85). Each case's effect is returned to the test framework.
  */
class DijkstraBbodyImpWorkersSuite extends FailsOnUncaught with DijkstraBbodyImpCases[IO]:
  protected def runner: DijkstraPlanned.Runner[IO] = (body, n) =>
    ParallelPlutus.Workers
      .from(n)
      .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
      .flatMap(_(body))
      .attempt
      .map(_.leftMap(Phase2.Defect(_)))

  protected def register(name: String)(body: => IO[Unit]): Unit = test(name)(IO.defer(body))
