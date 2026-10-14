// SPDX-License-Identifier: Apache-2.0
package lab.bls

import lab.cbor.Bytes

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

    /** The set seats in ascending order, for a committee of `seats`. Strict: the length must be
      * exactly `ceil(seats / 8)` and the padding bits (index >= seats) clear.
      */
    def members(seats: Int): Either[BlsError, Vector[SeatId]] =
      val expected = (seats + 7) / 8
      if seats > SeatId.maxIndex + 1 then Left(BlsError.CommitteeTooLarge(seats))
      else if s.size != expected then Left(BlsError.SignersWrongLength(expected, s.size))
      else
        val out = Vector.newBuilder[SeatId]
        var padding = false
        var i = 0
        while i < expected * 8 do
          if (s(i >> 3) & (1 << (7 - (i & 7)))) != 0 then
            if i >= seats then padding = true else out += SeatId.unsafe(i)
          i += 1
        if padding then Left(BlsError.SignersPaddingSet) else Right(out.result())
