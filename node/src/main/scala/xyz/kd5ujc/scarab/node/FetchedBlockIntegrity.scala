// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{BlockTransport, Header, PeerError}

/** A malformed or mismatched body cannot enter the semantic-invalidity punishment path. */
private[node] object FetchedBlockIntegrity:
  def check(header: Header, raw: Bytes): Either[PeerError.ProtocolProblem, Unit] =
    BlockTransport.check(header, raw).void.leftMap(PeerError.ProtocolProblem.FetchedBlockIntegrity(_))
