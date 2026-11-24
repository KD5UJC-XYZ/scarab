// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.Id
import munit.FunSuite
import scala.concurrent.duration.*

/** The reference's BBODY cases for Conway ([[ConwayBbodyImpCases]]), each prepared block run on as
  * many threads as each of the node's worker counts ([[ConwayPlanned.threaded]]); the node's own
  * scheduler runs them in `ConwayBbodyImpWorkersSuite` (node).
  */
class ConwayBbodyImpSuite
    extends FunSuite
    with ConwayBbodyImpCases[Id](using cats.catsInstancesForId):
  // the block of `Conway/Imp/BbodySpec.hs:86` (cardano-ledger f649f975) holds 2,424 transactions,
  // each made by the fixups and submitted before the block is judged: some 40 s on a CI runner
  override val munitTimeout: Duration = 10.minutes

  protected def runner: ConwayPlanned.Runner[Id] = ConwayPlanned.threaded
  protected def register(name: String)(body: => Id[Unit]): Unit = test(name)(body)
