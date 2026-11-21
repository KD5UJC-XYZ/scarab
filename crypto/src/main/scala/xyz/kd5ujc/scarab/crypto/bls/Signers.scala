// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import xyz.kd5ujc.scarab.codec.Bytes

/** The signer bitfield of a certificate: `ceil(seats / 8)` bytes, seat `i` is bit `7 - (i mod 8)`
  * (most significant first) of byte `i / 8`. At most 8192 bytes on the wire (`bytes .size (0 ..
  * 8192)`).
  */
opaque type Signers = Bytes

object Signers:
  val maxBytes: Int = 8192
  def fromBytes(bytes: Bytes): Option[Signers] =
    if bytes.size <= maxBytes then Some(bytes) else None

  /** The bitfield of the given seats in a committee of `seats`. */
  def of(seats: Int, signing: Set[SeatId]): Either[BlsError, Signers] =
    if seats > SeatId.maxIndex + 1 then Left(BlsError.CommitteeTooLarge(seats))
    else
      signing.find(_.index >= seats) match
        case Some(s) => Left(BlsError.SignerNotInCommittee(s))
        case None =>
          val out = new Array[Byte]((seats + 7) / 8)
          signing.foreach { s =>
            out(s.index >> 3) = (out(s.index >> 3) | (1 << (7 - (s.index & 7)))).toByte
          }
          Right(Bytes.wrap(out))

  extension (s: Signers)
    def bytes: Bytes = s

    /** The set seats in ascending order, for a committee of `seats`: `bitFieldMembers` of
      * `verifyLeiosCert` (cardano-base c1e1ed8d `cardano-crypto-leios/src/Cardano/Crypto/
      * Leios.hs:332-340`). The length must be exactly `ceil(seats / 8)` bytes, else `None`
      * (`MalformedSigners`, `:305-307`); bits at or beyond `seats` name no seat and are ignored, as
      * there (`globalIx < n`).
      */
    def members(seats: Int): Option[Vector[SeatId]] =
      Option.when(s.size == (seats + 7) / 8)(
        (0 until seats).iterator
          .filter(i => s.lift(i >> 3).exists(b => (b & (0x80 >> (i & 7))) != 0))
          .map(SeatId.unsafe)
          .toVector
      )
