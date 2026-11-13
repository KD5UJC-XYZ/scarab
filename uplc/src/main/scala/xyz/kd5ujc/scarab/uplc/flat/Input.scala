// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.Word64

/** A value read and the bit position after it. */
final case class Got[+A](value: A, at: Long)

/** The bytes of a Flat encoding, read at bit positions: the primitive decoders of the reference
  * (`PlutusCore/Flat/Decoder/Prim.hs` and `Decoder/Strict.hs`, plutus 1.70.0.0 `507cab8c`) as pure
  * functions of a position. Bits are read most significant first. A read that needs more bits than
  * remain fails with [[FlatError.NotEnoughSpace]] where the reference's `ensureBits` does, before
  * it looks at any of them.
  */
final class Input(val bytes: Bytes):
  /** The number of bits of the input. */
  val bitLength: Long = bytes.size.toLong * 8

  private def available(at: Long, needed: Long): Either[FlatError, Unit] =
    Either.cond(bitLength - at >= needed, (), FlatError.NotEnoughSpace(at, needed))

  private def byte(i: Int): Int = bytes.byteOr(i)(0)(b => b & 0xff)

  // The `n` (at most 8) bits at `at`, which are in the input.
  private def peek(at: Long, n: Int): Int =
    val i = (at >>> 3).toInt
    val used = (at & 7).toInt
    (((byte(i) << 8) | byte(i + 1)) >>> (16 - used - n)) & ((1 << n) - 1)

  /** `dBool` (`Prim.hs:200-211`). */
  def bit(at: Long): Either[FlatError, Got[Boolean]] =
    available(at, 1).map(_ => Got(peek(at, 1) == 1, at + 1))

  /** `dBEBits8 n` (`Prim.hs:224-227`): `n` bits, at most eight, as a number. */
  def bits(at: Long, n: Int): Either[FlatError, Got[Int]] =
    available(at, n.toLong).map(_ => Got(peek(at, n), at + n))

  /** `dNatural` (`dUnsigned`, `Strict.hs:242-273`): groups of seven bits, least significant first,
    * each in a byte whose top bit says whether another follows. Any number of groups is read, so a
    * value may carry groups of zeros at its top.
    */
  def natural(at: Long): Either[FlatError, Got[BigInt]] =
    // The first nine groups fill 63 bits, which a `Long` holds; a longer natural is assembled once
    // from all of its groups.
    @tailrec def small(from: Long, shift: Int, acc: Long): Either[FlatError, Got[BigInt]] =
      available(from, 8) match
        case Left(e) => Left(e)
        case Right(_) =>
          val group = peek(from, 8)
          val value = acc | ((group & 0x7f).toLong << shift)
          if group < 0x80 then Right(Got(BigInt(value), from + 8))
          else if shift < 56 then small(from + 8, shift + 7, value)
          else large(from + 8, 9)
    @tailrec def large(from: Long, count: Int): Either[FlatError, Got[BigInt]] =
      available(from, 8) match
        case Left(e) => Left(e)
        case Right(_) =>
          if peek(from, 8) < 0x80 then Right(Got(groups(at, count + 1), from + 8))
          else large(from + 8, count + 1)
    small(at, 0, 0L)

  // The natural whose `count` groups start at `at` (all in the input), from its big-endian bytes.
  private def groups(at: Long, count: Int): BigInt =
    val bitCount = count * 7
    val length = (bitCount + 7) / 8
    def valueBit(j: Int): Int = (peek(at + 8L * (j / 7), 8) >>> (j % 7)) & 1
    val magnitude = Array.tabulate[Byte](length) { k =>
      val low = 8 * (length - 1 - k)
      (0 until 8)
        .foldLeft(0) { (b, i) =>
          val j = low + i
          if j < bitCount then b | (valueBit(j) << i) else b
        }
        .toByte
    }
    BigInt(new java.math.BigInteger(1, magnitude))

  /** `dInteger` (`Strict.hs:113-114`): a natural, zig-zag decoded (`zagZig`: an even `n` is
    * `n / 2`, an odd one `-(n + 1) / 2`, `PlutusCore/Flat/Data/ZigZag.hs`).
    */
  def integer(at: Long): Either[FlatError, Got[BigInt]] =
    natural(at).map(g =>
      Got(if g.value.testBit(0) then -((g.value + 1) >> 1) else g.value >> 1, g.at)
    )

  /** `dWord64` (`Strict.hs:165-184`): nine groups as for a natural (`wordStep`), then a tenth that
    * may hold bit 63 alone and may not continue (`lastStep`, `Strict.hs:228-239`). Groups of zeros
    * at the top are accepted.
    */
  def word64(at: Long): Either[FlatError, Got[Word64]] =
    @tailrec def go(from: Long, index: Int, acc: Long): Either[FlatError, Got[Word64]] =
      available(from, 8) match
        case Left(e) => Left(e)
        case Right(_) =>
          val group = peek(from, 8)
          val payload = (group & 0x7f).toLong
          if index < 9 then
            val value = acc | (payload << (7 * index))
            if group < 0x80 then Right(Got(Word64.fromBits(value), from + 8))
            else go(from + 8, index + 1, value)
          else if group >= 0x80 || payload > 1 then Left(FlatError.Word64Overflow(from))
          else Right(Got(Word64.fromBits(acc | (payload << 63)), from + 8))
    go(at, 0, 0L)

  /** `dFiller` (`Strict.hs:301-304`) and the generated decoder of `Filler` (`Filler.hs:27-35`, a 0
    * bit for `FillerBit`, a 1 bit for `FillerEnd`): zero bits up to and including a 1, as many as
    * there are. The position after it.
    */
  def filler(at: Long): Either[FlatError, Long] =
    @tailrec def go(from: Long): Either[FlatError, Long] =
      if from >= bitLength then Left(FlatError.NotEnoughSpace(from, 1))
      else if peek(from, 1) == 1 then Right(from + 1)
      else go(from + 1)
    go(at)

  /** `dByteString` (`Strict.hs:317-318`): a filler, then (`getChunksInfo`, `Prim.hs:441-459`) a
    * byte boundary or [[FlatError.Misaligned]], then chunks of a length byte and that many bytes up
    * to one of length 0. Chunks of any length are accepted, the canonical 255 or not.
    */
  def byteString(at: Long): Either[FlatError, Got[Bytes]] =
    @tailrec def chunks(from: Int, read: List[Bytes]): Either[FlatError, Got[Bytes]] =
      if from >= bytes.size then Left(FlatError.NotEnoughSpace(from.toLong * 8, 8))
      else
        val length = byte(from)
        if length == 0 then Right(Got(joined(read.reverse), (from.toLong + 1) * 8))
        else if from + 1 + length > bytes.size then
          Left(FlatError.NotEnoughSpace((from.toLong + 1) * 8, length.toLong * 8))
        else chunks(from + 1 + length, bytes.slice(from + 1, from + 1 + length) :: read)
    filler(at).flatMap { start =>
      if (start & 7) != 0 then Left(FlatError.Misaligned(start))
      else chunks((start >>> 3).toInt, Nil)
    }

  // The chunks as one value that holds only its own bytes.
  private def joined(chunks: List[Bytes]): Bytes = chunks match
    case Nil        => Bytes.empty
    case one :: Nil => one.compact
    case several    => Bytes.wrap(Array.concat(several.map(_.toArray)*))
