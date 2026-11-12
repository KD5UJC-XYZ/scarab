// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.*
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation
import xyz.kd5ujc.scarab.uplc.flat.FlatWriter
import xyz.kd5ujc.scarab.uplc.flat.FlatWriter.{bits, tagList, typeTags}

/** The bytes the ledger holds for a generated program: [[FlatWriter]]'s encoding, except that a
  * `data` constant is the reference's `serialise` of it (`encodeData`, `PlutusCore/Data.hs`, with
  * byte strings over 64 bytes in chunks), which [[DataSerialisation]] writes, and a list or array
  * of BLS12-381 values may be written when it is empty. Every other BLS12-381 constant has no Flat
  * encoding; the generator does not make one.
  */
object ScriptWriter:
  // The eight bits of each byte, so that a long byte string is written without a number per bit.
  private val octets: Vector[String] = Vector.tabulate(256)(bits(_, 8))

  /** [[FlatWriter.byteString]] without its allocation per bit: a filler, then chunks of at most 255
    * bytes, each after its length, then an empty chunk.
    */
  def byteString(written: Int, b: Array[Byte]): String =
    val out = new StringBuilder(FlatWriter.filler(written))
    b.grouped(255).foreach { chunk =>
      out.append(octets(chunk.length))
      chunk.foreach(x => out.append(octets(x & 0xff)))
    }
    out.append(octets(0)).toString

  /** [[FlatWriter.natural]] in time linear in the number's size: its seven-bit groups, least
    * significant first, read by bit rather than by shifting the number.
    */
  def natural(n: BigInt): String =
    val groups = ((n.bitLength + 6) / 7).max(1)
    val out = new StringBuilder
    (0 until groups).foreach { g =>
      val group =
        (0 until 7).foldLeft(0)((acc, k) => if n.testBit(7 * g + k) then acc | (1 << k) else acc)
      out.append(octets(if g < groups - 1 then group | 0x80 else group))
    }
    out.toString

  def integer(n: BigInt): String = natural(if n >= 0 then n << 1 else ((-n) << 1) - 1)

  /** Bits, then a filler that ends them on a byte boundary, as bytes. */
  def padded(bits: String): Array[Byte] =
    val all = bits + FlatWriter.filler(bits.length)
    Array.tabulate(all.length / 8)(i =>
      (0 until 8).foldLeft(0)((acc, j) => (acc << 1) | (all.charAt(8 * i + j) - '0')).toByte
    )

  def value(written: Int, c: Constant): String = c match
    case Constant.Integer(n)    => integer(n)
    case Constant.ByteString(b) => byteString(written, b.toArray)
    case Constant.String(t)     => byteString(written, t.utf8.toArray)
    case Constant.Data(d)       => byteString(written, DataSerialisation.serialise(d).toArray)
    case l: Constant.List       => items(written, l.items)
    case a: Constant.Array      => items(written, a.items)
    case Constant.Pair(a, b) =>
      val first = value(written, a)
      first + value(written + first.length, b)
    case other => FlatWriter.value(written, other)

  private def items(written: Int, xs: Vector[Constant]): String =
    xs.foldLeft(new StringBuilder) { (out, x) =>
      out.append("1")
      out.append(value(written + out.length, x))
    }.append("0")
      .toString

  def constant(written: Int, c: Constant): String =
    val header = bits(4, 4) + tagList(typeTags(c.typeOf))
    header + value(written + header.length, c)

  private enum Item:
    case Emit(bits: String)
    case Encode(term: Term)

  /** The bits of a term written after `written` bits, from a work list. */
  def term(written: Int, t: Term): String =
    @tailrec def go(todo: List[Item], out: StringBuilder): String = todo match
      case Nil                  => out.toString
      case Item.Emit(b) :: rest => go(rest, out.append(b))
      case Item.Encode(t) :: rest =>
        t.unfix match
          case TermF.Var(i)    => go(rest, out.append(bits(0, 4)).append(natural(i.index.toBigInt)))
          case TermF.Delay(x)  => go(Item.Encode(x) :: rest, out.append(bits(1, 4)))
          case TermF.LamAbs(x) => go(Item.Encode(x) :: rest, out.append(bits(2, 4)))
          case TermF.Apply(f, a) =>
            go(Item.Encode(f) :: Item.Encode(a) :: rest, out.append(bits(3, 4)))
          case TermF.Constant(c) => go(rest, out.append(constant(written + out.length, c)))
          case TermF.Force(x)    => go(Item.Encode(x) :: rest, out.append(bits(5, 4)))
          case TermF.Error       => go(rest, out.append(bits(6, 4)))
          case TermF.Builtin(f)  => go(rest, out.append(bits(7, 4)).append(bits(f.flatTag, 7)))
          case TermF.Constr(tag, fields) =>
            val list =
              fields.toList.flatMap(f => List(Item.Emit("1"), Item.Encode(f))) :+ Item.Emit("0")
            go(list ::: rest, out.append(bits(8, 4)).append(natural(tag.toBigInt)))
          case TermF.Case(s, branches) =>
            val list =
              branches.toList.flatMap(b => List(Item.Emit("1"), Item.Encode(b))) :+ Item.Emit("0")
            go(Item.Encode(s) :: list ::: rest, out.append(bits(9, 4)))
    go(List(Item.Encode(t)), StringBuilder())

  def flat(program: Program): Bytes =
    val version = natural(program.version.major) + natural(program.version.minor) +
      natural(program.version.patch)
    Bytes.wrap(padded(version + term(version.length, program.term)))

  /** The script as the ledger holds it: a CBOR byte string around the Flat program. */
  def script(program: Program): Bytes = Bytes.wrap(FlatWriter.cborBytes(flat(program).toArray))

  /** A script argument as the oracle reads it: the CBOR of the `Data`. */
  def argument(d: PlutusData): Bytes = DataSerialisation.serialise(d)
