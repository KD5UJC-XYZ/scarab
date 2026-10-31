// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import java.math.BigInteger
import xyz.kd5ujc.scarab.codec.Bytes
import cats.syntax.either.*
import supranational.blst.{BLST_ERROR, P1, P1_Affine, P2, P2_Affine, SecretKey as BlstSecretKey}

/** A voting secret key: a non-zero scalar below the group order. The value never appears in
  * `toString`. Equality is not provided on purpose (compare derived [[VerificationKey]]s).
  */
final class SecretKey private (private val scalar: Bytes):
  /** The 32-byte big-endian scalar (the form in a `BlsSigningKey_...` text envelope). */
  def toBytes: Bytes = scalar
  private[bls] def blst: BlstSecretKey =
    val sk = new BlstSecretKey()
    sk.from_bendian(scalar.toArray)
    sk
  override def toString: String = "SecretKey(<secret>)"

object SecretKey:
  val size: Int = 32

  /** The order of the prime-order subgroup (r of BLS12-381). */
  private val order = new BigInteger(
    "73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001",
    16
  )

  /** Key generation exactly as cardano-base: `blst_keygen(ikm = seed, info = "")`. */
  def generate(seed: Seed): Either[BlsError, SecretKey] =
    MinSig
      .guard {
        val sk = new BlstSecretKey()
        sk.keygen(seed.bytes.toArray)
        sk.to_bendian()
      }
      .flatMap(raw => fromBytes(Bytes.wrap(raw)))

  /** Strict decode of a big-endian scalar: 32 bytes, non-zero, below the group order. */
  def fromBytes(bytes: Bytes): Either[BlsError, SecretKey] =
    if bytes.size != size then Left(BlsError.WrongLength(Operand.SecretKey, size, bytes.size))
    else
      val v = new BigInteger(1, bytes.toArray)
      if v.signum == 0 || v.compareTo(order) >= 0 then Left(BlsError.InvalidScalar)
      else Right(new SecretKey(bytes.compact))

/** A voting verification key: a finite point of the G2 subgroup, 96 bytes compressed (MinSig). */
final class VerificationKey private (val bytes: Bytes, private[bls] val point: P2_Affine):
  override def equals(that: Any): Boolean = that match
    case o: VerificationKey => bytes == o.bytes
    case _                  => false
  override def hashCode(): Int = bytes.hashCode
  override def toString: String = s"VerificationKey(${bytes.hex})"

object VerificationKey:
  val size: Int = 96

  /** Strict decode: compressed form only, on the curve, in the subgroup, not the identity. */
  def fromBytes(bytes: Bytes): Either[BlsError, VerificationKey] =
    MinSig.decodeG2(Operand.VerificationKey, bytes).flatMap { p =>
      if p.is_inf() then Left(BlsError.Infinity(Operand.VerificationKey))
      else Right(new VerificationKey(bytes.compact, p))
    }

  /** For points the caller has already established to be finite and in the subgroup. */
  private[bls] def unchecked(bytes: Bytes, point: P2_Affine): VerificationKey =
    new VerificationKey(bytes, point)

  def derive(sk: SecretKey): Either[BlsError, VerificationKey] =
    MinSig
      .guard(new P2(sk.blst).compress())
      .flatMap(raw => fromBytes(Bytes.wrap(raw)))

/** A signature: a finite point of the G1 subgroup, 48 bytes compressed (MinSig). */
final class Signature private (val bytes: Bytes, private[bls] val point: P1_Affine):
  override def equals(that: Any): Boolean = that match
    case o: Signature => bytes == o.bytes
    case _            => false
  override def hashCode(): Int = bytes.hashCode
  override def toString: String = s"Signature(${bytes.hex})"

object Signature:
  val size: Int = 48

  /** Strict decode. The identity is rejected here (cardano-base rejects it at verify/aggregate: the
    * accepted set of certificates is the same).
    */
  def fromBytes(bytes: Bytes): Either[BlsError, Signature] =
    MinSig.decodeG1(Operand.Signature, bytes).flatMap { p =>
      if p.is_inf() then Left(BlsError.Infinity(Operand.Signature))
      else Right(new Signature(bytes.compact, p))
    }

  private[bls] def fromPoint(p: P1_Affine): Either[BlsError, Signature] =
    MinSig.guard(p.compress()).flatMap(raw => fromBytes(Bytes.wrap(raw)))

/** A proof of possession: the signature of the key's own compressed encoding under the `BLS_POP_`
  * tag. 48 bytes (the Dijkstra and cardano-base form; the older Rust specification's 96-byte pair
  * is a different construction).
  */
final class ProofOfPossession private (val bytes: Bytes, private[bls] val point: P1_Affine):
  override def equals(that: Any): Boolean = that match
    case o: ProofOfPossession => bytes == o.bytes
    case _                    => false
  override def hashCode(): Int = bytes.hashCode
  override def toString: String = s"ProofOfPossession(${bytes.hex})"

object ProofOfPossession:
  val size: Int = 48

  def fromBytes(bytes: Bytes): Either[BlsError, ProofOfPossession] =
    MinSig.decodeG1(Operand.ProofOfPossession, bytes).flatMap { p =>
      if p.is_inf() then Left(BlsError.Infinity(Operand.ProofOfPossession))
      else Right(new ProofOfPossession(bytes.compact, p))
    }

/** BLS12-381 MinSig (keys in G2, signatures in G1) with the IETF "POP" ciphersuite, as
  * cardano-base's `BLS12381MinSigDSIGN` (`Cardano.Crypto.DSIGN.BLS12381.Internal`) and CIP-164.
  *
  * Pure from the caller's side: every function is a total function of its arguments returning a
  * [[BlsError]] on failure. blst is called through jblst (ADR 0025); its handles are used as local
  * accumulators only and never shared. The native code is not audited by this repository and is not
  * guaranteed constant-time in this binding; do not sign with real long-lived keys from this code.
  */
object MinSig:
  /** `BLS_SIG_` + hash-to-curve suite of the signature group (G1) + `_POP_` (RFC draft 06, 4.2.3).
    */
  val signatureDst: String = "BLS_SIG_BLS12381G1_XMD:SHA-256_SSWU_RO_POP_"
  val possessionDst: String = "BLS_POP_BLS12381G1_XMD:SHA-256_SSWU_RO_POP_"

  /** The only place that turns a native failure (an exception) into a value. */
  private[bls] def guard[A](body: => A): Either[BlsError, A] =
    Either.catchNonFatal(body).leftMap(BlsError.Backend(_))

  private def compressed(operand: Operand, bytes: Bytes, size: Int): Either[BlsError, Unit] =
    if bytes.size != size then Left(BlsError.WrongLength(operand, size, bytes.size))
    else if !bytes.headOption.exists(b => (b & 0x80) != 0) then
      Left(BlsError.NotCompressed(operand))
    else Right(())

  private[bls] def decodeG1(operand: Operand, bytes: Bytes): Either[BlsError, P1_Affine] =
    compressed(operand, bytes, 48).flatMap { _ =>
      guard(new P1_Affine(bytes.toArray)).left
        .map(_ => BlsError.MalformedPoint(operand))
        .flatMap { p =>
          if p.in_group() then Right(p) else Left(BlsError.NotInSubgroup(operand))
        }
    }

  private[bls] def decodeG2(operand: Operand, bytes: Bytes): Either[BlsError, P2_Affine] =
    compressed(operand, bytes, 96).flatMap { _ =>
      guard(new P2_Affine(bytes.toArray)).left
        .map(_ => BlsError.MalformedPoint(operand))
        .flatMap { p =>
          if p.in_group() then Right(p) else Left(BlsError.NotInSubgroup(operand))
        }
    }

  /** Sign `message` (the raw bytes, no augmentation) under the vote tag. Deterministic. */
  def sign(sk: SecretKey, message: Bytes): Either[BlsError, Signature] =
    signUnder(sk, message, signatureDst).flatMap(Signature.fromPoint)

  /** Sign under an explicit tag. Exists to test that another tag fails; production code uses
    * [[sign]] and [[provePossession]].
    */
  private[bls] def signUnder(
      sk: SecretKey,
      message: Bytes,
      dst: String
  ): Either[BlsError, P1_Affine] =
    // no augmentation: the overload without it, not a null array, which the JNI glue of blst v0.3.17
    // reads (issue 651)
    guard(new P1().hash_to(message.toArray, dst).sign_with(sk.blst).to_affine())

  def verify(vk: VerificationKey, message: Bytes, sig: Signature): Either[BlsError, Unit] =
    verifyUnder(vk, message, sig.point, signatureDst)

  private[bls] def verifyUnder(
      vk: VerificationKey,
      message: Bytes,
      sig: P1_Affine,
      dst: String
  ): Either[BlsError, Unit] =
    guard(sig.core_verify(vk.point, true, message.toArray, dst)).flatMap { r =>
      if r == BLST_ERROR.BLST_SUCCESS then Right(()) else Left(BlsError.SignatureInvalid)
    }

  /** Proof of possession: sign the key's compressed encoding under the `BLS_POP_` tag. */
  def provePossession(sk: SecretKey): Either[BlsError, ProofOfPossession] =
    for
      vk <- VerificationKey.derive(sk)
      p <- signUnder(sk, vk.bytes, possessionDst)
      raw <- guard(p.compress())
      pop <- ProofOfPossession.fromBytes(Bytes.wrap(raw))
    yield pop

  def verifyPossession(vk: VerificationKey, pop: ProofOfPossession): Either[BlsError, Unit] =
    verifyUnder(vk, vk.bytes, pop.point, possessionDst).left.map(_ =>
      BlsError.ProofOfPossessionInvalid
    )

  /** Sum of signatures (all over the same message for same-message aggregation). Rejects an empty
    * list and an identity sum, like cardano-base's `aggregateSigsDSIGN`.
    */
  def aggregateSignatures(sigs: Vector[Signature]): Either[BlsError, Signature] = sigs match
    case first +: rest =>
      guard {
        // `add` does not repeat the subgroup check that `aggregate` runs on every call: the
        // operands were validated when decoded.
        rest.foldLeft(first.point.to_jacobian())((acc, s) => acc.add(s.point)).to_affine()
      }.flatMap { p =>
        if p.is_inf() then Left(BlsError.AggregateIsInfinity(Operand.Signature))
        else Signature.fromPoint(p)
      }
    case _ => Left(BlsError.EmptyAggregate)

  /** Sum of verification keys. Only sound after each key's proof of possession was checked
    * (rogue-key defence); [[Committee]] does that once.
    */
  def aggregateKeys(keys: Vector[VerificationKey]): Either[BlsError, VerificationKey] = keys match
    case first +: rest =>
      guard {
        rest.foldLeft(first.point.to_jacobian())((acc, k) => acc.add(k.point)).to_affine()
      }.flatMap { p =>
        // a sum of subgroup points is in the subgroup; only the identity needs rejecting
        if p.is_inf() then Left(BlsError.AggregateIsInfinity(Operand.VerificationKey))
        else guard(VerificationKey.unchecked(Bytes.wrap(p.compress()), p))
      }
    case _ => Left(BlsError.EmptyAggregate)

  /** Same-message aggregate verification: one pairing check against the summed key. */
  def verifyAggregate(
      keys: Vector[VerificationKey],
      message: Bytes,
      aggregate: Signature
  ): Either[BlsError, Unit] =
    aggregateKeys(keys).flatMap(verify(_, message, aggregate))
end MinSig
