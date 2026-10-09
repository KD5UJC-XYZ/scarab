// SPDX-License-Identifier: Apache-2.0
package lab.vm

import org.bouncycastle.crypto.digests.Blake2bDigest
import scalus.uplc.builtin.ByteString

/** Only unkeyed BLAKE2b-256 is enabled; every other platform capability rejects. */
private[vm] object Blake2bPlatform extends RejectingPlatform:
  override def blake2b_256(input: ByteString): ByteString =
    val digest = new Blake2bDigest(256)
    digest.update(input.bytes, 0, input.size)
    val output = new Array[Byte](32)
    digest.doFinal(output, 0)
    ByteString.fromArray(output)
