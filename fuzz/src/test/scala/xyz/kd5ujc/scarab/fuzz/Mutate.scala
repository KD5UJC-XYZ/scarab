// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import org.scalacheck.Gen
import scala.util.Random

/** Byte-level mutations aimed at CBOR: bit flips, truncation, extension, corrupted and absurd
  * length fields, deep nesting, indefinite-length abuse, tag abuse, splicing.
  */
object Mutate:
  private val interesting: Array[Int] =
    Array(0x00, 0x01, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1f, 0x20, 0x38, 0x3b, 0x40, 0x58, 0x5b,
      0x5f, 0x60, 0x7b, 0x7f, 0x80, 0x98, 0x9b, 0x9f, 0xa0, 0xb8, 0xbb, 0xbf, 0xc0, 0xc2, 0xc3,
      0xd8, 0xda, 0xdb, 0xe0, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0xfc, 0xff)

  /** Declared lengths and numbers at the edges. */
  private val huge: Array[Long] =
    Array(
      0L,
      1L,
      23L,
      24L,
      255L,
      256L,
      65535L,
      65536L,
      0x7fffffffL,
      0x80000000L,
      0xffffffffL,
      0x100000000L,
      1L << 40,
      1L << 60,
      Long.MaxValue,
      -1L,
      Long.MinValue
    )

  /** A CBOR head with an 8-byte argument. */
  def head8(major: Int, value: Long): Array[Byte] =
    val out = new Array[Byte](9)
    out(0) = ((major << 5) | 27).toByte
    var i = 0
    while i < 8 do
      out(1 + i) = (value >>> (56 - 8 * i)).toByte
      i += 1
    out

  private def pick(rnd: Random, a: Array[Long]): Long = a(rnd.nextInt(a.length))
  private def at(rnd: Random, a: Array[Byte]): Int = if a.isEmpty then 0 else rnd.nextInt(a.length)
  private def insert(a: Array[Byte], pos: Int, piece: Array[Byte]): Array[Byte] =
    val p = math.min(pos, a.length)
    a.take(p) ++ piece ++ a.drop(p)

  private def repeat(piece: Array[Byte], times: Int): Array[Byte] =
    val out = new Array[Byte](piece.length * times)
    var i = 0
    while i < times do
      System.arraycopy(piece, 0, out, i * piece.length, piece.length)
      i += 1
    out

  /** Positions that look like the head of a string, array, map or tag with an argument. */
  private def headPositions(a: Array[Byte]): IndexedSeq[Int] =
    a.indices.filter { i =>
      val major = (a(i) & 0xff) >> 5
      val ai = a(i) & 31
      major >= 2 && major <= 6 && (ai >= 24 || ai == 31)
    }

  private def mutations(
      others: IndexedSeq[Array[Byte]]
  ): Vector[(Array[Byte], Random) => Array[Byte]] =
    Vector(
      (a, r) =>
        // flip one bit
        if a.isEmpty then a
        else
          val c = a.clone(); val i = r.nextInt(a.length)
          c(i) = (c(i) ^ (1 << r.nextInt(8))).toByte; c
      ,
      (a, r) =>
        // set one byte to a CBOR-significant value
        if a.isEmpty then a
        else
          val c = a.clone();
          c(r.nextInt(a.length)) = interesting(r.nextInt(interesting.length)).toByte; c
      ,
      (a, r) =>
        // Truncate at any prefix, including the empty input.
        a.take(r.nextInt(a.length + 1)),
      (a, r) =>
        // extend with random bytes, a copy of itself, or a break
        r.nextInt(3) match
          case 0 => a ++ Array.fill(1 + r.nextInt(16))(r.nextInt(256).toByte)
          case 1 => a ++ a
          case _ => a :+ 0xff.toByte
      ,
      (a, r) =>
        // overwrite the bytes after a head with a huge declared length (the head keeps its major type)
        val heads = headPositions(a)
        if heads.isEmpty then a
        else
          val p = heads(r.nextInt(heads.size))
          val major = (a(p) & 0xff) >> 5
          a.take(p) ++ head8(major, pick(r, huge)) ++ a.drop(p + 1)
      ,
      (a, r) =>
        // a fresh head with a huge declared length, anywhere, followed by little or nothing
        insert(
          a,
          at(r, a),
          head8(2 + r.nextInt(4), pick(r, huge)) ++ Array.fill(r.nextInt(4))(0.toByte)
        ),
      (a, r) =>
        // deep nesting in front of or inside the input
        val depth = Array(5, 24, 25, 33, 64, 65, 100, 500, 1024, 2047, 2048, 2049, 5000, 100000)(
          r.nextInt(14)
        )
        val unit = r.nextInt(5) match
          case 0 => Array(0x81.toByte)
          case 1 => Array(0xa1.toByte, 0x00.toByte)
          case 2 => Array(0x9f.toByte)
          case 3 => Array(0xc1.toByte)
          case _ => Array(0x82.toByte, 0x00.toByte)
        insert(a, if r.nextBoolean() then 0 else at(r, a), repeat(unit, depth))
      ,
      (a, r) =>
        // indefinite-length abuse: start one without a break, break without a start, wrong chunk types
        r.nextInt(5) match
          case 0 => insert(a, at(r, a), Array(Array(0x9f, 0xbf, 0x5f, 0x7f)(r.nextInt(4)).toByte))
          case 1 => insert(a, at(r, a), Array(0xff.toByte))
          case 2 => insert(a, at(r, a), Array(0x5f, 0x61, 0x61, 0xff).map(_.toByte))
          case 3 => insert(a, at(r, a), Array(0x7f, 0x41, 0x00, 0xff).map(_.toByte))
          case _ =>
            if a.isEmpty then a
            else
              val c = a.clone(); val p = at(r, a)
              c(p) = ((c(p) & 0xe0) | 31).toByte; c
      ,
      (a, r) =>
        // tag abuse: an extra tag in front of a random item, with a small or huge number
        val tag =
          r.nextInt(4) match
            case 0 => Array((0xc0 + r.nextInt(24)).toByte)
            case 1 =>
              Array(0xd8.toByte, Array(2, 3, 24, 30, 55, 102, 121, 127).apply(r.nextInt(8)).toByte)
            case 2 => Array(0xd9, 0x01, 0x02).map(_.toByte)
            case _ => head8(6, pick(r, huge))
        insert(a, at(r, a), tag)
      ,
      (a, r) =>
        // splice with another input
        if others.isEmpty then a
        else
          val b = others(r.nextInt(others.size))
          a.take(at(r, a)) ++ b.drop(at(r, b))
      ,
      (a, r) =>
        // delete or duplicate a range
        if a.isEmpty then a
        else
          val from = r.nextInt(a.length); val len = 1 + r.nextInt(math.min(16, a.length - from))
          if r.nextBoolean() then a.patch(from, Nil, len)
          else a.patch(from, a.slice(from, from + len), 0)
    )

  /** `corpus` items with one to four mutations, a splice partner drawn from the corpus. */
  def mutated(corpus: IndexedSeq[Array[Byte]]): Gen[Input] =
    val ms = mutations(corpus)
    for
      base <- Gen.oneOf(corpus)
      seed <- Gen.long
    yield
      val rnd = new Random(seed)
      var a = base
      var n = 1 + rnd.nextInt(4)
      while n > 0 do
        a = ms(rnd.nextInt(ms.size))(a, rnd)
        n -= 1
      new Input(a)

  /** Unstructured bytes of up to 80 (or sometimes 600) bytes. */
  val noise: Gen[Input] =
    for
      n <- Gen.frequency(9 -> Gen.choose(0, 80), 1 -> Gen.choose(0, 600))
      seed <- Gen.long
    yield
      val a = new Array[Byte](n)
      new Random(seed).nextBytes(a)
      new Input(a)

  /** Random heads followed by random tails, so that the first bytes are plausible CBOR. */
  val headsAndNoise: Gen[Input] =
    for
      seed <- Gen.long
      n <- Gen.choose(1, 12)
    yield
      val r = new Random(seed)
      val out = new java.io.ByteArrayOutputStream()
      (0 until n).foreach { _ =>
        val h = interesting(r.nextInt(interesting.length))
        out.write(h)
        val width = (h & 31) match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => 0
        (0 until width).foreach(_ => out.write(if r.nextInt(3) == 0 then 0xff else r.nextInt(256)))
      }
      new Input(out.toByteArray)

  /** The generator of a target: mostly mutated corpus items, some valid items, some noise. */
  def generator(corpus: IndexedSeq[Array[Byte]]): Gen[Input] =
    if corpus.isEmpty then Gen.frequency(1 -> noise, 3 -> headsAndNoise)
    else
      Gen.frequency(
        12 -> mutated(corpus),
        2 -> Gen.oneOf(corpus).map(new Input(_)),
        1 -> noise,
        2 -> headsAndNoise
      )

  /** Every head of `a` rewritten to declare `value` elements or bytes (for the allocation guard).
    */
  def inflated(a: Array[Byte], value: Long, limit: Int): Seq[Array[Byte]] =
    a.indices
      .filter(p => ((a(p) & 0xff) >> 5) >= 2 && ((a(p) & 0xff) >> 5) <= 5 && (a(p) & 31) != 31)
      .take(limit)
      .map { p =>
        val major = (a(p) & 0xff) >> 5
        val width = (a(p) & 31) match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => 0
        a.take(p) ++ head8(major, value) ++ a.drop(p + 1 + width)
      }
