// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{DecodingFailure, ParsingFailure}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold as Leader

/** Configuration failures retain parsed inputs and source causes; text is rendered at presentation.
  */
enum ConfigProblem:
  case ActiveSlotsCoefficient(cause: Leader.Error)
  case FileMissing(role: String, file: String)
  case GenesisHashMismatch(role: String, file: String, expected: String, actual: String)
  case UnsupportedTopology
  case RemovedFlag(flag: String)
  case JsonSyntax(context: String, cause: ParsingFailure)
  case JsonField(context: String, field: String, cause: DecodingFailure)
  case MissingField(context: String, field: String)
  case InvalidTimestamp(value: String, cause: Throwable)
  case FractionalSlotMilliseconds(value: BigDecimal)
  case ByronGenesisRequired
  case IntegerText(context: String, value: String)
  case Base64(context: String, value: String, cause: Throwable)
  case Base58Address(value: String)
  case Hex(cause: Bytes.HexError)
  case ByteLength(context: String, expected: Int, actual: Int)
  case LedgerValue(cause: LedgerDecodeError)
  case Credential(value: String)
  case DRep(value: String)
  case DuplicateInjection(name: String)
  case CostModelLanguage(value: String)
  case NonPositive(context: String, value: BigDecimal)
  case ActiveSlotRange(value: BigDecimal)
  case EpochTooShort(epochLength: Long, randomnessWindow: BigInt)

  def message: String = this match
    case ActiveSlotsCoefficient(e) => e.message
    case _                         => toString
