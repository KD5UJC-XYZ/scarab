// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import java.io.ByteArrayOutputStream
import scala.util.Random

/** Random, valid CBOR for a CDDL rule, driven by the resolved schema (the generator used by the
  * property and fuzz tests). Output is canonical: shortest heads, definite lengths, map entries in
  * rule order. `maxDepth` bounds recursion; beyond it the first (shortest) alternative is taken.
  */
final class CddlSampler(schema: Schema, maxDepth: Int = 7):
  private def fail(what: String): Nothing = throw GenException(GenError.Unsupported("sampler", what))

  private final class Enc:
    val out = new ByteArrayOutputStream()
    def head(major: Int, n: BigInt): Unit =
      val m = major << 5
      def put(b: Int): Unit = out.write(b)
      if n < 24 then put(m | n.toInt)
      else if n <= 0xff then { put(m | 24); put(n.toInt) }
      else if n <= 0xffff then { put(m | 25); put((n >> 8).toInt); put(n.toInt & 0xff) }
      else if n <= BigInt(0xffffffffL) then
        put(m | 26)
        (3 to 0 by -1).foreach(i => put(((n >> (8 * i)) & 0xff).toInt))
      else
        put(m | 27)
        (7 to 0 by -1).foreach(i => put(((n >> (8 * i)) & 0xff).toInt))
    def raw(b: Array[Byte]): Unit = out.write(b, 0, b.length)
    def bytes(b: Array[Byte]): Unit = { head(2, b.length); raw(b) }
    def text(s: String): Unit =
      val b = s.getBytes("UTF-8")
      head(3, b.length)
      raw(b)
    def int(n: BigInt): Unit = if n.signum >= 0 then head(0, n) else head(1, -1 - n)
    def result: Array[Byte] = out.toByteArray

  /** A random item that matches the rule `q`. */
  def sample(q: QName, rnd: Random): Array[Byte] =
    val e = new Enc
    rule(q, rnd, e, 0)
    e.result

  private def rule(q: QName, rnd: Random, e: Enc, depth: Int): Unit =
    schema.rule(q).body match
      case RuleBody.Type(t)       => ty(t, rnd, e, depth + 1)
      case RuleBody.GroupBody(_)  => fail(s"group rule $q has no standalone encoding")

  private def big(rnd: Random, lo: BigInt, hi: BigInt): BigInt =
    val span = hi - lo + 1
    rnd.nextInt(6) match
      case 0 => lo
      case 1 => hi
      case _ =>
        if span.isValidLong && span <= Int.MaxValue then lo + rnd.nextInt(span.toInt)
        else lo + (BigInt(span.bitLength + 8, rnd) mod span)

  private def randBytes(rnd: Random, n: Int): Array[Byte] =
    val a = new Array[Byte](n)
    rnd.nextBytes(a)
    a

  private def ty(t: RT, rnd: Random, e: Enc, depth: Int): Unit = t match
    case RT.Prim(PrimKind.Uint)  => e.head(0, big(rnd, 0, BigInt(1) << rnd.nextInt(64)))
    case RT.Prim(PrimKind.Int)   => e.int(big(rnd, -(BigInt(1) << rnd.nextInt(40)), BigInt(1) << rnd.nextInt(40)))
    case RT.Prim(PrimKind.Nint)  => e.int(-1 - big(rnd, 0, BigInt(1) << rnd.nextInt(40)))
    case RT.Prim(PrimKind.Bytes) => e.bytes(randBytes(rnd, rnd.nextInt(10)))
    case RT.Prim(PrimKind.Text)  => e.text(rnd.alphanumeric.take(rnd.nextInt(10)).mkString)
    case RT.Prim(PrimKind.Bool)  => e.raw(Array((if rnd.nextBoolean() then 0xf5 else 0xf4).toByte))
    case RT.Prim(PrimKind.Nil)   => e.raw(Array(0xf6.toByte))
    case RT.Prim(PrimKind.Any) =>
      rnd.nextInt(4) match
        case 0 => e.head(0, rnd.nextInt(1000))
        case 1 => e.bytes(randBytes(rnd, rnd.nextInt(6)))
        case 2 => e.text(rnd.alphanumeric.take(rnd.nextInt(6)).mkString)
        case _ => e.raw(Array(0x80.toByte))
    case RT.Prim(PrimKind.Float) => fail("float")
    case RT.LitInt(n)            => e.int(n)
    case RT.LitText(s)           => e.text(s)
    case RT.LitBool(b)           => e.raw(Array((if b then 0xf5 else 0xf4).toByte))
    case RT.LitBytes(bs)         => e.bytes(bs.toArray)
    case RT.IntRange(lo, hi)     => e.int(big(rnd, lo, hi))
    case RT.SizedBytes(lo, hi)   => e.bytes(randBytes(rnd, lo + (if hi == lo then 0 else rnd.nextInt(math.min(hi - lo, 8) + 1))))
    case RT.SizedText(lo, hi) =>
      e.text(rnd.alphanumeric.take(lo + (if hi == lo then 0 else rnd.nextInt(math.min(hi - lo, 8) + 1))).mkString)
    case RT.Ref(q) => rule(q, rnd, e, depth)
    case RT.Choice(alts) =>
      val pick = if depth > maxDepth then alts.head else alts(rnd.nextInt(alts.size))
      ty(pick, rnd, e, depth)
    case RT.Arr(es) =>
      val parts = es.flatMap(entryItems(_, rnd, depth))
      e.head(4, parts.size)
      parts.foreach(p => p(e))
    case RT.MapT(es) =>
      // Keys are sampled per entry; a typed entry repeats with distinct keys.
      val pairs = es.flatMap { en =>
        val n = count(en.occ, rnd, depth)
        val seen = scala.collection.mutable.Set.empty[Seq[Byte]]
        (0 until n).flatMap { _ =>
          val k = new Enc
          en.key match
            case Some(RKey.IntKey(v))    => k.int(v)
            case Some(RKey.TextKey(s))   => k.text(s)
            case Some(RKey.OfType(kt, _)) => ty(kt, rnd, k, depth + 1)
            case None                    => fail("map entry without key")
          val kb = k.result
          if seen.add(kb.toSeq) then
            val v = new Enc
            ty(en.value, rnd, v, depth + 1)
            Some((kb, v.result))
          else None
        }
      }
      e.head(5, pairs.size)
      pairs.foreach { (k, v) => e.raw(k); e.raw(v) }
    case RT.Tagged(n, inner) =>
      e.head(6, n)
      ty(inner, rnd, e, depth)
    case RT.CborOf(inner) =>
      val i = new Enc
      ty(inner, rnd, i, depth)
      e.bytes(i.result)
    case RT.GroupRef(q)      => fail(s"group $q as a type")
    case RT.Unsupported(why) => fail(why)

  private def count(occ: Ast.Occ, rnd: Random, depth: Int): Int =
    val hi = occ.max.getOrElse(occ.min + 3)
    if depth > maxDepth then occ.min
    else occ.min + rnd.nextInt(math.min(hi - occ.min, 3) + 1)

  /** The encoded items one array entry contributes (groups are spliced). */
  private def entryItems(en: REntry, rnd: Random, depth: Int): Vector[Enc => Unit] =
    val n = count(en.occ, rnd, depth)
    (0 until n).toVector.flatMap { _ =>
      en.value match
        case RT.GroupRef(g) =>
          schema.rule(g).body match
            case RuleBody.GroupBody(es) => es.flatMap(entryItems(_, rnd, depth + 1))
            case _                      => fail(s"$g is not a group")
        case v =>
          val item = new Enc
          ty(v, rnd, item, depth + 1)
          val bytes = item.result
          Vector((out: Enc) => out.raw(bytes))
    }
