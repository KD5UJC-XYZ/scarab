// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.PlutusData

/** The CBOR encoding of `Data` that `serialiseData` returns (`BSL.toStrict . serialise`,
  * `PlutusCore/Default/Builtins.hs:1850-1856`): `encodeData` of `PlutusCore/Data.hs:144-196`,
  * plutus 1.70.0.0 (`507cab8c`).
  *
  *   - A constructor of index 0 to 6 is tag 121 + i, of 7 to 127 tag 1280 + (i - 7), and any other
  *     tag 102 over a definite pair of the index and the fields. That index is written by
  *     `encodeWord64` when it fits a `Word64` and by cborg's `encodeInteger` otherwise: a negative
  *     integer down to -2^64, else a big number with its magnitude in one definite byte string,
  *     however long (lines 152-163).
  *   - The fields of a constructor and the items of a list are `encode` of a Haskell list: an empty
  *     array (`80`) when there are none, else an indefinite array (`9f` ... `ff`).
  *   - A map is a definite map of its entries in order (lines 164-166).
  *   - An integer that fits 64 bits is a plain integer; any other is tag 2 or 3 over its magnitude
  *     (`integerToBytesBE`, `PlutusCore/Bitwise.hs:304-306`) written as a byte string (lines
  *     170-182).
  *   - A byte string of at most 64 bytes is definite; a longer one is an indefinite byte string of
  *     64-byte chunks, the last one shorter (lines 184-200).
  *
  * The list encoding (`defaultEncodeList`, `serialise`) and the integer encoding (cborg) are not in
  * the vendored source; the reference bytes of `fixtures/reference/plutus-serialise-data` pin both.
  * Every head has the shortest width. The walk keeps the items still to write on the heap, so a
  * value nested as deep as a script can build is written on any thread stack.
  */
object DataSerialisation:
  def serialise(data: PlutusData): Bytes =
    Bytes.wrap(Array.concat(write(List(Work.Encode(data)), Nil).reverse*))

  /** An item still to write: some bytes, or a value. */
  private enum Work:
    case Raw(bytes: Array[Byte])
    case Encode(data: PlutusData)

  private val Break: Array[Byte] = Array(0xff.toByte)
  private val EmptyArray: Array[Byte] = Array(0x80.toByte)
  private val IndefiniteArray: Array[Byte] = Array(0x9f.toByte)
  private val IndefiniteBytes: Array[Byte] = Array(0x5f.toByte)
  private val PairHead: Array[Byte] = Array(0x82.toByte)
  private val PositiveBig: Array[Byte] = Array(0xc2.toByte)
  private val NegativeBig: Array[Byte] = Array(0xc3.toByte)

  /** The pieces written so far, last first. */
  @tailrec private def write(pending: List[Work], written: List[Array[Byte]]): List[Array[Byte]] =
    pending match
      case Nil                 => written
      case Work.Raw(b) :: rest => write(rest, b :: written)
      case Work.Encode(d) :: rest =>
        val (heads, children) = open(d)
        write(children ::: rest, heads.reverse ::: written)

  /** The bytes a value starts with, in order, and the items to write after them. */
  private def open(d: PlutusData): (List[Array[Byte]], List[Work]) = d match
    case PlutusData.Constr(i, fields) =>
      val (start, items) = listItems(fields)
      (constructor(i) ::: List(start), items)
    case PlutusData.Map(entries) =>
      (
        List(head(5, entries.size.toLong)),
        entries.foldRight(List.empty[Work])((kv, rest) =>
          Work.Encode(kv._1) :: Work.Encode(kv._2) :: rest
        )
      )
    case PlutusData.List(items) =>
      val (start, rest) = listItems(items)
      (List(start), rest)
    case PlutusData.I(n) => (integer(n), Nil)
    case PlutusData.B(b) => (byteString(b), Nil)

  /** `encode` of a Haskell list of `Data`: `80`, or `9f`, the items, `ff`. */
  private def listItems(items: Vector[PlutusData]): (Array[Byte], List[Work]) =
    if items.isEmpty then (EmptyArray, Nil)
    else
      (
        IndefiniteArray,
        items.foldRight(List[Work](Work.Raw(Break)))((d, rest) => Work.Encode(d) :: rest)
      )

  private def constructor(i: BigInt): List[Array[Byte]] =
    if i.signum >= 0 && i < 7 then List(head(6, 121L + i.toLong))
    else if i >= 7 && i < 128 then List(head(6, 1280L + i.toLong - 7))
    else head(6, 102) :: PairHead :: wideIndex(i)

  /** `encodeWord64`, or cborg's `encodeInteger` beyond it: a big number in one definite byte
    * string.
    */
  private def wideIndex(i: BigInt): List[Array[Byte]] =
    if fitsWord64(i) then List(head(0, i.longValue))
    else if fitsWord64(-1 - i) then List(head(1, (-1 - i).longValue))
    else if i.signum > 0 then
      val m = magnitude(i)
      List(PositiveBig, head(2, m.length.toLong), m)
    else
      val m = magnitude(-1 - i)
      List(NegativeBig, head(2, m.length.toLong), m)

  /** `encodeInteger` of `PlutusCore/Data.hs:170-182`. */
  private def integer(n: BigInt): List[Array[Byte]] =
    if fitsWord64(n) then List(head(0, n.longValue))
    else if fitsWord64(-1 - n) then List(head(1, (-1 - n).longValue))
    else if n.signum > 0 then PositiveBig :: byteString(Bytes.wrap(magnitude(n)))
    else NegativeBig :: byteString(Bytes.wrap(magnitude(-1 - n)))

  /** `encodeBs` of `PlutusCore/Data.hs:184-200`. */
  private def byteString(b: Bytes): List[Array[Byte]] =
    if b.size <= 64 then List(head(2, b.size.toLong), b.toArray)
    else
      val chunks = (0 until b.size by 64).toList.flatMap { at =>
        val chunk = b.slice(at, at + 64)
        List(head(2, chunk.size.toLong), chunk.toArray)
      }
      IndefiniteBytes :: chunks ::: List(Break)

  private def fitsWord64(n: BigInt): Boolean = n.signum >= 0 && n.bitLength <= 64

  /** The big-endian bytes of `n > 0` without leading zeros. */
  private def magnitude(n: BigInt): Array[Byte] =
    val twos = n.toByteArray
    if twos.length > 1 && twos(0) == 0 then twos.drop(1) else twos

  /** A head of `major` with argument `n`, read as an unsigned 64-bit word, in the shortest width.
    */
  private def head(major: Int, n: Long): Array[Byte] =
    val m = major << 5
    def below(bound: Long) = java.lang.Long.compareUnsigned(n, bound) < 0
    def bigEndian(width: Int): Array[Byte] =
      Array.tabulate(width)(k => (n >>> (8 * (width - 1 - k))).toByte)
    if below(24) then Array((m | n.toInt).toByte)
    else if below(0x100L) then (m | 24).toByte +: bigEndian(1)
    else if below(0x10000L) then (m | 25).toByte +: bigEndian(2)
    else if below(0x100000000L) then (m | 26).toByte +: bigEndian(4)
    else (m | 27).toByte +: bigEndian(8)
