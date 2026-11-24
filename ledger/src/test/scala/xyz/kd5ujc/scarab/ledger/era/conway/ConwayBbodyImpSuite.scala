// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.Id
import munit.FunSuite

/** The reference's BBODY cases for Conway ([[ConwayBbodyImpCases]]), each prepared block run on as
  * many threads as each of the node's worker counts ([[ConwayPlanned.threaded]]); the node's own
  * scheduler runs them in `ConwayBbodyImpWorkersSuite` (node).
  */
class ConwayBbodyImpSuite
    extends FunSuite
    with ConwayBbodyImpCases[Id](using cats.catsInstancesForId):
  protected def runner: ConwayPlanned.Runner[Id] = ConwayPlanned.threaded
  protected def register(name: String)(body: => Id[Unit]): Unit = test(name)(body)
