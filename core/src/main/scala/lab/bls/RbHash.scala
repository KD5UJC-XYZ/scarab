// SPDX-License-Identifier: Apache-2.0
package lab.bls

import lab.cbor.Bytes

/** The 32-byte hash of the ranking block that announced an endorser block: the signed message. */
opaque type RbHash = Bytes

object RbHash:
  val size: Int = 32
  def fromBytes(bytes: Bytes): Option[RbHash] = if bytes.size == size then Some(bytes) else None
  extension (h: RbHash) def bytes: Bytes = h
