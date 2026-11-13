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
  /** `maximumInputLength` (`Bitwise.hs:81-82`). */
  val MaximumInputLength: Int = 4096

  /** `maximumOutputLength` (`Bitwise.hs:85-86`). */
  val MaximumOutputLength: Int = 8192

  /** The byte string as a big-endian unsigned integer. */
  private def unsigned(bytes: Bytes): BigInt = BigInt(1, bytes.toArray)

  /** The low `size` bytes of `n >= 0`, big-endian. */
  private def bytesOf(n: BigInt, size: Int): Bytes =
    val magnitude = n.toByteArray.dropWhile(_ == 0)
    Bytes.wrap(Array.fill(size - magnitude.length)(0.toByte) ++ magnitude.takeRight(size))

  /** GHC's `integerLog2`: the floor of the base-2 logarithm, 0 for a non-positive integer. */
  private def integerLog2(n: BigInt): BigInt = if n.signum <= 0 then 0 else n.bitLength - 1

  /** `integerToByteString` (`Bitwise.hs:90-140`, `unsafeIntegerToByteString` 157-301): `input` in
    * `length` bytes (the fewest that hold it when `length` is 0), big-endian when `bigEndian`. Zero
    * in `length` 0 is the empty byte string.
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

  /** `byteStringToInteger` (`Bitwise.hs:312-332`): the unsigned integer, big-endian when
    * `bigEndian`.
    */
  def byteStringToInteger(bigEndian: Boolean, bytes: Bytes): BigInt =
    if bigEndian then unsigned(bytes) else BigInt(1, bytes.toArray.reverse)

  /** `andByteString`, `orByteString`, `xorByteString` (`Bitwise.hs:406-490`): the bytes are
    * combined from index 0; with `pad` the result is as long as the longer argument, whose
    * remaining bytes are kept, otherwise as long as the shorter.
    */
  def combine(pad: Boolean, x: Bytes, y: Bytes)(op: (Int, Int) => Int): Bytes =
    val (shorter, longer) = if x.size < y.size then (x, y) else (y, x)
    val s = shorter.toArray
    val l = longer.toArray
    val size = if pad then l.length else s.length
    Bytes.wrap(Array.tabulate(size)(i => if i < s.length then op(s(i), l(i)).toByte else l(i)))

  /** `complementByteString` (`Bitwise.hs:493-509`). */
  def complement(bytes: Bytes): Bytes = Bytes.wrap(bytes.toArray.map(b => (~b).toByte))

  /** `readBit` (`Bitwise.hs:512-529`). */
  def readBit(bytes: Bytes, index: BigInt): Either[BitwiseFailure, Boolean] =
    if index.signum < 0 || index >= BigInt(bytes.size) * 8 then
      Left(BitwiseFailure.ReadBitOutOfBounds(index))
    else
      val i = index.toInt
      Right(bytes.lift(bytes.size - 1 - i / 8).exists(b => ((b >> (i % 8)) & 1) == 1))

  /** `writeBits` (`Bitwise.hs:532-571`): every index set to `bit`, in order; the first index out of
    * bounds fails. Variants D and E first refuse an input of more than [[MaximumInputLength]] bytes
    * (`Default/Builtins.hs:2199-2214`).
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
    else
      indices.find(i => i.signum < 0 || i >= bitLength) match
        case Some(i) => Left(BitwiseFailure.WriteBitsOutOfBounds(i))
        case None    =>
          // the bits of each byte that the indices name
          val masks = indices.foldLeft(Map.empty[Int, Int]) { (acc, index) =>
            val i = index.toInt
            val at = bytes.size - 1 - i / 8
            acc.updated(at, acc.getOrElse(at, 0) | (1 << (i % 8)))
          }
          val original = bytes.toArray
          Right(Bytes.wrap(Array.tabulate(original.length) { at =>
            val b = original(at) & 0xff
            masks.get(at).fold(b)(m => if bit then b | m else b & ~m).toByte
          }))

  /** `replicateByte` (`Bitwise.hs:576-590`). */
  def replicateByte(length: BigInt, byte: Int): Either[BitwiseFailure, Bytes] =
    if length.signum < 0 then Left(BitwiseFailure.ReplicateNegativeLength(length))
    else if length > MaximumOutputLength then Left(BitwiseFailure.ReplicateLengthTooLong(length))
    else Right(Bytes.wrap(Array.fill(length.toInt)(byte.toByte)))

  /** `shiftByteString` (`Bitwise.hs:596-612`, 689-760): towards higher bit indices for a positive
    * `move`, zeros shifted in; all zeros once the move reaches the length in bits.
    */
  def shift(bytes: Bytes, move: BigInt): Bytes =
    val bitLength = BigInt(bytes.size) * 8
    if bytes.isEmpty || move.signum == 0 then bytes
    else if move.abs >= bitLength then Bytes.wrap(Array.fill(bytes.size)(0.toByte))
    else
      val n = unsigned(bytes)
      val shifted = if move.signum > 0 then n << move.toInt else n >> (-move).toInt
      bytesOf(shifted & ((BigInt(1) << bitLength.toInt) - 1), bytes.size)

  /** `rotateByteString` (`Bitwise.hs:619-638`, 762-801): by `move` modulo the length in bits,
    * towards higher bit indices.
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

  /** `countSetBits` (`Bitwise.hs:804-836`). */
  def countSetBits(bytes: Bytes): Int = bytes.foldLeft(0)((n, b) => n + Integer.bitCount(b & 0xff))

  /** `findFirstSetBit` (`Bitwise.hs:838-917`): the lowest index of a set bit, -1 if there is none.
    */
  def findFirstSetBit(bytes: Bytes): Int = unsigned(bytes).lowestSetBit
