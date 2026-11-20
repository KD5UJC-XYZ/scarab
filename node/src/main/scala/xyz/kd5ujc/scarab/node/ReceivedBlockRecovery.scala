// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{Block, BlockTransportError, PeerError, StorageError}
import xyz.kd5ujc.scarab.storage.{ReceivedBlocks, VolatileStore}

/** Recover actual checked originals, without manufacturing a live adding client. */
private[scarab] object ReceivedBlockRecovery:
  enum Problem extends DomainError:
    case MetadataMismatch(expected: BlockRef, actual: BlockRef)

  /** The start-up check of the received log, run on each record as the log is opened: [[checked]]
    * (the block decodes, its header names the record's point and its body is the header's), its
    * refusal told apart as a block that does not decode or one that decodes and is corrupt.
    */
  def startUp(byronEpochSlots: BigInt): ReceivedBlocks.Check =
    (expected, raw) => refusing(expected, raw, byronEpochSlots).void

  def checked(
      expected: BlockRef,
      raw: Bytes,
      byronEpochSlots: BigInt
  ): Either[StorageError, BlockPool.Delivered] =
    refusing(expected, raw, byronEpochSlots).leftMap {
      case VolatileStore.Refusal.Undecodable(error) => error
      case VolatileStore.Refusal.Corrupt(error)     => error
    }

  /** [[checked]], with why it refuses a block: the block, or a transaction or Byron body in it,
    * does not decode; or it decodes, and its header is not the record's, its body does not match
    * its header, or its header's fields are not the punishment view's.
    */
  private def refusing(
      expected: BlockRef,
      raw: Bytes,
      byronEpochSlots: BigInt
  ): Either[VolatileStore.Refusal, BlockPool.Delivered] =
    for
      block <- Block
        .decode(raw, byronEpochSlots)
        .leftMap(e => VolatileStore.Refusal.Undecodable(StorageError.Corrupt("received block", e)))
      header = block.header
      actual = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
      _ <- Either.cond(
        actual == expected,
        (),
        VolatileStore.Refusal.Corrupt(
          StorageError.Corrupt(
            "received block metadata",
            Problem.MetadataMismatch(expected, actual)
          )
        )
      )
      delivered <- BlockPool.Delivered
        .checked(header, raw)
        .leftMap {
          case PeerError.ProtocolProblem.FetchedBlockIntegrity(cause) =>
            transport(cause, StorageError.Corrupt("received block transport", cause))
          case PeerError.ProtocolProblem.InvalidPunishmentHeader(cause) =>
            VolatileStore.Refusal.Corrupt(StorageError.Corrupt("received block header", cause))
          case other =>
            VolatileStore.Refusal.Corrupt(
              StorageError.Corrupt("received block", PeerError.Protocol(other))
            )
        }
    yield delivered

  /** A refusal of the transport check: a part of the block that does not decode, or a block that
    * decodes and does not match its header.
    */
  private def transport(cause: BlockTransportError, error: StorageError): VolatileStore.Refusal =
    cause match
      case BlockTransportError.Decode(_) | BlockTransportError.TransactionDecode(_, _) |
          BlockTransportError.ByronBodyDecode(_) =>
        VolatileStore.Refusal.Undecodable(error)
      case BlockTransportError.WrongEra(_, _) | BlockTransportError.WrongHeader(_, _) |
          BlockTransportError.WrongBodyHash(_, _) | BlockTransportError.ByronBodyProof(_) |
          BlockTransportError.LeiosBody(_) =>
        VolatileStore.Refusal.Corrupt(error)
