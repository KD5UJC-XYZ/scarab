// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import cats.syntax.either.*
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.{
  Blake2bDigest,
  KeccakDigest,
  RIPEMD160Digest,
  SHA256Digest,
  SHA3Digest
}
import org.bouncycastle.math.ec.{ECAlgorithms, ECCurve, ECPoint}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.{
  StrictEd25519,
  VerificationError,
  VerificationResult,
  WitnessInputError
}

/** The hashes of `PlutusCore/Crypto/Hash.hs` (plutus 1.70.0.0 `507cab8c`, lines 37-62), which take
  * any byte string and have no failure, through Bouncy Castle's digests. `keccak_256` is Keccak
  * with its original padding, not SHA3-256.
  */
object Hashes:
  private def digest(d: Digest, input: Bytes): Bytes =
    val out = new Array[Byte](d.getDigestSize)
    d.update(input.toArray, 0, input.size)
    d.doFinal(out, 0)
    Bytes.wrap(out)

  def sha2_256(input: Bytes): Bytes = digest(new SHA256Digest(), input)
  def sha3_256(input: Bytes): Bytes = digest(new SHA3Digest(256), input)
  def blake2b_224(input: Bytes): Bytes = digest(new Blake2bDigest(224), input)
  def blake2b_256(input: Bytes): Bytes = digest(new Blake2bDigest(256), input)
  def keccak_256(input: Bytes): Bytes = digest(new KeccakDigest(256), input)
  def ripemd_160(input: Bytes): Bytes = digest(new RIPEMD160Digest(), input)

/** What a signature builtin answers: a verdict, the reference's failure for an argument that does
  * not deserialise, or a defect of the library.
  */
type Verified = Either[SignatureInputFailure | LibraryDefect, Boolean]

/** The signature builtins of `PlutusCore/Crypto/Ed25519.hs` and `PlutusCore/Crypto/Secp256k1.hs`
  * (plutus 1.70.0.0 `507cab8c`). The reference deserialises the key, then the signature (then, for
  * ECDSA, the message hash) and fails on the first that does not deserialise; a signature that
  * deserialises and does not verify is `False`. The rules of deserialisation and verification are
  * cardano-crypto-class's over libsodium and libsecp256k1, which are not vendored here; they are
  * transcribed as recorded (`docs/compatibility/strict-ed25519.md`, the `Secp256k1` test model of
  * the `plutus` tests) and checked by the conformance corpus and the Wycheproof vectors.
  */
object Signatures:
  /** `verifyEd25519Signature` (`Ed25519.hs:23-44`) through [[StrictEd25519]]: a key of 32 bytes and
    * a signature of 64, anything else failing; the verdict is libsodium's non-COMPAT
    * `crypto_sign_verify_detached`.
    */
  def verifyEd25519(publicKey: Bytes, message: Bytes, signature: Bytes): Verified =
    StrictEd25519.verify(publicKey, signature, message) match
      case Right(VerificationResult.SignatureVerified) => Right(true)
      case Right(VerificationResult.SignatureRejected) => Right(false)
      case Left(WitnessInputError.MalformedPublicKeyLength(_)) =>
        Left(SignatureInputFailure.InvalidVerificationKey(SignatureScheme.Ed25519))
      case Left(WitnessInputError.MalformedSignatureLength(_)) =>
        Left(SignatureInputFailure.InvalidSignature(SignatureScheme.Ed25519))
      case Left(VerificationError.ImplementationFailure(cause)) =>
        Left(LibraryDefect.Thrown(cause))
      case Left(other: WitnessInputError) => Left(LibraryDefect.UnexpectedInput(other))

  private val parameters = SECNamedCurves.getByName("secp256k1")
  private val curve: ECCurve = parameters.getCurve
  private val generator: ECPoint = parameters.getG

  /** The order `n` of the group and the field modulus `p` of secp256k1. */
  val Order: BigInteger = parameters.getN
  val FieldModulus: BigInteger = curve.getField.getCharacteristic

  private val Seven: BigInteger = BigInteger.valueOf(7)

  /** The point with coordinate `x` and a `y` of parity `odd`, if `x < p` and `x^3 + 7` is a square
    * (`secp256k1_ge_set_xo_var`). As `p = 3 mod 4`, `(x^3 + 7)^((p + 1) / 4)` is its square root
    * when it has one.
    */
  private def lift(x: BigInteger, odd: Boolean): Option[ECPoint] =
    Option.when(x.compareTo(FieldModulus) < 0)(x).flatMap { x =>
      val rhs = x.pow(3).add(Seven).mod(FieldModulus)
      val root = rhs.modPow(FieldModulus.add(BigInteger.ONE).shiftRight(2), FieldModulus)
      Option.when(root.multiply(root).mod(FieldModulus) == rhs) {
        val y =
          if root.testBit(0) == odd then root else FieldModulus.subtract(root).mod(FieldModulus)
        curve.createPoint(x, y)
      }
    }

  private def guarded(body: => Boolean): Verified =
    Either.catchNonFatal(body).leftMap(LibraryDefect.Thrown(_))

  /** `verifyEcdsaSecp256k1Signature` (`Secp256k1.hs:44-68`): a 33-byte compressed key (prefix 2 or
    * 3, `x < p`, on the curve: `secp256k1_ec_pubkey_parse`), a 64-byte `r || s` with `r, s < n`
    * (`secp256k1_ecdsa_signature_parse_compact`), a 32-byte message; then `secp256k1_ecdsa_verify`,
    * which answers `False` for `r = 0`, `s = 0` or `s > n / 2` (it verifies normalised signatures
    * only) and otherwise checks `x(u1 G + u2 Q) = r (mod n)`.
    */
  def verifyEcdsa(publicKey: Bytes, message: Bytes, signature: Bytes): Verified =
    val prefix = publicKey.headOption.map(_ & 0xff)
    val key =
      if publicKey.size != 33 || !prefix.exists(p => p == 2 || p == 3) then None
      else lift(new BigInteger(1, publicKey.drop(1).toArray), prefix.contains(3))
    key match
      case None =>
        Left(SignatureInputFailure.InvalidVerificationKey(SignatureScheme.EcdsaSecp256k1))
      case Some(q) =>
        val r = new BigInteger(1, signature.take(32).toArray)
        val s = new BigInteger(1, signature.drop(32).toArray)
        if signature.size != 64 || r.compareTo(Order) >= 0 || s.compareTo(Order) >= 0 then
          Left(SignatureInputFailure.InvalidSignature(SignatureScheme.EcdsaSecp256k1))
        else if message.size != 32 then Left(SignatureInputFailure.InvalidMessageHash)
        else if r.signum == 0 || s.signum == 0 || s.compareTo(Order.shiftRight(1)) > 0 then
          Right(false)
        else
          guarded {
            val w = s.modInverse(Order)
            val m = new BigInteger(1, message.toArray).mod(Order)
            val point =
              ECAlgorithms
                .sumOfTwoMultiplies(
                  generator,
                  m.multiply(w).mod(Order),
                  q,
                  r.multiply(w).mod(Order)
                )
                .normalize()
            !point.isInfinity && point.getAffineXCoord.toBigInteger.mod(Order) == r
          }

  private val challengeTag: Array[Byte] =
    val sha = new SHA256Digest()
    val tag = "BIP0340/challenge".getBytes(StandardCharsets.US_ASCII)
    val out = new Array[Byte](32)
    sha.update(tag, 0, tag.length)
    sha.doFinal(out, 0)
    out

  /** BIP-340's `hash_BIP0340/challenge(r || x(P) || message)`. */
  private def challenge(r: Bytes, key: Bytes, message: Bytes): BigInteger =
    val sha = new SHA256Digest()
    val out = new Array[Byte](32)
    Vector(challengeTag, challengeTag, r.toArray, key.toArray, message.toArray).foreach(b =>
      sha.update(b, 0, b.length)
    )
    sha.doFinal(out, 0)
    new BigInteger(1, out)

  /** `verifySchnorrSecp256k1Signature` (`Secp256k1.hs:87-109`): a 32-byte x-only key that lifts to
    * a point (`secp256k1_xonly_pubkey_parse`), a 64-byte signature; then
    * `secp256k1_schnorrsig_verify` (BIP-340, any message length): `False` for `r >= p` or `s >= n`,
    * otherwise `R = s G - e P` must be finite, of even `y`, with `x(R) = r`.
    */
  def verifySchnorr(publicKey: Bytes, message: Bytes, signature: Bytes): Verified =
    val key = Option
      .when(publicKey.size == 32)(publicKey)
      .flatMap(k => lift(new BigInteger(1, k.toArray), odd = false))
    key match
      case None =>
        Left(SignatureInputFailure.InvalidVerificationKey(SignatureScheme.SchnorrSecp256k1))
      case Some(p) =>
        if signature.size != 64 then
          Left(SignatureInputFailure.InvalidSignature(SignatureScheme.SchnorrSecp256k1))
        else
          val rBytes = signature.take(32)
          val r = new BigInteger(1, rBytes.toArray)
          val s = new BigInteger(1, signature.drop(32).toArray)
          if r.compareTo(FieldModulus) >= 0 || s.compareTo(Order) >= 0 then Right(false)
          else
            guarded {
              val e = challenge(rBytes, publicKey, message).mod(Order)
              val point = ECAlgorithms.sumOfTwoMultiplies(generator, s, p.negate(), e).normalize()
              !point.isInfinity && !point.getAffineYCoord.toBigInteger.testBit(0) &&
              point.getAffineXCoord.toBigInteger == r
            }

/** `expModInteger` (`Default/Builtins.hs:2314-2326` and `PlutusCore/Crypto/ExpMod.hs:17-50`). */
object ExpMod:
  /** `maxBoundI`, `minBoundI`, `maxBoundN` (`ExpMod.hs:37-47`). */
  val Max: BigInt = (BigInt(1) << 8191) - 1
  val Min: BigInt = -(BigInt(1) << 8191)

  private def outOfBounds(x: BigInt): Boolean = x > Max || x < Min

  /** `base ^ exponent mod modulus` in `[0, modulus)`; a negative exponent raises the inverse of the
    * base, which must exist.
    */
  def expMod(base: BigInt, exponent: BigInt, modulus: BigInt): Either[ExpModFailure, BigInt] =
    if modulus.signum < 0 then Left(ExpModFailure.NegativeModulus)
    else if modulus.signum == 0 || modulus > Max then Left(ExpModFailure.InvalidModulus)
    else if modulus == 1 then Right(BigInt(0))
    else if base.signum == 0 && exponent.signum < 0 then
      Left(ExpModFailure.NotInvertible(BigInt(0), modulus))
    else if outOfBounds(base) || outOfBounds(exponent) then Left(ExpModFailure.OutOfBounds)
    else if exponent.signum >= 0 then Right(base.modPow(exponent, modulus))
    else if base.gcd(modulus) != 1 then Left(ExpModFailure.NotInvertible(base, modulus))
    else Right(base.modInverse(modulus).modPow(-exponent, modulus))
