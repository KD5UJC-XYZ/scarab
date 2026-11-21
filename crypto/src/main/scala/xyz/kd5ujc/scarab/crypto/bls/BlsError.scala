// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import cats.data.NonEmptyVector

/** What a BLS operand is, for error reporting. */
enum Operand:
  case Seed, SecretKey, VerificationKey, Signature, ProofOfPossession

/** Closed set of failures of the MinSig BLS layer. Every public function of this package returns
  * one of these or a [[VerificationError]] (never a message string, never an exception).
  */
enum BlsError:
  /** An operand has the wrong number of bytes. */
  case WrongLength(operand: Operand, expected: Int, actual: Int)

  /** The compression flag (top bit of the first byte) is clear: an uncompressed or garbage form. */
  case NotCompressed(operand: Operand)

  /** Not a valid compressed point: non-canonical field element, not on the curve, or bad flags. */
  case MalformedPoint(operand: Operand)

  /** On the curve but outside the prime-order subgroup. */
  case NotInSubgroup(operand: Operand)

  /** The identity element, which the scheme forbids for this operand. */
  case Infinity(operand: Operand)

  /** The signing key is zero or not below the group order. */
  case InvalidScalar

  /** Aggregation of an empty list. */
  case EmptyAggregate

  /** The sum of the (individually finite) inputs is the identity. */
  case AggregateIsInfinity(operand: Operand)

  /** A signature does not verify. */
  case SignatureInvalid

  /** A proof of possession does not verify against its key. */
  case ProofOfPossessionInvalid

  /** The native library failed unexpectedly (should be unreachable on validated operands). */
  case Backend(cause: Throwable)

  /** A seat index above the 16-bit range of `voter_id`. */
  case SeatOutOfRange(index: Int)

  /** The committee is larger than a 16-bit seat index can address. */
  case CommitteeTooLarge(size: Int)

  /** No vote to aggregate. */
  case NoSigners

  /** A vote or signature index names a seat that is not in the committee. */
  case SignerNotInCommittee(seat: SeatId)

  /** A vote came from a seat whose key is absent. */
  case SeatHasNoKey(seat: SeatId)

  /** The wire value is not a well-formed vote or certificate. */
  case MalformedCbor(fault: CborFault)

/** `VerificationError` (cardano-base c1e1ed8d `cardano-crypto-leios/src/Cardano/Crypto/
  * Leios.hs:265-282`): why `verifyLeiosCert` refuses a certificate, checked in the order of the
  * cases. A failure of the native library is no such verdict; it stays [[BlsError.Backend]].
  */
enum VerificationError:
  /** The signer bitfield is not `ceil(seats / 8)` bytes. */
  case MalformedSigners

  /** The committee has more seats than a `LeiosSeatId` (a `Word16`) can name. */
  case MalformedCommittee(seats: Int)

  /** The aggregate signature does not verify against the signers' summed key, or the keys do not
    * sum to a key (none, or the identity).
    */
  case InvalidSignature

  /** The signers' summed weight is below the threshold. */
  case InsufficientWeight(received: Weight)

  /** Set bits select seats that have no voting key, all of them listed in seat order. */
  case SignerWithoutKey(seats: NonEmptyVector[SeatId])

/** Why a CBOR value is not a well-formed `leios_vote` or `leios_certificate`. */
enum CborFault:
  /** Not decodable CBOR, or bytes remain after the item. */
  case Syntax(cause: xyz.kd5ujc.scarab.codec.Cbor.Error)
  case NonCanonicalEncoding
  case NotAnArray
  case WrongArity(expected: Int, actual: Int)
  case FieldNotBytes(index: Int)
  case FieldNotUnsigned(index: Int)
  case HashNotThirtyTwoBytes(actual: Int)
  case SeatAbove16Bits
  case SignersTooLong(actual: Int)
