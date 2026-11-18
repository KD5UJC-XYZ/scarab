// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.codec.Bytes

/** The conversions and bitwise operations of CIP-121, CIP-122 and CIP-123 as plutus 1.70.0.0
  * (`507cab8c`) implements them in `PlutusCore/Bitwise.hs` (cited by line). Bit `i` of a byte
  * string of `n` bytes is bit `i mod 8` of byte `n - 1 - i / 8`: the byte string read as a
  * big-endian unsigned integer, whose bit `i` it is. The reference's loops over words and bytes
  * compute these functions of that integer.
  */
object Bitwise:
  /** `maximumInputLength` (`Bitwise.hs:81-82`, plutus 1.70.0.0). */
  val MaximumInputLength: Int = 4096

  /** `maximumOutputLength` (`Bitwise.hs:85-86`, plutus 1.70.0.0). */
  val MaximumOutputLength: Int = 8192

  /** The byte string as a big-endian unsigned integer. */
  private def unsigned(bytes: Bytes): BigInt = BigInt(1, bytes.toArray)

  /** The low `size` bytes of `n >= 0`, big-endian. */
  private def bytesOf(n: BigInt, size: Int): Bytes =
    val magnitude = n.toByteArray.dropWhile(_ == 0)
    Bytes.wrap(new Array[Byte](size - magnitude.length) ++ magnitude.takeRight(size))

  /** GHC's `integerLog2`: the floor of the base-2 logarithm, 0 for a non-positive integer. */
  private def integerLog2(n: BigInt): BigInt = if n.signum <= 0 then 0 else n.bitLength - 1

  /** `integerToByteString` (`Bitwise.hs:90-140`, `unsafeIntegerToByteString` 157-301, plutus
    * 1.70.0.0): `input` in `length` bytes (the fewest that hold it when `length` is 0), big-endian
    * when `bigEndian`. Zero in `length` 0 is the empty byte string.
    */
  def integerToByteString(
      bigEndian: Boolean,
      length: BigInt,
      input: BigInt
  ): Either[BitwiseFailure, Bytes] =
    if length.signum < 0 then Left(BitwiseFailure.NegativeLength(length))
    else if length > MaximumOutputLength then Left(BitwiseFailure.LengthTooLong(length))
    else if length.signum == 0 && integerLog2(input) >= 8 * MaximumOutputLength then
      Left(BitwiseFailure.InputTooLong(integerLog2(input) / 8 + 1))
    else if input.signum < 0 then Left(BitwiseFailure.NegativeInput(input))
    else
      val minimal = (input.bitLength + 7) / 8
      val size = if length.signum == 0 then minimal else length.toInt
      if size < minimal then Left(BitwiseFailure.NotEnoughDigits(input, length))
      else
        val be = bytesOf(input, size)
        Right(if bigEndian then be else Bytes.wrap(be.toArray.reverse))

  /** `byteStringToInteger` (`Bitwise.hs:312-332`, plutus 1.70.0.0): the unsigned integer,
    * big-endian when `bigEndian`.
    */
  def byteStringToInteger(bigEndian: Boolean, bytes: Bytes): BigInt =
    if bigEndian then unsigned(bytes) else BigInt(1, bytes.toArray.reverse)

  /** `andByteString`, `orByteString`, `xorByteString` (`Bitwise.hs:406-490`, plutus 1.70.0.0): the
    * bytes are combined from index 0; with `pad` the result is as long as the longer argument,
    * whose remaining bytes are kept, otherwise as long as the shorter. Inline, so that each
    * builtin's loop calls its own operation directly rather than through one shared call site.
    */
  inline def combine(pad: Boolean, x: Bytes, y: Bytes)(inline op: (Int, Int) => Int): Bytes =
    val (shorter, longer) = if x.size < y.size then (x, y) else (y, x)
    val s = shorter.toArray
    // the longer argument's bytes, the first combined with the shorter's in this copy before it is
    // wrapped: a loop over the bytes themselves, as the reference's (FUN-2 (a): the generic
    // Array.tabulate stored each byte through a boxed path, performance section 58)
    val out = longer.take(if pad then longer.size else s.length).toArray
    s.indices.foreach(i => out(i) = op(s(i), out(i)).toByte)
    Bytes.wrap(out)

  /** `complementByteString` (`Bitwise.hs:493-509`, plutus 1.70.0.0), in a copy before it is wrapped
    * (FUN-2 (a), as [[combine]]).
    */
  def complement(bytes: Bytes): Bytes =
    val out = bytes.toArray
    out.indices.foreach(i => out(i) = (~out(i)).toByte)
    Bytes.wrap(out)

  /** `readBit` (`Bitwise.hs:512-529`, plutus 1.70.0.0). */
  def readBit(bytes: Bytes, index: BigInt): Either[BitwiseFailure, Boolean] =
    if index.signum < 0 || index >= BigInt(bytes.size) * 8 then
      Left(BitwiseFailure.ReadBitOutOfBounds(index))
    else
      val i = index.toInt
      Right(bytes.lift(bytes.size - 1 - i / 8).exists(b => ((b >> (i % 8)) & 1) == 1))

  /** `writeBits` (`Bitwise.hs:532-571`, plutus 1.70.0.0 and 1.71.0.0): the input itself for no
    * index (`null ixs = pure bs`), otherwise every index set to `bit` in a copy, in order; the
    * first index out of bounds fails. Variants D and E first refuse an input of more than
    * [[MaximumInputLength]] bytes (`Default/Builtins.hs:2199-2214`; 2217-2236 at 1.71.0.0).
    */
  def writeBits(
      bytes: Bytes,
      indices: Vector[BigInt],
      bit: Boolean,
      limited: Boolean
  ): Either[BitwiseFailure, Bytes] =
    val bitLength = BigInt(bytes.size) * 8
    if limited && bytes.size > MaximumInputLength then
      Left(BitwiseFailure.WriteBitsInputTooLong(bytes.size))
    else if indices.isEmpty then Right(bytes)
    else
      indices.find(i => i.signum < 0 || i >= bitLength) match
        case Some(i) => Left(BitwiseFailure.WriteBitsOutOfBounds(i))
        case None    =>
          // each bit written in this copy before it is wrapped, as the reference writes its copy
          // (FUN-2 (a): a boxed map of masks and the generic Array.tabulate, performance section 58)
          val out = bytes.toArray
          indices.foreach { index =>
            val i = index.toInt
            val at = out.length - 1 - i / 8
            val mask = 1 << (i % 8)
            out(at) = (if bit then out(at) | mask else out(at) & ~mask).toByte
          }
          Right(Bytes.wrap(out))

  /** `replicateByte` (`Bitwise.hs:576-590`, plutus 1.70.0.0). */
  def replicateByte(length: BigInt, byte: Int): Either[BitwiseFailure, Bytes] =
    if length.signum < 0 then Left(BitwiseFailure.ReplicateNegativeLength(length))
    else if length > MaximumOutputLength then Left(BitwiseFailure.ReplicateLengthTooLong(length))
    else
      val out = new Array[Byte](length.toInt)
      java.util.Arrays.fill(out, byte.toByte)
      Right(Bytes.wrap(out))

  /** `shiftByteString` (`Bitwise.hs:596-612`, 689-760, plutus 1.70.0.0): towards higher bit indices
    * for a positive `move`, zeros shifted in; all zeros once the move reaches the length in bits.
    */
  def shift(bytes: Bytes, move: BigInt): Bytes =
    val bitLength = BigInt(bytes.size) * 8
    if bytes.isEmpty || move.signum == 0 then bytes
    else if move.abs >= bitLength then Bytes.wrap(new Array[Byte](bytes.size))
    else
      val n = unsigned(bytes)
      val shifted = if move.signum > 0 then n << move.toInt else n >> (-move).toInt
      bytesOf(shifted & ((BigInt(1) << bitLength.toInt) - 1), bytes.size)

  /** `rotateByteString` (`Bitwise.hs:619-638`, 762-801, plutus 1.70.0.0): by `move` modulo the
    * length in bits, towards higher bit indices.
    */
  def rotate(bytes: Bytes, move: BigInt): Bytes =
    if bytes.isEmpty then bytes
    else
      val bitLength = bytes.size * 8
      val k = (move mod BigInt(bitLength)).toInt
      if k == 0 then bytes
      else
        val n = unsigned(bytes)
        val mask = (BigInt(1) << bitLength) - 1
        bytesOf(((n << k) | (n >> (bitLength - k))) & mask, bytes.size)

  /** `countSetBits` (`Bitwise.hs:804-836`, plutus 1.70.0.0). */
  def countSetBits(bytes: Bytes): Int = bytes.foldLeft(0)((n, b) => n + Integer.bitCount(b & 0xff))

  /** `findFirstSetBit` (`Bitwise.hs:838-917`, plutus 1.70.0.0): the lowest index of a set bit, -1
    * if there is none.
    */
  def findFirstSetBit(bytes: Bytes): Int = unsigned(bytes).lowestSetBit
