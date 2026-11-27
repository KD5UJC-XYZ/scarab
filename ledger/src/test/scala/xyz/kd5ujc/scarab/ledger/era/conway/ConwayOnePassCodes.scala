// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational}
import xyz.kd5ujc.scarab.ledger.era.EraTxError

/** The code of a read that [[ConwayOnePassSuite]] records: `N` for bytes that are not one item of
  * CBOR, `A` for an accepted read, and for a refusal the first four bytes of the SHA-256 of its
  * canonical form, which names it by its structure and is no message text.
  */
private[conway] object ConwayOnePassCodes:
  /** The canonical form of a typed value: its constructors, qualified by their enclosing types, and
    * their fields in order, as Scala would write them; a set's elements sorted.
    */
  def canonical(x: Any): String = x match
    case b: BigInt  => s"""BigInt("$b")"""
    case i: Int     => i.toString
    case l: Long    => s"${l}L"
    case b: Boolean => b.toString
    case d: Double  => d.toString
    case s: String =>
      "\"" + s.flatMap {
        case '"'                     => "\\\""
        case '\\'                    => "\\\\"
        case c if c < ' ' || c > '~' => f"\\u${c.toInt}%04x"
        case c                       => c.toString
      } + "\""
    case b: Bytes     => s"""hex("${b.hex}")"""
    case n: Node      => s"""nd("${n.original.hex}")"""
    case v: Vector[?] => v.map(canonical).mkString("Vector(", ", ", ")")
    case v: Set[?]    => v.toVector.map(canonical).sorted.mkString("Set(", ", ", ")")
    case m: Map[?, ?] =>
      m.toVector
        .map((k, v) => s"${canonical(k)} -> ${canonical(v)}")
        .sorted
        .mkString("Map(", ", ", ")")
    case r: Rational  => s"Rational(${canonical(r.numerator)}, ${canonical(r.denominator)})"
    case (a, b)       => s"(${canonical(a)}, ${canonical(b)})"
    case s: Option[?] => s.fold("None")(a => s"Some(${canonical(a)})")
    case p: Product =>
      val full = p.getClass.getName
      val segs = full
        .substring(full.lastIndexOf('.') + 1)
        .split('$')
        .toVector
        .filter(s => s.nonEmpty && s != "anon" && !s.forall(_.isDigit))
      val name =
        if segs.lastOption.contains(p.productPrefix) then segs.mkString(".")
        else (segs :+ p.productPrefix).mkString(".")
      if p.productArity == 0 then name
      else p.productIterator.map(canonical).mkString(s"$name(", ", ", ")")
    case other => s"/*?${other.getClass.getName}*/ $other"

  /** A read's code. */
  def code(read: Option[Either[EraTxError, ?]]): String = read match
    case None           => "N"
    case Some(Right(_)) => "A"
    case Some(Left(e)) =>
      MessageDigest
        .getInstance("SHA-256")
        .digest(canonical(e).getBytes(StandardCharsets.UTF_8))
        .take(4)
        .map(b => f"${b & 0xff}%02x")
        .mkString
