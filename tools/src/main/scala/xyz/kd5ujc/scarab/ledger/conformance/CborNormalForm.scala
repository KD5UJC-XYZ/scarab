// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** The normal form of a CBOR item that the cardano-cbor-dataset compares re-encodings in when a
  * rule is not byte-exact (`VERIFICATION.md`, "Normalization"): every array and map definite with
  * its items in their order, every chunked string one string, every head minimal, a bignum (tags 2
  * and 3 over a byte string) whose magnitude fits a native head written as that integer, or else
  * with the leading zero bytes of its magnitude dropped, and a rational (tag 30 over two integers,
  * the second not zero) reduced to its lowest terms with the sign on the numerator, as `%` makes a
  * Haskell `Ratio`. Map key order, repeated keys, other tags, float widths and every other integer
  * value are kept, so a value read wrong still differs.
  */
object CborNormalForm:
  /** The items the normaliser reads: any CBOR, floats and simple values included, with no bound but
    * the input's size.
    */
  private val limits = Cbor.Limits.Unbounded.copy(allowFloats = true, allowSimpleValues = true)

  private val MaxUInt = (BigInt(1) << 64) - 1

  def of(bytes: Bytes): Either[Cbor.Error, Bytes] = Cbor.decode(bytes, limits).map(of)

  /** The normal form of `root`, written with the nesting on the heap (ADR 0011). */
  def of(root: Node): Bytes =
    @tailrec def loop(work: List[Node], out: List[Array[Byte]]): List[Array[Byte]] =
      work match
        case Nil => out
        case n :: rest =>
          val (piece, children) = emit(n)
          loop(children ++ rest, piece :: out)
    Bytes.wrap(Array.concat(loop(List(root), Nil).reverse*))

  /** The bytes of `n` before its children, and the children to write after them in order. */
  private def emit(n: Node): (Array[Byte], List[Node]) = n.value match
    case Value.UInt(v)       => (head(0, v), Nil)
    case Value.NInt(v)       => (head(1, -1 - v), Nil)
    case Value.ByteString(b) => (head(2, BigInt(b.size)) ++ b.toArray, Nil)
    case Value.Text(s) =>
      val utf8 = s.getBytes(StandardCharsets.UTF_8)
      (head(3, BigInt(utf8.length)) ++ utf8, Nil)
    case Value.Arr(xs)  => (head(4, BigInt(xs.size)), xs.toList)
    case Value.Map(kvs) => (head(5, BigInt(kvs.size)), kvs.toList.flatMap((k, v) => List(k, v)))
    case Value.Tag(t, inner) =>
      inner.value match
        case Value.ByteString(b) if t == 2 || t == 3 => (bignum(t == 2, BigInt(1, b.toArray)), Nil)
        case _ if t == 30 =>
          rational(inner).fold((head(6, t), List(inner)))(r => (head(6, t) ++ r, Nil))
        case _ => (head(6, t), List(inner))
    // a float keeps its width, and a simple value, a boolean or null is one head already minimal
    case Value.Bool(_) | Value.Null | Value.Simple(_) | Value.Float(_, _) =>
      (n.original.toArray, Nil)

  /** The reduced `[numerator, denominator]` of a rational's two integers, none when `n` is not two
    * integers or its denominator is zero.
    */
  private def rational(n: Node): Option[Array[Byte]] = n.value match
    case Value.Arr(Vector(p, q)) =>
      (integer(p), integer(q)) match
        case (Some(num), Some(den)) if den != 0 =>
          val g = num.gcd(den) * den.signum
          Some(head(4, BigInt(2)) ++ written(num / g) ++ written(den / g))
        case _ => None
    case _ => None

  /** The integer an item holds: a native integer or a bignum. */
  private def integer(n: Node): Option[BigInt] = n.value match
    case Value.UInt(v) => Some(v)
    case Value.NInt(v) => Some(v)
    case Value.Tag(t, inner) if t == 2 || t == 3 =>
      inner.value match
        case Value.ByteString(b) =>
          val magnitude = BigInt(1, b.toArray)
          Some(if t == 2 then magnitude else -1 - magnitude)
        case _ => None
    case _ => None

  /** An integer in its normal form: a native head when it fits one, else a bignum. */
  private def written(v: BigInt): Array[Byte] =
    if v >= 0 then bignum(true, v) else bignum(false, -1 - v)

  /** A bignum of `magnitude`, positive (tag 2) or the negative `-1 - magnitude` (tag 3). */
  private def bignum(positive: Boolean, magnitude: BigInt): Array[Byte] =
    if magnitude <= MaxUInt then head(if positive then 0 else 1, magnitude)
    else
      val bytes = magnitude.toByteArray.dropWhile(_ == 0)
      head(6, BigInt(if positive then 2 else 3)) ++ head(2, BigInt(bytes.length)) ++ bytes

  /** The shortest head of major type `major` and argument `n`, at most 2^64 - 1. */
  private def head(major: Int, n: BigInt): Array[Byte] =
    val initial = major << 5
    if n < 24 then Array((initial | n.toInt).toByte)
    else
      val width =
        if n <= 0xff then 1 else if n <= 0xffff then 2 else if n <= 0xffffffffL then 4 else 8
      val ai = width match
        case 1 => 24
        case 2 => 25
        case 4 => 26
        case _ => 27
      val argument = n.toByteArray.reverse.padTo(width, 0.toByte).take(width).reverse
      (initial | ai).toByte +: argument
