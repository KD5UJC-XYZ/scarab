// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, BlockTransport, Header, PeerError}

/** A malformed or mismatched body cannot enter the semantic-invalidity punishment path. The block,
  * decoded, is what the forest reads its Leios fields from ([[BlockPool.Delivered.checked]]).
  */
private[node] object FetchedBlockIntegrity:
  def check(header: Header, raw: Bytes): Either[PeerError.ProtocolProblem, Block] =
    BlockTransport
      .check(header, raw)
      .leftMap(PeerError.ProtocolProblem.FetchedBlockIntegrity(_))
