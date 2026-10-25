// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.Bytes

/** Plutus scripts written byte by byte in the flat encoding, for what Scalus's own encoder cannot
  * produce: it normalises `value` constants and narrows variable indices, so a script the reference
  * decodes differently has to be assembled by hand. A tiny term language and its flat writer
  * (`PlutusCore/FlatInstances.hs`, `UntypedPlutusCore/Core/Instance/Flat.hs`); test code, so plain
  * recursion over small terms.
  */
object FlatProgram:
  sealed trait Tm
  final case class Var(index: BigInt) extends Tm
  final case class Delay(body: Tm) extends Tm

  /** A variable whose index is written as exactly these bytes (seven-bit groups, each behind its
    * continue bit), padded or not, as `dWord64` reads them.
    */
  final case class VarGroups(groups: Vector[Int]) extends Tm
  final case class Lam(body: Tm) extends Tm
  final case class Ap(function: Tm, argument: Tm) extends Tm
  final case class Force(body: Tm) extends Tm
  final case class Constr(tag: Int, fields: Vector[Tm]) extends Tm
  final case class Case(scrutinee: Tm, branches: Vector[Tm]) extends Tm
  final case class Builtin(tag: Int) extends Tm
  case object Error extends Tm

  /** A constant: its type tags (preorder) and the writer of its value. */
  final case class Const(types: Vector[Int], value: Bits => Unit) extends Tm

  val unit: Tm = Const(Vector(3), _ => ())
  def integer(n: BigInt): Tm = Const(Vector(0), _.integer(n))

  /** A `data` constant whose flat byte string holds exactly `cbor`, trailing bytes and all. */
  def data(cbor: Array[Byte]): Tm = Const(Vector(8), _.byteString(cbor))

  /** A `string` constant whose flat byte string holds exactly `raw`, UTF-8 or not. */
  def string(raw: Array[Byte]): Tm = Const(Vector(2), _.byteString(raw))

  /** `n` nested list types around an integer, an empty list value. */
  def nestedEmptyList(n: Int): Tm =
    Const(Vector.fill(n)(Vector(7, 5)).flatten :+ 0, _.bit(0))

  /** A `value` constant with the entries as written, in this order, whatever their sense. */
  def value(entries: Vector[(Array[Byte], Vector[(Array[Byte], BigInt)])]): Tm =
    Const(
      Vector(13),
      bits =>
        entries.foreach { case (currency, tokens) =>
          bits.bit(1)
          bits.byteString(currency)
          tokens.foreach { case (token, quantity) =>
            bits.bit(1)
            bits.byteString(token)
            bits.integer(quantity)
          }
          bits.bit(0)
        }
        bits.bit(0)
    )

  /** The bytes of a G1 element behind the tag a flat constant would use. */
  def blsG1(compressed: Array[Byte]): Tm = Const(Vector(9), _.byteString(compressed))

  final class Bits:
    private val out = new StringBuilder
    def bit(b: Int): Unit = out.append(if b == 0 then '0' else '1')
    def bits(value: Int, width: Int): Unit =
      (width - 1 to 0 by -1).foreach(i => bit((value >> i) & 1))
    def natural(n: BigInt): Unit =
      var rest = n
      var more = true
      while more do
        val group = (rest & 0x7f).toInt
        rest = rest >> 7
        more = rest != 0
        bits(group | (if more then 0x80 else 0), 8)
    def integer(n: BigInt): Unit = natural(if n >= 0 then n << 1 else ((-n) << 1) - 1)
    def filler(): Unit =
      while (out.length + 1) % 8 != 0 do bit(0)
      bit(1)
    def byteString(b: Array[Byte]): Unit =
      filler()
      b.grouped(255).foreach { chunk =>
        bits(chunk.length, 8)
        chunk.foreach(x => bits(x & 0xff, 8))
      }
      bits(0, 8)
    def bytes: Array[Byte] =
      filler()
      packed

    /** Zero bits up to the next byte boundary, and no filler. */
    def zeros(): Unit = while out.length % 8 != 0 do bit(0)

    /** The bits written so far, which must end on a byte boundary, without a filler. */
    def packed: Array[Byte] =
      out.toString.grouped(8).map(Integer.parseInt(_, 2).toByte).toArray

  private def term(t: Tm, b: Bits): Unit = t match
    case Var(i) => b.bits(0, 4); b.natural(i)
    case VarGroups(gs) =>
      b.bits(0, 4)
      gs.foreach(g => b.bits(g, 8))
    case Delay(x) => b.bits(1, 4); term(x, b)
    case Lam(x)   => b.bits(2, 4); term(x, b)
    case Ap(f, a) => b.bits(3, 4); term(f, b); term(a, b)
    case Const(ts, v) =>
      b.bits(4, 4)
      ts.foreach { tag =>
        b.bit(1); b.bits(tag, 4)
      }
      b.bit(0)
      v(b)
    case Force(x) => b.bits(5, 4); term(x, b)
    case Constr(tag, fs) =>
      b.bits(8, 4)
      b.natural(tag)
      fs.foreach { f =>
        b.bit(1); term(f, b)
      }
      b.bit(0)
    case Case(s, bs) =>
      b.bits(9, 4)
      term(s, b)
      bs.foreach { x =>
        b.bit(1); term(x, b)
      }
      b.bit(0)
    case Builtin(tag) => b.bits(7, 4); b.bits(tag, 7)
    case Error        => b.bits(6, 4)

  /** The flat bytes of a program of the given UPLC version. */
  def flat(version: (Int, Int, Int), t: Tm): Array[Byte] =
    flatOf((BigInt(version._1), BigInt(version._2), BigInt(version._3)), t)

  /** The flat bytes of a program whose version components may be any natural. */
  def flatOf(version: (BigInt, BigInt, BigInt), t: Tm): Array[Byte] =
    unpadded(version, t).bytes

  /** The bits of a program up to the end of its term: what follows is the caller's to write. */
  def unpadded(version: (BigInt, BigInt, BigInt), t: Tm): Bits =
    val b = Bits()
    b.natural(version._1)
    b.natural(version._2)
    b.natural(version._3)
    term(t, b)
    b

  def cborByteString(content: Array[Byte]): Array[Byte] =
    val n = content.length
    val head =
      if n < 24 then Array((0x40 + n).toByte)
      else if n < 256 then Array(0x58.toByte, n.toByte)
      else if n < 65536 then Array(0x59.toByte, (n >> 8).toByte, n.toByte)
      else Array(0x5a.toByte, (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)
    head ++ content

  /** The script as the ledger holds it: a CBOR byte string around the flat bytes. */
  def script(version: (Int, Int, Int), t: Tm): Bytes =
    Bytes.fromArray(cborByteString(flat(version, t)))
