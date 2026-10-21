// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.{Cbor, Value}

/** The envelope of an externally submitted transaction, before the ledger rules run. */
enum TransactionDecodeProblem:
  case Syntax(cause: Cbor.Error)
  case Ledger(cause: LedgerDecodeError)
  case Envelope(actual: Value)
  case ValidityFlag(actual: Value)
  case TaggedPayload(actual: Value)
  case EraOutOfRange(value: BigInt)
  case EraExpected(actual: Value)
  case EraEnvelope(actual: Value)

  def message: String = this match
    case Syntax(cause)        => cause.message
    case Ledger(cause)        => cause.message
    case Envelope(_)          => "expected [body, witnesses, is_valid, auxiliary_data]"
    case ValidityFlag(_)      => "is_valid is not a boolean"
    case TaggedPayload(_)     => "tag 24 does not hold a byte string"
    case EraOutOfRange(value) => s"era $value is not Conway (6)"
    case EraExpected(_)       => "era is not an integer"
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
