// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.secret

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
  * The JDK refuses SHA-512 only when none of its providers supplies it. That is found once, when a
  * key is made or read ([[Provider.found]]), where the key's constructor returns it as a typed
  * failure; the key keeps the [[Provider]] found, and every hash it computes later takes its digest
  * from that provider, which supplied SHA-512 and so supplies it again.
  */
private[crypto] object Sha512:
  private given Releasable[MessageDigest] = _.reset()

  /** The JDK provider that supplies SHA-512, as found once: holding one is the evidence that a
    * digest can be made, so a computation given one has no failure of its own to type.
    */
  final class Provider private[Sha512] (private[Sha512] val jdk: java.security.Provider)

  object Provider:
    /** The provider the JDK takes SHA-512 from; the JDK's refusal when none supplies it. */
    def found: Either[NoSuchAlgorithmException, Provider] =
      foundWith(MessageDigest.getInstance("SHA-512"))

    /** [[found]] from the digest `lookup` makes, a seam for the suites to refuse it. */
    private[crypto] def foundWith(
        lookup: => MessageDigest
    ): Either[NoSuchAlgorithmException, Provider] =
      Either.catchOnly[NoSuchAlgorithmException](new Provider(lookup.getProvider))

  /** SHA-512 of `pieces` into the 64 bytes of `into`, with a digest of the provider found. */
  def into(into: Array[Byte], pieces: Array[Byte]*)(using provider: Provider): Unit =
    intoWith(MessageDigest.getInstance("SHA-512", provider.jdk))(into, pieces*)

  /** [[into]] with `digest`, which a suite can watch being reset. */
  private[secret] def intoWith(
      digest: MessageDigest
  )(into: Array[Byte], pieces: Array[Byte]*): Unit =
    Using.resource(digest) { d =>
      pieces.foreach(d.update)
      d.digest(into, 0, into.length)
    }
