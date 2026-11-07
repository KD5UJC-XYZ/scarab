// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.byron.{ByronBodyProof, ByronError, ByronTransport}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

/** Transport failures precede ChainDB admission and are never tentative-block forgiveness cases. */
enum BlockTransportError extends DomainError:
  case Decode(cause: ChainError)
  case WrongEra(expected: Era, actual: Era)
  case WrongHeader(expected: ChainSync.Point, actual: ChainSync.Point)
  case WrongBodyHash(expected: Bytes, actual: Bytes)
  case TransactionDecode(id: Bytes, cause: LedgerDecodeError)
  case ByronBodyDecode(cause: LedgerDecodeError)
  case ByronBodyProof(cause: ByronError)

  /** A Dijkstra block whose certificate disagrees with its header's flag, or that carries a
    * certificate and transactions ([[LeiosBlock.Fault]]).
    */
  case LeiosBody(fault: LeiosBlock.Fault)

  def message: String = this match
    case Decode(cause)                 => cause.message
    case WrongEra(expected, actual)    => s"fetched $actual block for $expected header"
    case WrongHeader(expected, actual) => s"fetched header $actual differs from announced $expected"
    case WrongBodyHash(expected, actual) =>
      s"fetched body hash ${actual.hex} differs from announced ${expected.hex}"
    case TransactionDecode(id, cause) =>
      s"fetched transaction ${id.hex} does not decode: ${cause.message}"
    case ByronBodyDecode(cause) => s"fetched Byron body does not decode: ${cause.message}"
    case ByronBodyProof(cause)  => cause.message
    case LeiosBody(fault)       => s"fetched Dijkstra body does not match its header: $fault"

/** The pinned BlockFetch client's point/body checks, before storage or ledger selection.
  * Shelley/Ledger/Block.hs:211 checks the body hash, not its declared size. Byron delegates to
  * abobMatchesBody (Byron/API/Common.hs:145–161): main-block payload proofs, no EBB body proof.
  * Original bytes are decoded and hashed; this boundary never re-encodes an announced header.
  */
object BlockTransport:
  def check(announced: Header, raw: Bytes): Either[BlockTransportError, Block] =
    for
      block <- (announced match
        case byron: Header.Byron => Block.decode(raw, byron.epochSlots)
        case _                   => Block.decodeShelley(raw)
      ).leftMap(BlockTransportError.Decode(_))
      _ <- Either.cond(
        block.era == announced.era,
        (),
        BlockTransportError.WrongEra(announced.era, block.era)
      )
      _ <- Either.cond(
        block.header.point == announced.point,
        (),
        BlockTransportError.WrongHeader(announced.point, block.header.point)
      )
      _ <- (announced, block) match
        case (header: Header.Shelley, body: Block.Shelley) =>
          // Native TxSeq decoding constructs transaction bodies, witnesses and auxiliary data
          // before blockMatchesHeader. A malformed transaction is not a ledger-invalid trap.
          body.transactions.traverse_(tx =>
            Tx.fromBlock(tx, body.era).leftMap(BlockTransportError.TransactionDecode(tx.id, _))
          ) *> Either.cond(
            body.bodyHash == header.bodyHash,
            (),
            BlockTransportError.WrongBodyHash(header.bodyHash, body.bodyHash)
          )
        // `blockMatchesHeader` of Praos2 (ouroboros-consensus 2416493b
        // `Shelley/Ledger/Block.hs:224-231`): the body hash, the certificate flag, and no
        // certificate with transactions; the body size is BBODY's. The transactions were decoded
        // whole with the block ([[LeiosBlock]]).
        case (header: Header.Shelley, body: Block.Dijkstra) =>
          Either.cond(
            body.bodyHash == header.bodyHash,
            (),
            BlockTransportError.WrongBodyHash(header.bodyHash, body.bodyHash)
          ) *> body.block.faults
            .collectFirst {
              case f @ (LeiosBlock.Fault.CertificateFlagDiffers(_, _) |
                  LeiosBlock.Fault.CertificateWithTransactions(_)) =>
                BlockTransportError.LeiosBody(f)
            }
            .toLeft(())
        case (header: Header.Byron, body: Block.Byron) =>
          ByronTransport
            .check(body.body, body.extra, header.boundary)
            .leftMap(BlockTransportError.ByronBodyDecode(_)) *>
            (if header.boundary then Right(())
             else
               ByronBodyProof
                 .check(header, body.body)
                 .leftMap(BlockTransportError.ByronBodyProof(_)))
        case _ => Right(())
    yield block
