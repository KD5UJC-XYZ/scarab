// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.io.ByteArrayOutputStream
import scala.annotation.tailrec
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data

/** The `serialiseData` builtin: the CBOR encoding of `Data` that `PlutusCore.Data.encodeData` of
  * the reference writes.
  *
  *   - `Constr i`: tag `121 + i` for `0 <= i < 7`, tag `1280 + i - 7` for `7 <= i < 128`, else tag
  *     102 and a two-element array of the index (an unsigned 64-bit integer, or any other index as
  *     an integer, which uses the big-number tags outside the 64-bit range over one definite byte
  *     string of any length, never chunked) and the fields;
  *   - lists are indefinite-length when non-empty and `0x80` when empty; maps are definite;
  *   - integers within 64 bits are plain CBOR integers, others tag 2 or 3 over a byte string;
  *   - byte strings of up to 64 bytes are definite, longer ones indefinite in chunks of 64 bytes.
  *
  * Scalus 1.2.0 wrote the index of a constructor outside `[0, 2^63)` through a `Long` (fixed in
  * 1.3.0, which agrees with this encoder on every `Data` of the property test); Scalus 1.3.0 still
  * refuses more than 1000 levels of nesting, the reference does not. This encoder is the one the
  * machine runs (`ScalusEvaluator.CorrectedVm`).
  */
object DataSerialiser:
  private val Word64Max = (BigInt(1) << 64) - 1

  def serialise(data: Data): Array[Byte] =
    val out = ByteArrayOutputStream()
    write(out, data)
    out.toByteArray

  /** An open list or map: what is still to write, and the byte that closes it (-1 for none). */
  private final case class Open(rest: List[Data], close: Int)

  /** The nesting is walked on the heap: the reference has no depth limit, and a datum can be as
    * deep as the block that carries it is long.
    */
  private def write(out: ByteArrayOutputStream, root: Data): Unit =
    def list(items: PList[Data], below: List[Open]): List[Open] =
      val xs = PList.asScala(items).toList
      if xs.isEmpty then
        out.write(0x80)
        below
      else
        out.write(0x9f)
        Open(xs, 0xff) :: below
    // writes what `data` begins with; the stack it returns has what `data` opened on top
    def emit(data: Data, below: List[Open]): List[Open] = data match
      case Data.Constr(i, fields) =>
        if i >= 0 && i < 7 then head(out, 6, BigInt(121) + i)
        else if i >= 7 && i < 128 then head(out, 6, BigInt(1280) + i - 7)
        else
          head(out, 6, 102)
          head(out, 4, 2)
          index(out, i)
        list(fields, below)
      case Data.Map(entries) =>
        val es = PList.asScala(entries).toList
        head(out, 5, BigInt(es.size))
        Open(es.flatMap((k, v) => List(k, v)), -1) :: below
      case Data.List(items) => list(items, below)
      case Data.I(v) =>
        integer(out, v)
        below
      case Data.B(b) =>
        bytes(out, b.bytes)
        below
    @tailrec def walk(stack: List[Open]): Unit = stack match
      case Open(next :: rest, close) :: below => walk(emit(next, Open(rest, close) :: below))
      case Open(Nil, close) :: below =>
        if close >= 0 then out.write(close)
        walk(below)
      case Nil => ()
    walk(emit(root, Nil))

  /** The index of a constructor in tag 102 is written by cborg's `encodeInteger`: a plain head
    * within 64 bits, otherwise tag 2 or 3 over one definite byte string of the minimal magnitude,
    * however long (`PlutusCore/Data.hs` `encodeData`; cborg `Codec.CBOR.Write`, `integerMP` and
    * `bigNatMP`). Only `Data.I` goes through the chunking `encodeBs`.
    */
  private def index(out: ByteArrayOutputStream, v: BigInt): Unit =
    if v >= 0 && v <= Word64Max then head(out, 0, v)
    else if v < 0 && -1 - v <= Word64Max then head(out, 1, -1 - v)
    else
      val (tag, digits) = if v >= 0 then (2, magnitude(v)) else (3, magnitude(-1 - v))
      head(out, 6, tag)
      head(out, 2, BigInt(digits.length))
      out.write(digits)

  private def integer(out: ByteArrayOutputStream, v: BigInt): Unit =
    if v >= 0 && v <= Word64Max then head(out, 0, v)
    else if v < 0 && -1 - v <= Word64Max then head(out, 1, -1 - v)
    else if v >= 0 then
      head(out, 6, 2)
      bytes(out, magnitude(v))
    else
      head(out, 6, 3)
      bytes(out, magnitude(-1 - v))

  private def magnitude(v: BigInt): Array[Byte] =
    val b = v.toByteArray
    if b.length > 1 && b(0) == 0 then b.drop(1) else b

  private def bytes(out: ByteArrayOutputStream, b: Array[Byte]): Unit =
    if b.length <= 64 then
      head(out, 2, BigInt(b.length))
      out.write(b)
    else
      out.write(0x5f)
      b.grouped(64).foreach { chunk =>
        head(out, 2, BigInt(chunk.length))
        out.write(chunk)
      }
      out.write(0xff)

  /** A CBOR head: major type and an argument of up to 64 bits, in the shortest form. */
  private def head(out: ByteArrayOutputStream, major: Int, arg: BigInt): Unit =
    val m = major << 5
    val n = arg.toLong
    if arg < 24 then out.write(m | n.toInt)
    else if arg < 256 then
      out.write(m | 24)
      out.write(n.toInt)
    else if arg < 65536 then
      out.write(m | 25)
      out.write((n >> 8).toInt)
      out.write(n.toInt)
    else if arg < (BigInt(1) << 32) then
      out.write(m | 26)
      (3 to 0 by -1).foreach(s => out.write((n >> (8 * s)).toInt))
    else
      out.write(m | 27)
      (7 to 0 by -1).foreach(s => out.write((n >> (8 * s)).toInt))
