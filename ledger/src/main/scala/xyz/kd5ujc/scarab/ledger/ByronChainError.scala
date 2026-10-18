// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.byron.ByronError
import xyz.kd5ujc.scarab.ledger.state.VocabError
import xyz.kd5ujc.scarab.crypto.witness.{VerificationError, WitnessInputError}

/** PBFT and Byron linkage failures, shared by consensus and block validation. */
enum ByronChainError:
  case ProtocolMagic(actual: BigInt, expected: BigInt)
  case PreviousHash(actual: Option[Bytes], expected: Bytes)
  case SlotOutsideEpoch(slot: BigInt, epochSlots: BigInt)
  case MissingConsensusData
  case UnknownDelegate(delegate: Bytes)
  case Vocabulary(cause: VocabError)
  case SlotBeforeLastSigned(slot: BigInt, last: BigInt)
  case SignatureThreshold(key: Bytes, signed: BigInt, window: BigInt, threshold: Long)
  case Body(cause: LedgerDecodeError)
  case Ledger(cause: ByronError)
  case InitialBoundaryRequired
  case BoundaryOrder(epoch: BigInt, previousEpoch: BigInt, previousBoundary: Boolean)
  case SlotOrder(slot: BigInt, previous: BigInt)
  case SignatureRejected
  case SignatureInput(cause: WitnessInputError)
  case SignatureFailure(cause: VerificationError)

  def message: String = this match
    case ProtocolMagic(a, e) => s"protocol magic $a differs from the genesis $e"
    case PreviousHash(a, e) => s"previous hash ${a.fold("none")(_.hex)} is not the tip ${e.hex}"
    case SlotOutsideEpoch(s, n) => s"slot $s outside a $n-slot epoch"
    case MissingConsensusData => "main header without consensus data"
    case UnknownDelegate(d) => s"signer ${d.hex.take(16)} is not a delegate of any genesis key"
    case Vocabulary(e) => e.toString
    case SlotBeforeLastSigned(s, l) => s"slot $s is below the last signed slot $l"
    case SignatureThreshold(k, s, w, t) => s"genesis key ${k.hex.take(16)} signed $s of the last $w blocks, above the threshold $t"
    case Body(e) => e.message
    case Ledger(e) => e.message
    case InitialBoundaryRequired => "the chain must start with the epoch 0 boundary"
    case BoundaryOrder(e, p, b) => s"boundary block of epoch $e after epoch $p${if b then " boundary" else ""}"
    case SlotOrder(s, p) => s"slot $s does not follow $p"
    case SignatureRejected => "block signature rejected"
    case SignatureInput(e) => s"signature input failed: $e"
    case SignatureFailure(e) => s"signature check failed: $e"
