// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, Program, Term, TermF}
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** Why a program has no Flat encoding. */
enum FlatEncodeError:
  /** A constant of a type whose values the reference's Flat instance does not write: the BLS12-381
    * points and Miller-loop results, which no script can carry. A list or an array of them is
    * written when it is empty.
    */
  case Unencodable(uni: DefaultUni)

  /** The CBOR byte string around the program could not be written. */
  case Envelope(error: Cbor.Error)

/** The Flat encoding of a program, the inverse of [[FlatDecoder]]: plutus 1.70.0.0's (`507cab8c`)
  * `Flat` instances of `Program` and `Term` (`UntypedPlutusCore/Core/Instance/Flat.hs`), of the
  * built-in types and values (`PlutusCore/Flat.hs`), and the filler that pads the program to a byte
  * boundary. A `data` constant is the reference's `serialise` of it ([[DataSerialisation]]),
  * written as a byte string.
  *
  * The program is written from a work list on the heap (ADR 0011): terms, types and constants nest
  * as deep as their source makes them. Bits accumulate as text, one chunk per item.
  */
object FlatEncoder:

  /** What remains to write: bits, a term, the tags of a type, the value of a constant, or a byte
    * string (whose filler depends on where it starts).
    */
  private enum Item:
    case Bits(bits: String)
    case OfTerm(term: Term)
    case OfType(uni: DefaultUni)
    case OfValue(constant: Constant)
    case OfBytes(bytes: Bytes)

  import Item.*

  /** What has been written: the chunks, the latest first, and their length in bits. */
  private final case class Written(chunks: List[String], length: Int):
    def +(bits: String): Written = Written(bits :: chunks, length + bits.length)
    def text: String = chunks.reverseIterator.mkString

  private val octets: Vector[String] = Vector.tabulate(256)(bits(_, 8))

  private def bits(value: Int, width: Int): String =
    (width - 1 to 0 by -1).map(i => if ((value >> i) & 1) == 1 then '1' else '0').mkString

  /** A natural: its seven-bit groups, least significant first, each with a continuation bit. */
  private def natural(n: BigInt): String =
    val groups = ((n.bitLength + 6) / 7).max(1)
    (0 until groups).map { g =>
      val group =
        (0 until 7).foldLeft(0)((acc, k) => if n.testBit(7 * g + k) then acc | (1 << k) else acc)
      octets(if g < groups - 1 then group | 0x80 else group)
    }.mkString

  /** An integer, zig-zag encoded as a natural. */
  private def integer(n: BigInt): String = natural(if n >= 0 then n << 1 else ((-n) << 1) - 1)

  /** Zeros up to a 1 that ends a byte, after `written` bits (`eFiller`). */
  private def filler(written: Int): String = "0" * (7 - written % 8) + "1"

  /** A byte string after `written` bits: a filler, then chunks of at most 255 bytes, each after its
    * length, then an empty chunk.
    */
  private def byteString(written: Int, b: Bytes): String =
    filler(written) +
      b.toArray
        .grouped(255)
        .map(c => octets(c.length) + c.map(x => octets(x & 0xff)).mkString)
        .mkString +
      octets(0)

  /** One tag of a type, in the list of a constant's type tags. */
  private def tag(t: Int): Item = Bits("1" + bits(t, 4))

  /** The tags of a type (`encodeUni`), the applied operators before their arguments. */
  private def typeTags(uni: DefaultUni): List[Item] = uni match
    case DefaultUni.Integer              => List(tag(0))
    case DefaultUni.ByteString           => List(tag(1))
    case DefaultUni.String               => List(tag(2))
    case DefaultUni.Unit                 => List(tag(3))
    case DefaultUni.Bool                 => List(tag(4))
    case DefaultUni.List(e)              => List(tag(7), tag(5), OfType(e))
    case DefaultUni.Pair(a, b)           => List(tag(7), tag(7), tag(6), OfType(a), OfType(b))
    case DefaultUni.Data                 => List(tag(8))
    case DefaultUni.Bls12_381_G1_Element => List(tag(9))
    case DefaultUni.Bls12_381_G2_Element => List(tag(10))
    case DefaultUni.Bls12_381_MlResult   => List(tag(11))
    case DefaultUni.Array(e)             => List(tag(7), tag(12), OfType(e))
    case DefaultUni.Value                => List(tag(13))

  /** The items of a list of `xs`: each after a 1, then a 0. */
  private def listOf[A](xs: Iterable[A])(item: A => List[Item]): List[Item] =
    xs.toList.flatMap(x => Bits("1") :: item(x)) :+ Bits("0")

  private def valueItems(c: Constant): Either[FlatEncodeError, List[Item]] = c match
    case Constant.Integer(n)    => Right(List(Bits(integer(n))))
    case Constant.ByteString(b) => Right(List(OfBytes(b)))
    case Constant.String(t)     => Right(List(OfBytes(t.utf8)))
    case Constant.Unit          => Right(Nil)
    case Constant.Bool(b)       => Right(List(Bits(if b then "1" else "0")))
    case Constant.Data(d)       => Right(List(OfBytes(DataSerialisation.serialise(d))))
    case Constant.Value(v) =>
      Right(listOf(v.toList) { (currency, tokens) =>
        OfBytes(currency.bytes) :: listOf(tokens)((token, quantity) =>
          List(OfBytes(token.bytes), Bits(integer(quantity.amount)))
        )
      })
    case l: Constant.List    => Right(listOf(l.items)(x => List(OfValue(x))))
    case a: Constant.Array   => Right(listOf(a.items)(x => List(OfValue(x))))
    case Constant.Pair(a, b) => Right(List(OfValue(a), OfValue(b)))
    case other: (Constant.Bls12_381_G1_Element | Constant.Bls12_381_G2_Element |
          Constant.Bls12_381_MlResult) =>
      Left(FlatEncodeError.Unencodable(other.typeOf))

  private def termItems(t: Term): List[Item] = t.unfix match
    case TermF.Var(i)      => List(Bits(bits(0, 4) + natural(i.index.toBigInt)))
    case TermF.Delay(x)    => List(Bits(bits(1, 4)), OfTerm(x))
    case TermF.LamAbs(x)   => List(Bits(bits(2, 4)), OfTerm(x))
    case TermF.Apply(f, a) => List(Bits(bits(3, 4)), OfTerm(f), OfTerm(a))
    case TermF.Constant(c) =>
      (Bits(bits(4, 4)) :: OfType(c.typeOf) :: Nil) ++ List(Bits("0"), OfValue(c))
    case TermF.Force(x)   => List(Bits(bits(5, 4)), OfTerm(x))
    case TermF.Error      => List(Bits(bits(6, 4)))
    case TermF.Builtin(f) => List(Bits(bits(7, 4) + bits(f.flatTag, 7)))
    case TermF.Constr(tag, fields) =>
      Bits(bits(8, 4) + natural(tag.toBigInt)) :: listOf(fields)(f => List(OfTerm(f)))
    case TermF.Case(s, branches) =>
      Bits(bits(9, 4)) :: OfTerm(s) :: listOf(branches)(b => List(OfTerm(b)))

  @tailrec private def write(
      todo: List[Item],
      out: Written
  ): Either[FlatEncodeError, Written] = todo match
    case Nil                => Right(out)
    case Bits(b) :: rest    => write(rest, out + b)
    case OfTerm(t) :: rest  => write(termItems(t) ::: rest, out)
    case OfType(u) :: rest  => write(typeTags(u) ::: rest, out)
    case OfBytes(b) :: rest => write(rest, out + byteString(out.length, b))
    case OfValue(c) :: rest =>
      valueItems(c) match
        case Left(error)  => Left(error)
        case Right(items) => write(items ::: rest, out)

  /** The bits as bytes, eight to a byte, the first bit the most significant. */
  private def packed(text: String): Bytes =
    Bytes.fromArray(
      text
        .grouped(8)
        .map(byte => byte.foldLeft(0)((acc, c) => (acc << 1) | (c - '0')).toByte)
        .toArray
    )

  /** The Flat encoding of `program`: its version, its term and the filler. */
  def program(program: Program): Either[FlatEncodeError, Bytes] =
    val version = natural(program.version.major) + natural(program.version.minor) +
      natural(program.version.patch)
    write(List(OfTerm(program.term)), Written(List(version), version.length)).map { out =>
      packed(out.text + filler(out.length))
    }

  /** The script as the ledger holds it: a CBOR byte string around the Flat program, what
    * [[SerialisedScript.deserialiseScript]] reads.
    */
  def script(p: Program): Either[FlatEncodeError, Bytes] =
    program(p).flatMap(flat =>
      Cbor.encode(CborValue.ByteString(flat)).left.map(FlatEncodeError.Envelope(_))
    )
