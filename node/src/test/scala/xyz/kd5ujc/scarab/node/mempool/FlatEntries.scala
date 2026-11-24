// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.Tx

/** The flat transaction of a held one of an era from Shelley to Conway, for the suites of those
  * eras, which hold no Dijkstra transaction: one fails the suite.
  */
object FlatEntries:
  extension (entry: Entry)
    def tx: Tx = entry.held match
      case Held.Flat(_, _, tx) => tx
      case Held.InConway(held) => held.read.flat
      case Held.InDijkstra(held) =>
        munit.Assertions.fail(
          s"a Dijkstra transaction, ${held.read.id.hex}, where a flat one is held"
        )
