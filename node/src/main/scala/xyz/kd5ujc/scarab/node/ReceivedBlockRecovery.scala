// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{Block, PeerError, StorageError}

/** Recover actual checked originals, without manufacturing a live adding client. */
private[node] object ReceivedBlockRecovery:
  enum Problem extends DomainError:
    case MetadataMismatch(expected: BlockRef, actual: BlockRef)

  def checked(
      expected: BlockRef,
      raw: Bytes,
      byronEpochSlots: BigInt
  ): Either[StorageError, BlockPool.Delivered] =
    for
      block <- Block.decode(raw, byronEpochSlots).leftMap(StorageError.Corrupt("received block", _))
      header = block.header
      actual = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
      _ <- Either.cond(
        actual == expected,
        (),
        StorageError.Corrupt("received block metadata", Problem.MetadataMismatch(expected, actual))
      )
      delivered <- BlockPool.Delivered
        .checked(header, raw)
        .leftMap {
          case PeerError.ProtocolProblem.FetchedBlockIntegrity(cause) =>
            StorageError.Corrupt("received block transport", cause)
          case PeerError.ProtocolProblem.InvalidPunishmentHeader(cause) =>
            StorageError.Corrupt("received block header", cause)
          case other => StorageError.Corrupt("received block", PeerError.Protocol(other))
        }
    yield delivered
