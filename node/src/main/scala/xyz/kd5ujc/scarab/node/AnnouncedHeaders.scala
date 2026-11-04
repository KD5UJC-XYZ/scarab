// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.{Header, LeiosHeader}
import xyz.kd5ujc.scarab.protocol.{HfcHeader, LeiosNotify}

/** The header of a LeiosNotify announcement read by its era as the build the Leios testnet runs
  * reads it (#757): `Header blk` with the node-to-node decoder of the era
  * (`HardFork/Combinator/Serialisation/SerialiseNodeToNode.hs:125-130`, ouroboros-consensus
  * `2416493b`). Index 7 is Dijkstra's Leios header ([[LeiosHeader.decode]]), an earlier index that
  * era's header ([[Header.fromNtN]]: Byron's with its epoch length `byronEpochSlots`, a
  * Shelley-based era's by [[Header.fromRaw]]). A refusal is the era decoder's, a typed
  * [[xyz.kd5ujc.scarab.ledger.ChainError]].
  */
final class AnnouncedHeaders(byronEpochSlots: BigInt) extends LeiosNotify.AnnouncementReader:
  def read(header: HfcHeader): Either[DomainError, Unit] = header match
    case HfcHeader.Supported(h) => Header.fromNtN(h, byronEpochSlots).void
    case other                  => LeiosHeader.byEra(other).void
