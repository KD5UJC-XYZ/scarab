// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes.secret

import com.weavechain.curve25519.{Constants, EdwardsPoint, Scalar}
import xyz.kd5ujc.scarab.crypto.secret.{Scratch, Sha512}

/** The Ed25519 keys at the leaves of a Sum6 KES tree (RFC 8032 section 5.1, `PureEdDSA`;
  * cardano-base `Ed25519DSIGN`, whose signing key is the 32-byte seed), computed in a [[Scratch]]:
  * SHA-512 of the seed, its first half clamped as the scalar x and its second half the nonce
  * prefix; the public key A, the encoding of `[x]B`; the nonce `r = SHA-512(prefix || M) mod L`,
  * `R = [r]B`, the challenge `k = SHA-512(R || A || M) mod L` and `S = (r + k x) mod L`. Every one
  * of them is a buffer of the scratch, zeroed when the computation ends. Bouncy Castle's Ed25519,
  * which the KES key used before, keeps the expanded key, the scalar and the nonce in arrays of its
  * own that it does not clear.
  *
  * The arithmetic on x and r is the vendored curve25519 code's constant-time arithmetic, as the VRF
  * prover's (`xyz.kd5ujc.scarab.crypto.vrf.secret.Draft03Key`): the fixed-base multiplication by
  * radix-16 digits selected by masks (`EdwardsBasepointTable.multiplyRadix16`), the radix-16
  * recoding (`Scalar.toRadix16`), the reduction of a 64-byte hash (`Scalar.reduceWide`) and
  * `Scalar.multiplyAndAdd`. x stays unreduced: it is below 2^255, the range the recoding and the
  * response take, and B has order L, so `[x]B` is that of x mod L. The curve library's own
  * intermediates (field elements and points) are immutable objects that no scratch holds and that
  * are not erased; the JIT and the processor give no constant-time guarantee.
  *
  * The vendored calls refuse arrays of the wrong size by throwing, and none is handed one: every
  * array they take is a buffer made here at the size the call takes (scalars and encodings of 32
  * bytes, hashes and radix-16 digits of 64), filled by the call before it, and the seed is the 32
  * bytes at an offset of a key whose size its loader checked. The message, the one input of any
  * size, is only hashed. Each hash works on a copy of the digest the key found when it was made or
  * read ([[Sha512.Digests]]), made without asking the JDK again, so none of them can fail.
  */
private[kes] object Leaf:
  val SeedBytes: Int = 32
  val PublicKeyBytes: Int = 32
  val SignatureBytes: Int = 64

  /** The public key of the seed at `seed(at, at + 32)`, into `out` at `outAt`. */
  def publicKey(s: Scratch)(seed: Array[Byte], at: Int, out: Array[Byte], outAt: Int)(using
      Sha512.Digests
  ): Unit =
    val (_, _, a) = expanded(s)(seed, at)
    System.arraycopy(s.array(a), 0, out, outAt, PublicKeyBytes)

  /** The signature `R || S` of `message` by the seed at `seed(at, at + 32)`, into `out` at `outAt`.
    */
  def sign(s: Scratch)(
      seed: Array[Byte],
      at: Int,
      message: Array[Byte],
      out: Array[Byte],
      outAt: Int
  )(using Sha512.Digests): Unit =
    val (x, prefix, a) = expanded(s)(seed, at)
    val nonce = s.buffer(64)
    sha512(s.array(nonce))(s.array(prefix), message)
    val r = s.buffer(32)
    Scalar.reduceWide(s.array(nonce), s.array(r))
    val commitment =
      encoded(s)(Constants.ED25519_BASEPOINT_TABLE.multiplyRadix16(s.array(digits(s)(r))))
    val hram = s.buffer(64)
    sha512(s.array(hram))(s.array(commitment), s.array(a), message)
    val k = s.buffer(32)
    Scalar.reduceWide(s.array(hram), s.array(k))
    val response = s.buffer(32)
    Scalar.multiplyAndAdd(s.array(k), s.array(x), s.array(r), s.array(response))
    System.arraycopy(s.array(commitment), 0, out, outAt, 32)
    System.arraycopy(s.array(response), 0, out, outAt + 32, 32)

  /** The scalar x, the nonce prefix and the public key of the seed at `seed(at, at + 32)`, in `s`.
    */
  private def expanded(s: Scratch)(seed: Array[Byte], at: Int)(using
      Sha512.Digests
  ): (s.Buffer, s.Buffer, s.Buffer) =
    val copy = s.buffer(SeedBytes)
    System.arraycopy(seed, at, s.array(copy), 0, SeedBytes)
    val az = s.buffer(64)
    sha512(s.array(az))(s.array(copy))
    val x = s.buffer(32)
    val prefix = s.buffer(32)
    System.arraycopy(s.array(az), 0, s.array(x), 0, 32)
    System.arraycopy(s.array(az), 32, s.array(prefix), 0, 32)
    val scalar = s.array(x)
    // clamped: bit 255 clear, bit 254 set, the low three bits clear
    scalar(0) = (scalar(0) & 248).toByte
    scalar(31) = ((scalar(31) & 127) | 64).toByte
    val a = encoded(s)(Constants.ED25519_BASEPOINT_TABLE.multiplyRadix16(s.array(digits(s)(x))))
    (x, prefix, a)

  /** The 64 radix-16 digits of the 32-byte scalar `k`, below 2^255, in `s`. */
  private def digits(s: Scratch)(k: s.Buffer): s.Buffer =
    val e = s.buffer(64)
    Scalar.toRadix16(s.array(k), s.array(e))
    e

  /** The encoding of `p`, which `s` keeps and zeroes: `compress` makes it for this call only. */
  private def encoded(s: Scratch)(p: EdwardsPoint): s.Buffer = s.adopt(p.compress().toByteArray)

  /** SHA-512 of `pieces` into the 64 bytes of `into`, with a digest reset on the way out, as the
    * VRF prover's hash is ([[Sha512]]).
    */
  private def sha512(into: Array[Byte])(pieces: Array[Byte]*)(using Sha512.Digests): Unit =
    Sha512.into(into, pieces*)
