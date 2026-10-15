// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

/** A committee seat index: the `voter_id` of a vote and the bit position in the signers bitfield.
  * Fits the 16 bits of the Blueprint's `word16` (so a committee has at most 65536 seats).
  */
opaque type SeatId = Int

object SeatId:
  val maxIndex: Int = 0xffff
  def fromInt(i: Int): Either[BlsError, SeatId] =
    if i >= 0 && i <= maxIndex then Right(i) else Left(BlsError.SeatOutOfRange(i))

  /** For indices already known to be in range. */
  private[bls] def unsafe(i: Int): SeatId = i
  given Ordering[SeatId] = Ordering.Int
  extension (s: SeatId) def index: Int = s
