// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf.secret

import cats.syntax.either.*
import com.weavechain.curve25519.{CompressedEdwardsY, Constants, EdwardsPoint, Scalar}
import java.security.MessageDigest
import java.util.Arrays
import scala.util.Using
import scala.util.Using.Releasable
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover.{Failure, Proved}

/** The secret steps of ECVRF-ED25519-SHA512-Elligator2 (draft-03) proving, in the order and form of
  * the Cardano libsodium fork's `crypto_vrf_ietfdraft03_prove` (IntersectMBO/libsodium dbb48cce,
  * `src/libsodium/crypto_vrf/ietfdraft03/prove.c` 25-70), over the input's hash-to-curve point H,
  * which [[xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover]] computes from the public key and alpha
  * (public inputs) and hands in as its encoding.
  *
  * The key holds its 32-byte seed and nothing derived from it. Each operation copies the seed into
  * a [[Scratch]] and expands it there, as the reference expands the key on every call (25-28):
  * SHA-512 of the seed, its first half clamped as the scalar x, which the multiplication and the
  * response take unreduced (41, 67), and its second half the nonce key. Every value computed from
  * them (x and its radix-16 digits, the nonce digest, the nonce k and its digits, the encodings of
  * Gamma, U = [k]B and V = [k]H) lives in that scratch and is zeroed when the operation ends, on
  * failure too; the reference zeroes the expanded key and the nonce (69-70).
  *
  * Every operation on x and k is the constant-time arithmetic of the vendored curve25519 code: the
  * fixed-base and variable-base multiplications by radix-16 digits
  * (`EdwardsBasepointTable.multiplyRadix16`, `EdwardsPoint.multiplyRadix16`, each digit selected
  * from its table by masks), the radix-16 recoding (`Scalar.toRadix16`), the reduction of the nonce
  * (`Scalar.reduceWide`) and the response `s = c x + k` (`Scalar.multiplyAndAdd`), and point
  * encoding (`EdwardsPoint.compress`, an inversion by a fixed addition chain). The challenge is the
  * hash of the encodings as computed (52-63), and the output the hash of the encoding of `[8]Gamma`
  * computed from the constant-time point; no secret-derived point is decoded. This package may name
  * no variable-time routine (`scripts/check_crypto_guard.py`). That is the structure of the code:
  * the JIT and the processor give no constant-time guarantee, and the curve library's intermediates
  * (its field elements) are immutable objects that are not erased.
  *
  * The seed is zeroed by [[forget]], under this key's monitor, which every copy of it also holds:
  * an operation copies the whole seed or, once the key is forgotten, refuses
  * ([[Failure.MissingSigningKey]]). A forgotten key proves nothing more.
  */
private[vrf] final class Draft03Key private (
    private val material: Array[Byte],
    val publicKey: Bytes
):
  import Draft03Key.*

  // FUN-2 (d): the seed is zeroed in place by forget; reads and the erasure hold this monitor
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var forgotten = false

  /** Zero the seed. Copies given out by [[toBytes]] are their owners'. */
  def forget(): Unit = synchronized {
    Arrays.fill(material, 0.toByte)
    forgotten = true
  }

  def isForgotten: Boolean = synchronized(forgotten)

  /** Whether every byte of the key's own seed is zero, read under the monitor and with no early
    * exit: for the suites of this package to see that [[forget]] zeroes it.
    */
  private[secret] def storedSeedIsZero: Boolean =
    synchronized(material.foldLeft(0)((acc, b) => acc | b) == 0)

  /** `seed || publicKey`, the layout of `vrf.skey`: a copy its caller owns (the seed zero once the
    * key is forgotten), and the only one made outside a scratch. The node reads VRF key files and
    * writes none, so no main code calls it; a writer would take the bytes inside a scratch instead.
    */
  def toBytes: Bytes = Scratch.within { s =>
    val payload = s.buffer(SeedBytes + 32)
    seedIn(s).foreach(seed => System.arraycopy(s.array(seed), 0, s.array(payload), 0, SeedBytes))
    publicKey.copyTo(s.array(payload), SeedBytes)
    s.publish(payload)
  }

  /** Whether `other` holds the same seed, and so is the same key; false once either is forgotten.
    * The seeds are compared in constant time.
    */
  def sameKey(other: Draft03Key): Boolean = Scratch.within { s =>
    (seedIn(s), other.seedIn(s)) match
      case (Some(a), Some(b)) => MessageDigest.isEqual(s.array(a), s.array(b))
      case _                  => false
  }

  /** The 64-byte output of the input whose hash-to-curve point is encoded as `h`: the output
    * [[prove]] certifies, from the same steps up to Gamma, without the nonce, the commitments, the
    * challenge and the response that only the proof needs.
    */
  def output(h: Bytes): Either[Failure, Bytes] = run(Scratch.within(s => outputIn(s, h)))

  /** The proof `Gamma || c || s` over the input whose hash-to-curve point is encoded as `h`, and
    * the output it certifies.
    */
  def prove(h: Bytes): Either[Failure, Proved] = run(Scratch.within(s => provingIn(s, h)))

  /** [[output]] in the scratch `s`; none once the key is forgotten. */
  private[secret] def outputIn(s: Scratch, h: Bytes): Option[Bytes] =
    seedIn(s).map { seed =>
      val (x, _) = expanded(s)(seed)
      outputOf(s)(gammaOf(s)(x, decoded(h)))
    }

  /** [[prove]] in the scratch `s`; none once the key is forgotten. `copied` runs once the seed is
    * copied and the monitor released, before anything is computed from the copy: the point at which
    * a suite of this package parks a proof to race it with [[forget]].
    */
  private[secret] def provingIn(
      s: Scratch,
      h: Bytes,
      copied: () => Unit = () => ()
  ): Option[Proved] =
    seedIn(s).map { seed =>
      copied()
      val (x, nonceKey) = expanded(s)(seed)
      val hString = h.toArray
      val hPoint = decoded(h)
      val gamma = gammaOf(s)(x, hPoint)
      val gammaString = encoded(s)(gamma)
      // k = SHA-512(nonce key || H) mod L, the deterministic nonce of draft-03 (RFC 8032 style)
      val nonce = s.buffer(64)
      sha512(s.array(nonce), s.array(nonceKey), hString)
      val k = s.buffer(32)
      Scalar.reduceWide(s.array(nonce), s.array(k))
      val kDigits = digits(s)(k)
      val u = encoded(s)(Constants.ED25519_BASEPOINT_TABLE.multiplyRadix16(s.array(kDigits)))
      val v = encoded(s)(hPoint.multiplyRadix16(s.array(kDigits)))
      // c, the first 16 bytes of the hash of H, Gamma, U and V, each as encoded
      val hashed = s.buffer(64)
      sha512(
        s.array(hashed),
        Array(Suite, ChallengeDomain),
        hString,
        s.array(gammaString),
        s.array(u),
        s.array(v)
      )
      val c = s.buffer(32)
      System.arraycopy(s.array(hashed), 0, s.array(c), 0, ChallengeBytes)
      val response = s.buffer(32)
      Scalar.multiplyAndAdd(s.array(c), s.array(x), s.array(k), s.array(response))
      val proof = s.buffer(80)
      System.arraycopy(s.array(gammaString), 0, s.array(proof), 0, 32)
      System.arraycopy(s.array(c), 0, s.array(proof), 32, ChallengeBytes)
      System.arraycopy(s.array(response), 0, s.array(proof), 32 + ChallengeBytes, 32)
      Proved(s.publish(proof), outputOf(s)(gamma))
    }

  /** The seed, copied into a buffer of `s`; none once the key is forgotten. */
  private def seedIn(s: Scratch): Option[s.Buffer] = synchronized {
    Option.unless(forgotten) {
      val seed = s.buffer(SeedBytes)
      System.arraycopy(material, 0, s.array(seed), 0, SeedBytes)
      seed
    }
  }

private[vrf] object Draft03Key:
  private val SeedBytes = 32
  private val ChallengeBytes = 16

  // the suite string of ECVRF-ED25519-SHA512-Elligator2, and the domain bytes of the challenge
  // hash and the output hash
  private val Suite: Byte = 4
  private val ChallengeDomain: Byte = 2
  private val OutputDomain: Byte = 3

  def fromSeed(seed: Bytes): Either[Failure, Draft03Key] =
    if seed == null then Left(Failure.MissingSeed)
    else if seed.size != SeedBytes then Left(Failure.SeedLength(seed.size))
    else made(seed)

  /** The 64-byte `vrf.skey` payload `seed || publicKey`; the stored public key must be the seed's.
    */
  def fromBytes(bytes: Bytes): Either[Failure, Draft03Key] =
    if bytes == null then Left(Failure.MissingSigningKey)
    else if bytes.size != SeedBytes + 32 then Left(Failure.SigningKeyLength(bytes.size))
    else
      made(bytes.take(SeedBytes)).flatMap { key =>
        if key.publicKey == bytes.drop(SeedBytes) then Right(key)
        else
          key.forget()
          Left(Failure.PublicKeyMismatch)
      }

  /** The key of the 32 bytes `seed`: its public key `[x]B`, computed from a copy in a scratch,
    * which zeroes it however the computation ends; then the key, made with an array of zeros, and
    * the seed copied into that array, which nothing between the copy and the key that owns it can
    * fail.
    */
  private def made(seed: Bytes): Either[Failure, Draft03Key] =
    Either
      .catchNonFatal(Scratch.within { s =>
        val copy = s.buffer(SeedBytes)
        seed.copyTo(s.array(copy), 0)
        val (x, _) = expanded(s)(copy)
        s.publish(
          encoded(s)(Constants.ED25519_BASEPOINT_TABLE.multiplyRadix16(s.array(digits(s)(x))))
        )
      })
      .bimap(
        Failure.InternalFailure(_),
        { publicKey =>
          val key = new Draft03Key(new Array[Byte](SeedBytes), publicKey)
          seed.copyTo(key.material, 0)
          key
        }
      )

  /** What an operation computed, a failure of the library as [[Failure.InternalFailure]], or the
    * refusal of a forgotten key.
    */
  private def run[A](computed: => Option[A]): Either[Failure, A] =
    Either
      .catchNonFatal(computed)
      .leftMap(Failure.InternalFailure(_))
      .flatMap(_.toRight(Failure.MissingSigningKey))

  /** The scalar x and the nonce key of `seed`, in `s`: SHA-512 of the seed, its first half clamped
    * (bit 255 clear, bit 254 set, the low three bits clear) and its second half. x stays unreduced:
    * it is below 2^255, the range the radix-16 recoding and the response take, and B and H have
    * order L, so `[x]B` and `[x]H` are those of x mod L.
    */
  private def expanded(s: Scratch)(seed: s.Buffer): (s.Buffer, s.Buffer) =
    val az = s.buffer(64)
    sha512(s.array(az), s.array(seed))
    val x = s.buffer(32)
    val nonceKey = s.buffer(32)
    System.arraycopy(s.array(az), 0, s.array(x), 0, 32)
    System.arraycopy(s.array(az), 32, s.array(nonceKey), 0, 32)
    val scalar = s.array(x)
    scalar(0) = (scalar(0) & 248).toByte
    scalar(31) = ((scalar(31) & 127) | 64).toByte
    (x, nonceKey)

  /** The 64 radix-16 digits of the 32-byte scalar `k`, below 2^255, in `s`. */
  private def digits(s: Scratch)(k: s.Buffer): s.Buffer =
    val e = s.buffer(64)
    Scalar.toRadix16(s.array(k), s.array(e))
    e

  /** The encoding of `p`, which `s` keeps and zeroes: `compress` makes it for this call only. */
  private def encoded(s: Scratch)(p: EdwardsPoint): s.Buffer = s.adopt(p.compress().toByteArray)

  /** `Gamma = [x]H`. */
  private def gammaOf(s: Scratch)(x: s.Buffer, h: EdwardsPoint): EdwardsPoint =
    h.multiplyRadix16(s.array(digits(s)(x)))

  /** The output of `gamma`: SHA-512 of the suite, the domain byte and the encoding of `[8]Gamma`,
    * what the verifier computes from the proof's Gamma.
    */
  private def outputOf(s: Scratch)(gamma: EdwardsPoint): Bytes =
    val out = s.buffer(64)
    sha512(
      s.array(out),
      Array(Suite, OutputDomain),
      s.array(encoded(s)(gamma.multiplyByCofactor()))
    )
    s.publish(out)

  /** H, from its encoding: a public point, decoded by the constant-time decoder. */
  private def decoded(h: Bytes): EdwardsPoint = new CompressedEdwardsY(h.toArray).decompress()

  /** SHA-512 of `pieces` into the 64 bytes of `into`, with a digest made for the call. */
  private def sha512(into: Array[Byte], pieces: Array[Byte]*): Unit =
    sha512With(MessageDigest.getInstance("SHA-512"))(into, pieces*)

  private given Releasable[MessageDigest] = _.reset()

  /** SHA-512 of `pieces` into `into` with `digest`, which is reset on the way out, whether the hash
    * returns or throws. `digest(into, …)` does not clear the provider: in the JDK
    * (`sun.security.provider.DigestBase` and `SHA5` at jdk-25.0.4.1-ga) it leaves the input after
    * the last whole block in the provider's buffer, the result in its state words and the last
    * block's message schedule in its `W` array, and clears them only when the object is used again
    * or reset (`engineReset`: `implReset` restores the initial state and zeroes `W`, then the
    * buffer is zeroed). A digest made for one call and dropped kept the seed, the expanded key or
    * the nonce key until the collector reused its memory. What the reset cannot reach: what the
    * compression's intrinsic keeps in registers or spills to the stack, and copies a moving
    * collector made of the provider's arrays before the reset.
    */
  private[secret] def sha512With(
      digest: MessageDigest
  )(into: Array[Byte], pieces: Array[Byte]*): Unit =
    Using.resource(digest) { d =>
      pieces.foreach(d.update)
      d.digest(into, 0, into.length)
    }
