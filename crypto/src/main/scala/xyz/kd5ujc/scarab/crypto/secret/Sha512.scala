// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.secret

import java.security.MessageDigest
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
  */
private[crypto] object Sha512:
  private given Releasable[MessageDigest] = _.reset()

  /** SHA-512 of `pieces` into the 64 bytes of `into`. */
  def into(into: Array[Byte], pieces: Array[Byte]*): Unit =
    intoWith(MessageDigest.getInstance("SHA-512"))(into, pieces*)

  /** [[into]] with `digest`, which a suite can watch being reset. */
  private[secret] def intoWith(
      digest: MessageDigest
  )(into: Array[Byte], pieces: Array[Byte]*): Unit =
    Using.resource(digest) { d =>
      pieces.foreach(d.update)
      d.digest(into, 0, into.length)
    }
