// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import java.security.{InvalidKeyException, KeyFactory, Signature, SignatureException}
import java.security.spec.{EdECPoint, EdECPublicKeySpec, NamedParameterSpec}
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc8032.Ed25519
import scala.util.Try
import xyz.kd5ujc.scarab.codec.Bytes

/** The Ed25519 verifiers the cross-check suites compare, each with the outcomes it has.
  *
  *   - Scarab's `StrictEd25519`, whose rule is the reference's: cardano-crypto-class's
  *     `verifyDSIGN` for Ed25519 (cardano-base `060819b59c184b951a54e3c563304983c53a3eac`,
  *     `cardano-crypto-class/src/Cardano/Crypto/DSIGN/Ed25519.hs:198-210`) calls libsodium's
  *     `crypto_sign_ed25519_verify_detached`, built without `ED25519_COMPAT`,
  *     IntersectMBO/libsodium at `dbb48cce5429cb6585c9034f002568964f1ce567`
  *     (`fixtures/witness/provenance/sodium-open.c` lines 31-38 and 50-54, the open.c of that
  *     revision byte for byte; the checks it calls are
  *     `src/libsodium/crypto_core/ed25519/ref10/ed25519_ref10.c` lines 1196-1211
  *     (`ge25519_is_canonical`), 1213-1267 (`ge25519_has_small_order`, a blacklist of seven
  *     encodings compared with bit 255 masked) and 2645-2665 (`sc25519_is_canonical`)): S below L;
  *     neither R nor A one of the blacklisted encodings of a point of small order; A canonical and
  *     on the curve; the canonical encoding of [S]B - [k]A equal to the bytes of R, with no
  *     cofactor.
  *   - The JDK's SunEC provider, OpenJDK jdk25u at tag `jdk-25.0.4.1+1` (commit
  *     `7b65d74bcbd7c3a0b4c1f5c5bd85e64aa2e51b4a`, the build image's JDK), in
  *     `src/java.base/share/classes/sun/security/ec/ed/`: the key is decoded when verification
  *     starts (`EdDSASignature.java:124`), refusing y >= p (`EdDSAOperations.java:212`), a y with
  *     no x and x = 0 with the sign bit set (`Ed25519Operations.java:133,137`) by an
  *     `InvalidKeyException`; then a signature of the wrong length (`EdDSAOperations.java:137`), S
  *     >= L (`:148`) and an R it cannot decode (`:153`, the same refusals) are a
  *     `SignatureException`; then [S]B = R + [k]A is compared as affine points (`:168`). There is
  *     no small-order test and no cofactor.
  *   - Bouncy Castle 1.86 (bcgit/bc-java tag `r1rv86`, commit
  *     `eacda831fbe15b272933e40841d8b0827b556fae`), `org.bouncycastle.math.ec.rfc8032.Ed25519`:
  *     `verify` refuses an R with y >= p (`checkPointVar`, `Ed25519.java:580`), S >= L (`:586`), an
  *     A with y >= p or the y of a point of small order (`checkPointFullVar`, `:591`, lines
  *     246-280), and an R or A with no x or with x = 0 and the sign bit set (`decodePointVar`,
  *     `:595,601`, lines 324-356); its equation is cofactored, [8]([S]B - R - [k]A) = 0 (the three
  *     final doublings of `scalarMultStraus128Var`, lines 1497-1502). Its raw `verify` takes 64
  *     bytes; for other lengths the cross-check calls `Ed25519Signer`, which returns false
  *     (`crypto/signers/Ed25519Signer.java:97-101`).
  *
  * So the JDK accepts what Scarab accepts and also a signature whose A or R is of small order when
  * the equation holds, and Bouncy Castle accepts what Scarab accepts and also a signature whose
  * small-order R or torsion component satisfies only the cofactored equation.
  */
private[witness] object Ed25519Verifiers:
  enum Jdk:
    case Verified, Rejected

    /** `initVerify` refused the key with an `InvalidKeyException`. */
    case KeyRefused

    /** `verify` refused the signature with a `SignatureException`. */
    case SignatureRefused

  def scarab(
      key: Array[Byte],
      signature: Array[Byte],
      message: Array[Byte]
  ): Either[WitnessInputError | VerificationError, VerificationResult] =
    StrictEd25519.verify(Bytes.fromArray(key), Bytes.fromArray(signature), Bytes.fromArray(message))

  def byron(
      key: Array[Byte],
      signature: Array[Byte],
      message: Array[Byte]
  ): Either[WitnessInputError | VerificationError, VerificationResult] =
    ByronEd25519.verify(Bytes.fromArray(key), Bytes.fromArray(signature), Bytes.fromArray(message))

  private val keys = KeyFactory.getInstance("Ed25519", "SunEC")

  /** The JDK key of a 32-byte encoding: y as written (bit 255 cleared) and the sign of x. */
  private def jdkKey(key: Array[Byte]) =
    val y = key.reverse
    y(0) = (y(0) & 0x7f).toByte
    val point = new EdECPoint((key(31) & 0x80) != 0, new java.math.BigInteger(1, y))
    keys.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point))

  /** Whether the JDK accepts the encoding as a key: `initVerify` does not refuse it. */
  def jdkAcceptsKey(key: Array[Byte]): Boolean =
    Try(Signature.getInstance("Ed25519", "SunEC").initVerify(jdkKey(key))).isSuccess

  def jdk(key: Array[Byte], signature: Array[Byte], message: Array[Byte]): Jdk =
    val verifier = Signature.getInstance("Ed25519", "SunEC")
    Try(verifier.initVerify(jdkKey(key))).toEither match
      case Left(_: InvalidKeyException) => Jdk.KeyRefused
      case Left(other)                  => throw other
      case Right(_) =>
        verifier.update(message)
        Try(verifier.verify(signature)).toEither match
          case Left(_: SignatureException) => Jdk.SignatureRefused
          case Left(other)                 => throw other
          case Right(true)                 => Jdk.Verified
          case Right(false)                => Jdk.Rejected

  def bc(key: Array[Byte], signature: Array[Byte], message: Array[Byte]): Boolean =
    if signature.length == Ed25519.SIGNATURE_SIZE then
      Ed25519.verify(signature, 0, key, 0, message, 0, message.length)
    else
      val signer = new Ed25519Signer()
      signer.init(false, new Ed25519PublicKeyParameters(key, 0))
      signer.update(message, 0, message.length)
      signer.verifySignature(signature)

  /** BC's decoding of a public key, `validatePublicKeyPartial`: `checkPointFullVar` then
    * `decodePointVar` (`Ed25519.java:1624-1637`).
    */
  def bcAcceptsKey(key: Array[Byte]): Boolean = Ed25519.validatePublicKeyPartial(key, 0)
