// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.era.{EraTxError, TxLevel}

/** The era decoder's two passes, the flat block decoder and the projection of what it read
  * ([[DijkstraTx.inBlockByFlat]], [[DijkstraTx.fromSubmissionByFlat]]), for the test code of other
  * packages: the fuzz target that compares them with the decoder's one pass (#85).
  */
object DijkstraTwoPasses:
  def inBlock(n: Node): Either[EraTxError, DijkstraTx[TxLevel.Top]] = DijkstraTx.inBlockByFlat(n)

  def fromSubmission(raw: Bytes): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
    DijkstraTx.fromSubmissionByFlat(raw)
