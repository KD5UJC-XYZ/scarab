// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.*

/** A Flat encoder for tests, written from the reference's encoders (`encodeTerm` and
  * `encodeProgram`, `UntypedPlutusCore/Core/Instance/Flat.hs:120-131`, 231; `encodeUni`,
  * `PlutusCore/Default/Universe.hs:1068-1081`; the primitive encoders of `PlutusCore/Flat/Encoder`),
  * and the bits of hand-made inputs the canonical encoder cannot produce. Bits are a string of `0`
  * and `1`; a term is written from a work list, so a deep one does not recurse.
  */
object FlatWriter:
  def bits(value: BigInt, width: Int): String =
    (width - 1 to 0 by -1).map(i => if value.testBit(i) then '1' else '0').mkString

  def bits(value: Int, width: Int): String = bits(BigInt(value), width)

  /** The canonical encoding of a natural: its seven-bit groups, least significant first. */
  def natural(n: BigInt): String =
    @tailrec def go(rest: BigInt, out: StringBuilder): String =
      val group = (rest & 0x7f).toInt
      val next = rest >> 7
      if next == 0 then out.append(bits(group, 8)).toString
      else go(next, out.append(bits(group | 0x80, 8)))
    go(n, StringBuilder())

  def integer(n: BigInt): String = natural(if n >= 0 then n << 1 else ((-n) << 1) - 1)

  /** A natural written as exactly these group bytes, padded or not. */
  def groups(bytes: Int*): String = bytes.map(bits(_, 8)).mkString

  /** Zeros up to a 1 that ends a byte, after `written` bits (`eFiller`). */
  def filler(written: Int): String = "0" * (7 - written % 8) + "1"

  /** A byte string after `written` bits: a filler, then chunks of at most `chunk` bytes. */
  def byteString(written: Int, b: Array[Byte], chunk: Int = 255): String =
    val chunks = b.grouped(chunk).map(c => bits(c.length, 8) + c.map(x => bits(x & 0xff, 8)).mkString)
    filler(written) + chunks.mkString + bits(0, 8)

  /** The tags of a type (`encodeUni`). */
  def typeTags(uni: DefaultUni): Vector[Int] = uni match
    case DefaultUni.Integer              => Vector(0)
    case DefaultUni.ByteString           => Vector(1)
    case DefaultUni.String               => Vector(2)
    case DefaultUni.Unit                 => Vector(3)
    case DefaultUni.Bool                 => Vector(4)
    case DefaultUni.List(e)              => Vector(7, 5) ++ typeTags(e)
    case DefaultUni.Pair(a, b)           => Vector(7, 7, 6) ++ typeTags(a) ++ typeTags(b)
    case DefaultUni.Data                 => Vector(8)
    case DefaultUni.Bls12_381_G1_Element => Vector(9)
    case DefaultUni.Bls12_381_G2_Element => Vector(10)
    case DefaultUni.Bls12_381_MlResult   => Vector(11)
    case DefaultUni.Array(e)             => Vector(7, 12) ++ typeTags(e)
    case DefaultUni.Value                => Vector(13)

  def tagList(tags: Seq[Int]): String = tags.map(t => "1" + bits(t, 4)).mkString + "0"

  /** A constant's value, after `written` bits (byte strings need the position for their filler). */
  def value(written: Int, c: Constant): String = c match
    case Constant.Integer(n)    => integer(n)
    case Constant.ByteString(b) => byteString(written, b.toArray)
    case Constant.String(t)     => byteString(written, t.utf8.toArray)
    case Constant.Unit          => ""
    case Constant.Bool(b)       => if b then "1" else "0"
    case Constant.Data(d)       => byteString(written, DataWriter.encode(d))
    case Constant.Value(v) =>
      // each key is a byte string, whose filler depends on where it starts
      def key(out: StringBuilder, k: Bytes): StringBuilder =
        out.append("1")
        out.append(byteString(written + out.length, k.toArray))
      v.toList
        .foldLeft(new StringBuilder) { case (out, (currency, tokens)) =>
          tokens
            .foldLeft(key(out, currency.bytes)) { case (o, (token, quantity)) =>
              key(o, token.bytes).append(integer(quantity.amount))
            }
            .append("0")
        }
        .append("0")
        .toString
    case l: Constant.List  => items(written, l.items)
    case a: Constant.Array => items(written, a.items)
    case Constant.Pair(a, b) =>
      val first = value(written, a)
      first + value(written + first.length, b)
    case other => throw IllegalArgumentException(s"no Flat value for $other")

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

  /** The bits of a term written after `written` bits. */
  def term(written: Int, t: Term): String =
    @tailrec def go(todo: List[Item], out: StringBuilder): String = todo match
      case Nil                     => out.toString
      case Item.Emit(b) :: rest    => go(rest, out.append(b))
      case Item.Encode(t) :: rest =>
        t.unfix match
          case TermF.Var(i)     => go(rest, out.append(bits(0, 4)).append(natural(i.index.toBigInt)))
          case TermF.Delay(x)   => go(Item.Encode(x) :: rest, out.append(bits(1, 4)))
          case TermF.LamAbs(x)  => go(Item.Encode(x) :: rest, out.append(bits(2, 4)))
          case TermF.Apply(f, a) => go(Item.Encode(f) :: Item.Encode(a) :: rest, out.append(bits(3, 4)))
          case TermF.Constant(c) => go(rest, out.append(constant(written + out.length, c)))
          case TermF.Force(x)   => go(Item.Encode(x) :: rest, out.append(bits(5, 4)))
          case TermF.Error      => go(rest, out.append(bits(6, 4)))
          case TermF.Builtin(f) => go(rest, out.append(bits(7, 4)).append(bits(f.flatTag, 7)))
          case TermF.Constr(tag, fields) =>
            val list = fields.toList.flatMap(f => List(Item.Emit("1"), Item.Encode(f))) :+ Item.Emit("0")
            go(list ::: rest, out.append(bits(8, 4)).append(natural(tag.toBigInt)))
          case TermF.Case(s, branches) =>
            val list =
              branches.toList.flatMap(b => List(Item.Emit("1"), Item.Encode(b))) :+ Item.Emit("0")
            go(Item.Encode(s) :: list ::: rest, out.append(bits(9, 4)))
    go(List(Item.Encode(t)), StringBuilder())

  /** The version and term of `program`, without the filler after it. */
  def unpadded(program: Program): String =
    val version = natural(program.version.major) + natural(program.version.minor) +
      natural(program.version.patch)
    version + term(version.length, program.term)

  /** Bits that end on a byte boundary as bytes. */
  def packed(bits: String): Array[Byte] =
    require(bits.length % 8 == 0, s"${bits.length} bits are not whole bytes")
    bits.grouped(8).map(Integer.parseInt(_, 2).toByte).toArray

  /** Bits, then a filler that ends them on a byte boundary (`postAligned`). */
  def padded(bits: String): Array[Byte] = packed(bits + filler(bits.length))

  def flat(program: Program): Bytes = Bytes.wrap(padded(unpadded(program)))

  /** A definite-length CBOR byte string around `content`, its length in the shortest head. */
  def cborBytes(content: Array[Byte]): Array[Byte] =
    val n = content.length
    val head =
      if n < 24 then Array((0x40 + n).toByte)
      else if n < 256 then Array(0x58.toByte, n.toByte)
      else if n < 65536 then Array(0x59.toByte, (n >> 8).toByte, n.toByte)
      else Array(0x5a.toByte, (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)
    head ++ content

  /** The script as the ledger holds it. */
  def script(program: Program): Bytes = Bytes.wrap(cborBytes(flat(program).toArray))
