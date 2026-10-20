// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{ParsingFailure, DecodingFailure}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor as BinaryCbor, Value}
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.state.{MirrorError, VocabError}
import xyz.kd5ujc.scarab.ledger.rules.TickFailure
import xyz.kd5ujc.scarab.protocol.Era

enum ForgeKeyProblem:
  case Json(cause: ParsingFailure)
  case Field(name: String, cause: DecodingFailure)
  case Hex(cause: Bytes.HexError)
  case EnvelopeType(expected: String, actual: String)
  case Cbor(cause: BinaryCbor.Error)
  case ExpectedByteString(actual: KeyShape)
  case Vrf(cause: Draft03Prover.Failure)
  case CertificateEnvelope(actual: KeyShape)
  case CertificateFields(actual: KeyShape)
  case FixedBytes(expected: Int, actual: KeyShape)
  case UnsignedInteger(actual: KeyShape)
  case Io(cause: Throwable)

enum ForgeAssemblyProblem:
  case Cbor(cause: BinaryCbor.Error)
  case TransactionArity(id: Bytes)
  case ValidityFlag
  case BodySize(actual: Long, maximum: BigInt)
  case BlockDecode(cause: ChainError)

enum ForgeLedgerProblem:
  case Vocabulary(cause: VocabError)
  case Tick(cause: TickFailure)
  case EpochPrecedes(epoch: BigInt, ledgerEpoch: BigInt)

enum ForgeProtocolProblem:
  case Version(actual: BigInt)
  case LeftConway
  case Bootstrap
  case InByron
  case Earlier(era: Era)
  case Mirror(cause: MirrorError)

/** Describe malformed key fields without retaining their contents. */
enum KeyShape:
  case Unsigned, Negative, Text, Boolean, Float, Null
  case Simple(value: Int)
  case Bytes(length: Int)
  case Array(length: Int)
  case Map(length: Int)
  case Tag(number: BigInt)

object KeyShape:
  def of(value: Value): KeyShape = value match
    case Value.UInt(_)           => KeyShape.Unsigned
    case Value.NInt(_)           => KeyShape.Negative
    case Value.ByteString(bytes) => KeyShape.Bytes(bytes.size)
    case Value.Text(_)           => KeyShape.Text
    case Value.Bool(_)           => KeyShape.Boolean
    case Value.Float(_, _)       => KeyShape.Float
    case Value.Null              => KeyShape.Null
    case Value.Simple(n)         => KeyShape.Simple(n)
    case Value.Arr(items)        => KeyShape.Array(items.size)
    case Value.Map(items)        => KeyShape.Map(items.size)
    case Value.Tag(number, _)    => KeyShape.Tag(number)
