// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.injection

import org.bouncycastle.crypto.digests.Blake2bDigest
import xyz.kd5ujc.scarab.codec.Bytes

/** An immutable incremental Blake2b-256 value: updates/finalization copy the private library state.
  * Like SclsCommitment.ChunkHash, it retains neither earlier chunks nor a shared mutable digest.
  */
private[injection] final class InjectionDigest private (state: Blake2bDigest):
  def add(bytes: Bytes): InjectionDigest =
    val next = new Blake2bDigest(state)
    next.update(bytes.unsafeArray, bytes.unsafeOffset, bytes.size)
    new InjectionDigest(next)

  def finish: Bytes =
    val next = new Blake2bDigest(state)
    val output = new Array[Byte](32)
    next.doFinal(output, 0)
    Bytes.wrap(output)

private[injection] object InjectionDigest:
  val empty: InjectionDigest = new InjectionDigest(new Blake2bDigest(256))
