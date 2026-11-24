// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.Phase2
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBbodyImpSuite, DijkstraPlanned}

/** `DijkstraBbodyImpSuite` (ledger) with every prepared block run through the node's own scheduler
  * (`ParallelPlutus`) at one, two, four and eight workers, in place of as many threads: the
  * reference's BBODY cases, the eight-script block of `Alonzo/Imp/BbodySpec.hs:49` (cardano-ledger
  * 9df9f98c) and the sub-transaction's batch among them, and the crafted blocks whose scripts read
  * what another batch changes, a script that fails in the middle of a block under each flag, and no
  * verdict at a sub-transaction's script; each to the serial run's result (#85).
  */
class DijkstraBbodyImpWorkersSuite extends DijkstraBbodyImpSuite:
  /** The node's bounded workers at each count of `DijkstraPlanned.workers`, made once. */
  private lazy val services: Map[Int, ParallelPlutus[IO]] =
    // the suite's checks are synchronous (`FunSuite`): the scheduler's effect is run here, the one
    // place this suite leaves `IO`
    DijkstraPlanned.workers
      .traverse(n =>
        ParallelPlutus.Workers
          .from(n)
          .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
          .map(n -> _)
      )
      .unsafeRunSync()
      .toMap

  override protected def runner: DijkstraPlanned.Runner = (body, n) =>
    services(n)(body).attempt.unsafeRunSync().leftMap(Phase2.Defect(_))
