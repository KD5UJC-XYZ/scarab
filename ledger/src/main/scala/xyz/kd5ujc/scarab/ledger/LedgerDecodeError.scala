// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Show
import cats.syntax.show.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.codec.{Cbor, Value}
import xyz.kd5ujc.scarab.ledger.byron.{ByronGenesisKey, ByronVersion, DelegationError}

/** Failures while reading ledger values. A [[DecodeSite]] identifies the place; cases identify
  * failures.
  */
enum LedgerDecodeError extends DomainError:
  case Protocol(cause: xyz.kd5ujc.scarab.protocol.ProtocolError)
  case Transaction(id: xyz.kd5ujc.scarab.codec.Bytes, cause: LedgerDecodeError)
  case Syntax(cause: Cbor.Error)
  case Expected(site: DecodeSite, expected: LedgerDecodeError.Shape, actual: Value)
  case ExpectedTag(site: DecodeSite, expected: BigInt, actual: Value)
  case ByteLength(site: DecodeSite, expected: Int, actual: Int)
  case ArrayLength(site: DecodeSite, expected: Int, actual: Int)

  /** A transaction output in none of the forms the era reads ([[TxOut.Forms]]). */
  case OutputForm(forms: TxOut.Forms, actual: Value)
  case Empty(site: DecodeSite)
  case MissingField(site: DecodeSite, field: DecodeSite)
  case UnknownFields(site: DecodeSite, fields: Set[Int])

  /** A key of a sparse record that names no field of it (`invalidKey`, `Coders.hs`). */
  case UnknownKey(site: DecodeSite, key: BigInt)
  case DuplicateKey(site: DecodeSite)
  case UnsupportedCostModelLanguage(language: Int)
  case CostModelLength(language: Int, expected: Int, actual: Int)
  case DuplicateElement(site: DecodeSite)
  case Zero(site: DecodeSite)
  case IndefiniteLength(site: DecodeSite)
  case ZeroDenominator(site: DecodeSite)
  case RationalOutOfBounds(site: DecodeSite, numerator: BigInt, denominator: BigInt)
  case UnknownTag(site: DecodeSite, tag: BigInt)
  case TaggedArity(site: DecodeSite, tag: BigInt, actual: Int)
  case AtMostOne(site: DecodeSite, actual: Int)
  case NumberOutOfRange(site: DecodeSite, value: BigInt, minimum: BigInt, maximum: BigInt)
  case AddressLength(site: DecodeSite, expected: Int, actual: Int)
  case AddressHeader(actual: Int)
  case RewardAccountHeader(actual: Int)
  case Pointer(cause: Ptr.Failure)
  case AssetNameLength(site: DecodeSite, actual: Int)
  case ZeroQuantity(site: DecodeSite)
  case MultiAssetNotAllowed(site: DecodeSite)
  case MultiAssetTooBig(site: DecodeSite, assets: Int, policies: Int)
  case NativeScript(cause: xyz.kd5ujc.scarab.ledger.NativeScript.DecodeError)
  case Voting(cause: VotingProcedures.DecodeError)
  case Version(cause: ByronVersion.Error)
  case Delegation(cause: DelegationError)
  case CrcMismatch(expected: BigInt, actual: BigInt)
  case EmptyInputsOrOutputs
  case DuplicateGenesisAddress
  case GenesisMissingField(key: ByronGenesisKey)
  case GenesisExpectedNumber(key: ByronGenesisKey)
  case ProposalEpochMismatch(target: BigInt, epoch: BigInt, beforePointOfNoReturn: Boolean)

  case ByronLayout
  case ByronBlockUnexpected
  case BodyWitnessCount(bodies: Int, witnesses: Int)
  case TransactionIndex(actual: Value, bound: Int)
  case LeiosCertificate(cause: xyz.kd5ujc.scarab.crypto.bls.BlsError)

  def message: String = this match
    case Syntax(cause)               => cause.message
    case Protocol(cause)             => cause.message
    case Transaction(id, cause)      => s"tx ${id.hex.take(16)}: ${cause.message}"
    case Expected(site, expected, _) => s"${site.show}: expected $expected"
    case ExpectedTag(site, tag, _)   => s"${site.show}: expected tag $tag"
    case NativeScript(cause)         => cause.message
    case Voting(cause)               => cause.message
    case Version(cause)              => cause.message
    case Delegation(cause)           => cause.message
    case _                           => this.show

  /** The rendering of [[Show]], so that a cause wrapped by another error, or logged, shows the
    * sites by their labels and not by the name of the Scala value.
    */
  override def toString: String = this.show

object LedgerDecodeError:
  /** The case and its fields as a case class renders them, except that a site is shown by its label
    * ([[DecodeSite]]) and a Byron genesis key by its key. The errors that carry a
    * [[LedgerDecodeError]] ([[LedgerDecodeError.NativeScript]], [[LedgerDecodeError.Voting]]) are
    * rendered the same way.
    */
  given Show[LedgerDecodeError] = Show.show(repr)

  private def repr(x: Any): String = x match
    case site: DecodeSite     => site.show
    case key: ByronGenesisKey => key.show
    case e: (LedgerDecodeError | xyz.kd5ujc.scarab.ledger.NativeScript.DecodeError |
          VotingProcedures.DecodeError) =>
      fields(e)
    case other => other.toString

  private def fields(e: Product): String =
    if e.productArity == 0 then e.productPrefix
    else e.productIterator.map(repr).mkString(s"${e.productPrefix}(", ",", ")")

  enum Shape:
    case Unsigned, Integer, Bytes, Text, Boolean, Null, Array, Map, ArrayOrMap, Metadatum
