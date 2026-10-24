// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.crypto.jni.Secp256k1 as NativeSecp256k1

/** The meaning of the Plutus builtin `verifyEcdsaSecp256k1Signature` as the reference defines it
  * (`PlutusCore.Crypto.Secp256k1` over `Cardano.Crypto.DSIGN.EcdsaSecp256k1` and libsecp256k1):
  *
  *   - the key is 33 bytes and parses as a compressed point (`secp256k1_ec_pubkey_parse`), the
  *     message is 32 bytes, the signature is 64 bytes and parses as compact `r || s`
  *     (`secp256k1_ecdsa_signature_parse_compact`, which fails only when `r` or `s` is at least the
  *     group order `n`): otherwise the builtin fails;
  *   - everything else is a verdict, `False` or `True`, from `secp256k1_ecdsa_verify`: it is
  *     `False` for `r = 0` or `s = 0` (they parse), and for a high `s` (above `n / 2`), because
  *     libsecp256k1 verifies only normalised signatures and Plutus does not normalise.
  *
  * Scalus 1.2.0 and 1.3.0 fail on `r = 0` or `s = 0` where the reference answers `False`; the
  * preprod transaction 97b31e35 (block 3,498,869) passes an all-zero signature and the chain
  * accepts the answer `False`. Failures are typed values; the Scalus callback adapter retains them
  * in its evaluation log.
  */
object Secp256k1:
  /** The order `n` of the secp256k1 group. */
  val Order: BigInt =
    BigInt("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

  enum Operand:
    case PublicKey, Message, Signature
  enum Error:
    case NativeUnavailable
    case MissingOperand(operand: Operand)
    case WrongLength(operand: Operand, expected: Int, actual: Int)
    case InvalidPublicKey
    case ScalarOutOfRange
    case Defect(cause: Throwable)
    def message: String = this match
      case NativeUnavailable       => "secp256k1 native library not available"
      case MissingOperand(operand) => s"missing $operand"
      case WrongLength(operand, expected, actual) =>
        s"$operand must have $expected bytes (got $actual)"
      case InvalidPublicKey => "Invalid verification key."
      case ScalarOutOfRange => "Invalid signature."
      case Defect(cause)    => Option(cause.getMessage).getOrElse(cause.getClass.getName)

  /** Input shape failures are distinct from a well-formed signature's false verdict. */
  def verifyEcdsa(pk: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Either[Error, Boolean] =
    def length(bytes: Array[Byte], operand: Operand, expected: Int): Either[Error, Unit] =
      if bytes == null then Left(Error.MissingOperand(operand))
      else
        Either.cond(
          bytes.length == expected,
          (),
          Error.WrongLength(operand, expected, bytes.length)
        )
    // libsecp256k1 parses the key, then the compact signature (which fails only for r or s at least
    // the order, refused above), then verifies: an IllegalArgumentException is the key's.
    def verify: Either[Error, Boolean] =
      try Right(NativeSecp256k1.ecdsaVerify(msg, sig, pk))
      catch case _: IllegalArgumentException => Left(Error.InvalidPublicKey)
    try
      for
        _ <- Either.cond(NativeCrypto.available, (), Error.NativeUnavailable)
        _ <- length(pk, Operand.PublicKey, 33)
        _ <- length(sig, Operand.Signature, 64)
        r = BigInt(1, sig.slice(0, 32))
        s = BigInt(1, sig.slice(32, 64))
        _ <- Either.cond(r < Order && s < Order, (), Error.ScalarOutOfRange)
        _ <- length(msg, Operand.Message, 32)
        verdict <- verify
      yield verdict
    catch case scala.util.control.NonFatal(cause) => Left(Error.Defect(cause))
