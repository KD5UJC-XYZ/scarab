// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import xyz.kd5ujc.scarab.codec.Bytes

/** The 32-byte hash of the ranking block that announced an endorser block, which votes and
  * certificates sign.
  */
opaque type RbHash = Bytes

object RbHash:
  val size: Int = 32
  def fromBytes(bytes: Bytes): Option[RbHash] = if bytes.size == size then Some(bytes) else None

  /** `0x58 0x20`: the CBOR head of a byte string of 32 bytes. */
  private val head: Bytes = Bytes.wrap(Array[Byte](0x58, 0x20))

  extension (h: RbHash)
    def bytes: Bytes = h

    /** The message a vote's or a certificate's signature is over: `getSignableRepresentation` of
      * `RbHash`, the hash's CBOR encoding `toStrictByteString (encodeRbHash h)`, a byte string of
      * 32 bytes behind its two-byte head (ouroboros-consensus 2416493b
      * `Ouroboros/Consensus/Leios/Types.hs:122-123, 138-141`), not the 32 bytes alone.
      */
    def signable: Bytes = head ++ h
