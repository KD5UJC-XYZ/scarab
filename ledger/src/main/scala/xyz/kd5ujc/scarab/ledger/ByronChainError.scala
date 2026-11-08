// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.byron.ByronError
import xyz.kd5ujc.scarab.ledger.state.VocabError
import xyz.kd5ujc.scarab.crypto.witness.WitnessInputError

/** PBFT and Byron linkage failures, shared by consensus and block validation. */
enum ByronChainError:
  case ProtocolMagic(actual: BigInt, expected: BigInt)
  case PreviousHash(actual: Option[Bytes], expected: Bytes)
  case MissingConsensusData
  case UnknownDelegate(delegate: Bytes)
  case Vocabulary(cause: VocabError)
  case SlotBeforeLastSigned(slot: BigInt, last: BigInt)
  case SignatureThreshold(key: Bytes, signed: BigInt, window: BigInt, threshold: BigInt)
  case Body(cause: LedgerDecodeError)
  case Ledger(cause: ByronError)
  case InitialBoundaryRequired

  /** A boundary block after the first names the genesis, not the tip `expected`
    * (`ChainValidationExpectedHeaderHash`).
    */
  case GenesisPredecessor(expected: Bytes)

  /** A block's slot is below the least the tip allows (`minimumNextSlotNo`). */
  case SlotOrder(slot: BigInt, minimum: BigInt)

  /** A boundary block's slot is not a multiple of the epoch's slots (`UnexpectedEBBInSlot`). */
  case BoundarySlot(slot: BigInt, epochSlots: BigInt)
  case SignatureRejected
  case SignatureInput(cause: WitnessInputError)

  def message: String = this match
    case ProtocolMagic(a, e)  => s"protocol magic $a differs from the genesis $e"
    case PreviousHash(a, e)   => s"previous hash ${a.fold("none")(_.hex)} is not the tip ${e.hex}"
    case MissingConsensusData => "main header without consensus data"
    case UnknownDelegate(d)   => s"signer ${d.hex.take(16)} is not a delegate of any genesis key"
    case Vocabulary(e)        => e.toString
    case SlotBeforeLastSigned(s, l) => s"slot $s is below the last signed slot $l"
    case SignatureThreshold(k, s, w, t) =>
      s"genesis key ${k.hex.take(16)} signed $s of the last $w blocks, above the threshold $t"
    case Body(e)   => e.message
    case Ledger(e) => e.message
    case InitialBoundaryRequired =>
      "the chain must start with a boundary block that names the genesis"
    case GenesisPredecessor(e) => s"boundary block names the genesis, not the tip ${e.hex}"
    case SlotOrder(s, m)       => s"slot $s is below the least next slot $m"
    case BoundarySlot(s, n)    => s"boundary block at slot $s, not a multiple of $n"
    case SignatureRejected     => "block signature rejected"
    case SignatureInput(e)     => s"signature input failed: $e"
