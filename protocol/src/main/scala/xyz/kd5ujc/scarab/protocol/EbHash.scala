// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

/** The reference's endorser-block hash wrapper. Its wire constructor retains any bytes;
  * cryptographic hash length is a separate boundary (ouroboros-consensus `7b239a16`,
  * Leios/Types.hs:101-112).
  */
opaque type EbHash = Bytes

object EbHash:
  def fromBytes(bytes: Bytes): EbHash = bytes
  extension (h: EbHash) def bytes: Bytes = h

/** The distinct transaction hash in an endorser block (`TxHash`, Leios/Types.hs:143-146,315-322 at
  * ouroboros-consensus `7b239a16`). Its CBOR decoder likewise retains arbitrary bytes.
  */
opaque type LeiosTxHash = Bytes

object LeiosTxHash:
  def fromBytes(bytes: Bytes): LeiosTxHash = bytes
  extension (h: LeiosTxHash) def bytes: Bytes = h
