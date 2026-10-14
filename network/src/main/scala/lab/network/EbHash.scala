// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes

/** A 32-byte Blake2b-256 hash named on the Leios mini-protocols: an endorser block or one of its
  * transactions (`base.hash`).
  */
opaque type EbHash = Bytes

object EbHash:
  val size: Int = 32
  def fromBytes(bytes: Bytes): Option[EbHash] = if bytes.size == size then Some(bytes) else None
  extension (h: EbHash) def bytes: Bytes = h
