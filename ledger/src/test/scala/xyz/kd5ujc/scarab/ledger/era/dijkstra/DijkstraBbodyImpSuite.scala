// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.Id
import munit.FunSuite

/** The reference's BBODY cases for Dijkstra ([[DijkstraBbodyImpCases]]), each prepared block run on
  * as many threads as each of the node's worker counts ([[DijkstraPlanned.threaded]]); the node's
  * own scheduler runs them in `DijkstraBbodyImpWorkersSuite` (node).
  */
class DijkstraBbodyImpSuite
    extends FunSuite
    with DijkstraBbodyImpCases[Id](using cats.catsInstancesForId):
  protected def runner: DijkstraPlanned.Runner[Id] = DijkstraPlanned.threaded
  protected def register(name: String)(body: => Id[Unit]): Unit = test(name)(body)
