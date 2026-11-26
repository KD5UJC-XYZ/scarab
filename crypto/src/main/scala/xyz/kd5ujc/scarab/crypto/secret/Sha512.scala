// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.secret

import cats.syntax.bifunctor.*
import cats.syntax.either.*
import java.security.{MessageDigest, NoSuchAlgorithmException}
import scala.util.Using
import scala.util.Using.Releasable

/** SHA-512 on the secret path (the VRF prover's `Draft03Key`, the KES leaves' `Leaf`), with a
  * digest that is reset on the way out, whether the hash returns or throws. `digest(into, …)` does
  * not clear the provider: in the JDK (`sun.security.provider.DigestBase` and `SHA5` at
  * jdk-25.0.4.1-ga) it leaves the input after the last whole block in the provider's buffer, the
  * result in its state words and the last block's message schedule in its `W` array, and clears
  * them only when the object is used again or reset (`engineReset`: `implReset` restores the
  * initial state and zeroes `W`, then the buffer is zeroed). A digest made for one call and dropped
  * kept the seed, the expanded key or the nonce key until the collector reused its memory. What the
  * reset cannot reach: what the compression's intrinsic keeps in registers or spills to the stack,
  * and copies a moving collector made of the provider's arrays before the reset.
  *
  * The digests come from one the JDK supplied, found once, when a key is made or read
  * ([[Digests.found]]), where the key's constructor returns its absence as a typed failure. Every
  * hash then works on a copy of it ([[Digests]]), made without asking the JDK again, so a hash has
  * no failure of its own.
  */
private[crypto] object Sha512:
  private given Releasable[MessageDigest] = _.reset()

  /** The JDK's SHA-512 digest, found once and never updated, which every hash copies: holding one
    * is the evidence that a digest can be had without a lookup. A copy is a `clone` of it, which
    * [[Digests.found]] has made once already: whether a digest clones is a property of its class,
    * so every later copy of the same digest succeeds. The prototype holds nothing secret, since no
    * hash updates it, and copies of it read it only, so keys and threads may share it.
    */
  final class Digests private[Sha512] (prototype: MessageDigest):
    /** A digest of its own for one hash. */
    private[Sha512] def fresh: MessageDigest = copied(prototype)

  object Digests:
    /** The JDK's SHA-512 digest, or the JDK's refusal: its `NoSuchAlgorithmException` when no
      * provider supplies SHA-512, or its `CloneNotSupportedException` when the digest it supplies
      * cannot be copied.
      */
    def found: Either[Exception, Digests] = foundWith(MessageDigest.getInstance("SHA-512"))

    /** [[found]] from the digest `lookup` makes, a seam for the suites. */
    private[crypto] def foundWith(lookup: => MessageDigest): Either[Exception, Digests] =
      for
        prototype <- Either.catchOnly[NoSuchAlgorithmException](lookup).leftWiden[Exception]
        _ <- Either.catchOnly[CloneNotSupportedException](prototype.clone()).leftWiden[Exception]
      yield new Digests(prototype)

  // FUN-2 (b): `MessageDigest.clone`, the JDK's, is declared to return an Object; the clone of a
  // MessageDigest is one of its class (`java.security.MessageDigest` and its `Delegate`s)
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def copied(prototype: MessageDigest): MessageDigest =
    prototype.clone().asInstanceOf[MessageDigest]

  /** SHA-512 of `pieces` into the 64 bytes of `into`, with a copy of the digest found. */
  def into(into: Array[Byte], pieces: Array[Byte]*)(using digests: Digests): Unit =
    intoWith(digests.fresh)(into, pieces*)

  /** [[into]] with `digest`, which a suite can watch being reset. */
  private[secret] def intoWith(
      digest: MessageDigest
  )(into: Array[Byte], pieces: Array[Byte]*): Unit =
    Using.resource(digest) { d =>
      pieces.foreach(d.update)
      d.digest(into, 0, into.length)
    }
