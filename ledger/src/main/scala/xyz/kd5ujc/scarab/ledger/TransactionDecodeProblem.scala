// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.{Cbor, DomainError, Node, Value}

/** The envelope of an externally submitted transaction, before the ledger rules run: where the
  * decoder of a submitted transaction fails on it.
  */
enum TransactionDecodeProblem extends DomainError:
  case Syntax(cause: Cbor.Error)
  case Ledger(cause: LedgerDecodeError)

  /** Tag 24 around something other than a byte string. */
  case TaggedPayload(actual: Value)

  /** Tag 24 around a chunked byte string, where `decodeBytes` reads a definite one. */
  case ChunkedPayload

  /** A transaction of a Shelley-based era not under tag 24 (CBOR-in-CBOR). */
  case NotCborInCbor(actual: Value)

  /** An era index past 255, which `decodeWord8` does not read. */
  case EraOutOfRange(value: BigInt)
  case EraExpected(actual: Value)

  /** An era index past the last era of `CardanoEras` ("decodeNS: invalid index"). */
  case UnknownEra(index: Int)

  /** Byron's payload is not the definite list of two that `enforceSize "MempoolPayload" 2` reads.
    */
  case ByronEnvelope(cause: Node.NotSized)

  /** Byron's payload names no constructor from 0 to 3 that `decodeWord8` reads. */
  case ByronConstructor(actual: Value)

  /** Not the definite list of two, era and transaction, that `enforceSize` reads. */
  case EraEnvelope(cause: Node.NotSized)

  def message: String = this match
    case Syntax(cause)        => cause.message
    case Ledger(cause)        => cause.message
    case TaggedPayload(_)     => "tag 24 does not hold a byte string"
    case ChunkedPayload       => "tag 24 holds a chunked byte string"
    case NotCborInCbor(_)     => "expected tag 24 (CBOR-in-CBOR)"
    case EraOutOfRange(value) => s"era index $value is past 255"
    case EraExpected(_)       => "era is not an integer"
    case UnknownEra(index)    => s"era index $index is past the last era"
    case ByronEnvelope(_)     => "expected Byron's [constructor, payload]"
    case ByronConstructor(_)  => "Byron's constructor is not one from 0 to 3"
    case EraEnvelope(_)       => "expected [era, transaction]"

enum MempoolProblem:
  case AllInputsSpent
  case UnelectedCommittee(voters: Vector[Credential])
  case Ledger(cause: LedgerFailure)
  case Rule(cause: PredicateFailure)
  case Malformed(cause: TransactionDecodeProblem)
  case Capacity(dimension: CapacityDimension, needed: BigInt, free: BigInt)
  case NoLedger(cause: MempoolLedgerProblem)

  def message: String = this match
    case AllInputsSpent => "All inputs are spent. Transaction has probably already been included"
    case UnelectedCommittee(voters) =>
      def credential(c: Credential): String = c match
        case Credential.Key(h)    => s"KeyHashObj (KeyHash {unKeyHash = \"${h.hex}\"})"
        case Credential.Script(h) => s"ScriptHashObj (ScriptHash \"${h.hex}\")"
      "Unelected committee members are not allowed to cast votes: " + voters
        .map(credential)
        .mkString("[", ",", "]")
    case Ledger(cause)    => cause.message
    case Rule(cause)      => cause.detail
    case Malformed(cause) => s"malformed transaction: ${cause.message}"
    case Capacity(dimension, needed, free) =>
      s"mempool full: $needed ${dimension.unit} needed, $free free"
    case NoLedger(cause) => s"no Conway ledger state: ${cause.message}"

/** The component of the mempool's capacity vector that a transaction does not fit in (the first, in
  * this order, when several are exceeded).
  */
enum CapacityDimension(val unit: String):
  case Bytes extends CapacityDimension("bytes")
  case ExUnitsMemory extends CapacityDimension("execution-unit memory")
  case ExUnitsSteps extends CapacityDimension("execution-unit steps")
  case RefScriptBytes extends CapacityDimension("reference-script bytes")

enum MempoolLedgerProblem:
  case InByron
  case Earlier(era: xyz.kd5ujc.scarab.protocol.Era)
  case Refused(cause: xyz.kd5ujc.scarab.ledger.state.MirrorError)
  def message: String = this match
    case InByron        => "the chain is in Byron"
    case Earlier(era)   => s"the chain is in $era, before Conway"
    case Refused(cause) => s"the ledger state cannot be read: $cause"
