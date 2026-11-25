// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.secret.Draft03Key
import cats.syntax.either.*

/** ECVRF-ED25519-SHA512-Elligator2 (draft-03) proving, as libsodium's
  * `crypto_vrf_ietfdraft03_prove` and the Cardano `PraosVRF` signing key: a 64-byte key
  * `seed || publicKey`. Proofs are deterministic, so they are checked against known vectors and by
  * [[StrictDraft03.verify]].
  *
  * Proving is split in two. Here, the hash to curve of the public key and alpha, public inputs
  * only, with the variable-time verifier arithmetic of [[Draft03Math]]: the only call this file
  * makes into it (`scripts/check_crypto_guard.py`). The rest, everything that reads the seed or a
  * value computed from it, is [[secret.Draft03Key]], in a package of its own that may name no
  * variable-time routine, and whose working values are erased when each operation ends. This object
  * never sees the seed: the key's material is private to that class.
  *
  * That is the intended split and the structure of the code, not an established property: the
  * arithmetic is Java compiled by the JIT, which gives no constant-time guarantee (#230), and the
  * JVM may copy an array before it is zeroed. See [[secret.Draft03Key]] for what is erased.
  */
object Draft03Prover:
  enum Failure:
    case MissingSeed, MissingSigningKey, PublicKeyMismatch
    case SeedLength(actual: Int)
    case SigningKeyLength(actual: Int)
    case InternalFailure(cause: Throwable)

  /** A proof and the 64-byte VRF output it certifies. */
  final case class Proved(proof: Bytes, output: Bytes)

  /** The signing key: its secret half, and its public key. */
  final class SigningKey private (private val key: Draft03Key):
    def publicKey: Bytes = key.publicKey

    /** `seed || publicKey`, the layout of `vrf.skey`: a copy its caller owns. */
    def toBytes: Bytes = key.toBytes

    /** Zero the seed, the only secret the key keeps. A forgotten key proves nothing more ([[prove]]
      * and [[output]] refuse with [[Failure.MissingSigningKey]]). Copies given out by [[toBytes]]
      * are their owners'.
      */
    def forget(): Unit = key.forget()

    /** Whether `other` is the same key (the same seed, compared in constant time); false once
      * either is forgotten.
      */
    def sameKey(other: SigningKey): Boolean = key.sameKey(other.key)

    def prove(alpha: Bytes): Either[Failure, Proved] = hashed(alpha).flatMap(key.prove)

    /** The 64-byte output of `alpha` alone: the output [[prove]] certifies, without the nonce, the
      * commitments, the challenge and the response that only the proof needs.
      */
    def output(alpha: Bytes): Either[Failure, Bytes] = hashed(alpha).flatMap(key.output)

    /** The encoding of H, the hash to curve of the public key and `alpha`, unless the key is
      * forgotten.
      */
    private def hashed(alpha: Bytes): Either[Failure, Bytes] =
      if key.isForgotten then Left(Failure.MissingSigningKey)
      else
        Either
          .catchNonFatal(
            Bytes.fromArray(Draft03Math.h2c(publicKey.toArray, alpha.toArray).encode())
          )
          .leftMap(Failure.InternalFailure(_))

  object SigningKey:
    def fromSeed(seed: Bytes): Either[Failure, SigningKey] =
      Draft03Key.fromSeed(seed).map(new SigningKey(_))

    /** The 64-byte `vrf.skey` payload; the stored public key must be the seed's. */
    def fromBytes(bytes: Bytes): Either[Failure, SigningKey] =
      Draft03Key.fromBytes(bytes).map(new SigningKey(_))
