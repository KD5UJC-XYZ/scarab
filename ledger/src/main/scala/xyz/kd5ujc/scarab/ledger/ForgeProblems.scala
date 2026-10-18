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
  case ExpectedByteString(actual: Value)
  case Vrf(cause: Draft03Prover.Failure)
  case CertificateEnvelope(actual: Value)
  case CertificateFields(actual: Value)
  case FixedBytes(expected: Int, actual: Value)
  case UnsignedInteger(actual: Value)
  case Io(cause: Throwable)

enum ForgeAssemblyProblem:
  case Cbor(cause: BinaryCbor.Error)
  case TransactionArity(id: Bytes)
  case ValidityFlag
  case BodySize(actual: Long, maximum: Int)
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
