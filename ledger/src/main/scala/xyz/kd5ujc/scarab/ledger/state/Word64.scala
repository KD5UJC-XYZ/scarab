// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Order, Show}

/** `Word64`: an unsigned 64-bit integer, the representation of Haskell's `EpochNo`, `SlotNo`,
  * `BlockNo` and `CompactForm Coin` (`Slot.hs:44,135`, `Block.hs:22`, `Coin.hs:148`; the first two
  * from cardano-slotting 0.2.2.0).
  *
  * Held in a `Long` as its two's-complement bits, so a value from 2^63 up reads as a negative
  * `Long`: nothing outside this object may look at the bits as a signed number. The order and the
  * rendering are the unsigned ones; the only ways in are [[Word64.fromBigInt]] (checked) and
  * [[Word64.fromUnsigned]] (every bit pattern is a word, so total).
  */
opaque type Word64 = Long

object Word64:
  val zero: Word64 = 0L
  val max: Word64 = -1L // 2^64 - 1

  private val modulus: BigInt = BigInt(1) << 64

  /** The word `n`, or why not: `Negative` below 0, `OutOfRange` from 2^64 up. */
  def fromBigInt(kind: VocabError.Kind, n: BigInt): Either[VocabError, Word64] =
    if n.signum < 0 then Left(VocabError.Negative(kind, n))
    else if n >= modulus then Left(VocabError.OutOfRange(kind, n))
    else Right(n.toLong) // the low 64 bits: exact, as 0 <= n < 2^64

  /** The word whose bits are `bits` (so `-1` is 2^64 - 1). Total. */
  def fromUnsigned(bits: Long): Word64 = bits

  /** Native Word64 arithmetic modulo 2^64, for rules that explicitly use it. Checked constructors
    * and [[Word64.successor]] retain their separate refusal contracts.
    */
  private[ledger] def wrappingAdd(left: Word64, right: Word64): Word64 = left + right

  private[ledger] def wrappingSubtract(left: Word64, right: Word64): Word64 = left - right

  extension (w: Word64)
    /** The two's-complement bits, for a codec that writes the word as 8 bytes. */
    def bits: Long = w

    def toBigInt: BigInt = if w >= 0 then BigInt(w) else BigInt(w) + modulus

    def isZero: Boolean = w == 0L

    /** `w + 1`, or `None` at 2^64 - 1 (Haskell's `Word64` would wrap; the mirror refuses). */
    def successor: Option[Word64] = Option.unless(w == -1L)(w + 1)

    /** The decimal rendering (`Long.toUnsignedString`). */
    def decimal: String = java.lang.Long.toUnsignedString(w)

  /** The unsigned order, as Haskell's `Ord Word64`. Explicit: the types that wrap a `Word64` are
    * aliases of it inside `Vocabulary.scala`, so they must not summon the given.
    */
  val order: Order[Word64] = Order.from(java.lang.Long.compareUnsigned(_, _))
  val show: Show[Word64] = Show.show(_.decimal)

  given Order[Word64] = order
  given Show[Word64] = show
