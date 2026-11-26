// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import xyz.kd5ujc.scarab.codec.Bytes
import cats.syntax.bifunctor.*
import cats.syntax.either.*

/** Ed25519 verification as the Byron era of the reference performs it. For Byron signatures only:
  * every later era verifies with [[StrictEd25519]].
  *
  * The Byron path of cardano-node 11.1.3 reaches `Crypto.PubKey.Ed25519.verify` of crypton 1.0.6:
  * cardano-crypto-wrapper 1.7.0.0 `verifySignatureRaw` (`Cardano/Crypto/Signing/Signature.hs`)
  * (`:228-234`) through cardano-crypto 1.3.0 `Cardano.Crypto.Wallet.verify`
  * (`Cardano/Crypto/Wallet.hs:198-202`), and `verifyRedeemSigRaw`
  * (`Cardano/Crypto/Signing/Redeem/Signature.hs:98-104`) directly. That is the ed25519-donna
  * `ed25519_sign_open` of crypton's `cbits/ed25519/ed25519.c:87-110`, which
  *   - refuses a signature only if one of the top three bits of S is set, and reduces S modulo L
  *     (`expand256_modm`, `modm-donna-64bit.h:153-187`), so S + L is accepted;
  *   - reads A with `ge25519_unpack_negative_vartime` (`ed25519-donna-impl-base.h:191-239`), which
  *     masks bit 255 and computes with y modulo p (`curve25519_expand`,
  *     `curve25519-donna-64bit.h:287-319`), ignores the sign bit when x = 0, and refuses only a y
  *     that is not on the curve: non-canonical and small-order keys are accepted;
  *   - never decodes R: the canonical encoding of [S]B - [h]A must equal the R bytes, h being
  *     SHA-512(R, A, M) over the original key bytes, reduced modulo L, with no cofactor.
  *
  * gouroboros ships the same semantics for its Byron path (blinklabs-io/gouroboros 055fb39a,
  * `internal/ed25519byron/verify.go`), as a point comparison after a canonical decoding of R; the
  * byte comparison here is the reference's own form and gives the same verdicts. Verdicts are
  * pinned against the released libraries by `ByronEd25519ReferenceSuite`
  * (`fixtures/witness-byron`). All inputs are public: no constant-time claim.
  */
object ByronEd25519:
  import VerificationResult.*

  def verify(
      publicKey: Bytes,
      signature: Bytes,
      message: Bytes
  ): Either[WitnessInputError | VerificationError, VerificationResult] =
    for
      key <- PublicKey32.create(publicKey)
      sig <- Signature64.create(signature)
      result <- verifyEd25519(key, sig, message).leftWiden[WitnessInputError | VerificationError]
    yield result

  def verifyEd25519(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): Either[VerificationError, VerificationResult] =
    // The arithmetic refuses a key off the curve with a verdict and, given a key of 32 bytes and a
    // signature of 64, throws nothing. What can throw is the JDK's SHA-512, when no provider
    // supplies it, and a null argument: a typed implementation failure.
    Either
      .catchNonFatal(evaluate(publicKey, signature, message))
      .leftMap(VerificationError.ImplementationFailure(_))

  private def evaluate(
      publicKey: PublicKey32,
      signature: Signature64,
      message: Bytes
  ): VerificationResult =
    // `RS[63] & 224`: S below 2^253, any such S reduced modulo L (zero-padded to 64 bytes); A read
    // as `ge25519_unpack_negative_vartime` reads it (y modulo p, any order); then the hash, and
    // `ge25519_double_scalarmult_vartime(&R, &A, hram, S)` over the negated key, as `StrictEd25519`
    // computes it, R never decoded (`VartimeEd25519.byron`)
    val accepted = publicKey.bytes.withRange { (key, keyAt, _) =>
      signature.bytes.withRange { (sig, sigAt, _) =>
        VartimeEd25519.byron(
          key,
          keyAt,
          sig,
          sigAt,
          () => Ed25519Challenge(signature.bytes, publicKey.bytes, message)
        )
      }
    }
    if accepted then SignatureVerified else SignatureRejected
