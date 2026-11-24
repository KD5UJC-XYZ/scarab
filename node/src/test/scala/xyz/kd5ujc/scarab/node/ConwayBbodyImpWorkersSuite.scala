// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.Phase2
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayBbodyImpSuite, ConwayPlanned}

/** `ConwayBbodyImpSuite` (ledger) with every block it checks run through the node's own scheduler
  * (`ParallelPlutus`) at one, two, four and eight workers, in place of as many threads: the
  * reference's BBODY cases for Conway at cardano-ledger f649f975 (and Shelley's of 9df9f98c), the
  * eight-script block of `Alonzo/Imp/BbodySpec.hs:46` among them, at each of Conway's versions;
  * each to the serial run's result (#85).
  */
class ConwayBbodyImpWorkersSuite extends ConwayBbodyImpSuite:
  /** The node's bounded workers at each count of `ConwayPlanned.workers`, made once. */
  private lazy val services: Map[Int, ParallelPlutus[IO]] =
    // the suite's checks are synchronous (`FunSuite`): the scheduler's effect is run here, the one
    // place this suite leaves `IO`, as `DijkstraBbodyImpWorkersSuite` does
    ConwayPlanned.workers
      .traverse(n =>
        ParallelPlutus.Workers
          .from(n)
          .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
          .map(n -> _)
      )
      .unsafeRunSync()
      .toMap

  override protected def runner: ConwayPlanned.Runner = (body, n) =>
    services(n)(body).attempt.unsafeRunSync().leftMap(Phase2.Defect(_))
