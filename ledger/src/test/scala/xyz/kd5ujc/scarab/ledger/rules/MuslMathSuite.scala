// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import io.circe.Json
import io.circe.parser.parse
import java.lang.Double.{doubleToRawLongBits as bitsOf, longBitsToDouble as fromBits}
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

/** [[MuslMath]] against the cardano-node 11.1.3 release binary's own `log`, `pow` and `exp`
  * (#1003).
  *
  * `fixtures/reference/release-libm` holds what `scripts/oracle/release-libm/run.py` measured by
  * calling the binary's machine code: function cases over the likelihood and query inputs, every
  * table interval and every special branch; 63 likelihood records; and the bytes of the binary's
  * tables.
  */
class MuslMathSuite extends ScalaCheckSuite:

  private val corpus = Path.of("fixtures/reference/release-libm")

  private val rows: Vector[Json] = Files
    .readString(corpus.resolve("cases.jsonl"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => parse(line).fold(e => fail(e.message), identity))
    .toVector

  private def text(row: Json, key: String): String =
    row.hcursor.get[String](key).fold(e => fail(e.message), identity)
  private def hex(row: Json, key: String): Long =
    java.lang.Long.parseUnsignedLong(text(row, key), 16)

  private enum Case:
    case Log(x: Long, r: Long)
    case Exp(x: Long, r: Long)
    case Pow(x: Long, y: Long, r: Long)

  private val cases: Vector[Case] = rows.flatMap { row =>
    text(row, "f") match
      case "log" => Vector(Case.Log(hex(row, "x"), hex(row, "r")))
      case "exp" => Vector(Case.Exp(hex(row, "x"), hex(row, "r")))
      case "pow" => Vector(Case.Pow(hex(row, "x"), hex(row, "y"), hex(row, "r")))
      case _     => Vector.empty
  }

  /** Equal bits; a NaN only needs to be a NaN (the payload is not promised, see [[MuslMath]]). */
  private def same(actual: Double, expected: Long): Boolean =
    bitsOf(actual) == expected || (actual.isNaN && fromBits(expected).isNaN)

  private def result(c: Case): (Double, Long) = c match
    case Case.Log(x, r)    => (MuslMath.log(fromBits(x)), r)
    case Case.Exp(x, r)    => (MuslMath.exp(fromBits(x)), r)
    case Case.Pow(x, y, r) => (MuslMath.pow(fromBits(x), fromBits(y)), r)

  test("the corpus holds the measured counts") {
    assertEquals(cases.count(_.isInstanceOf[Case.Log]), 2124)
    assertEquals(cases.count(_.isInstanceOf[Case.Pow]), 1396)
    assertEquals(cases.count(_.isInstanceOf[Case.Exp]), 1004)
    assertEquals(rows.size - cases.size, 63)
  }

  property("every committed case gives the release binary's bits") {
    forAll(Gen.oneOf(cases)) { c =>
      val (actual, expected) = result(c)
      Prop(same(actual, expected)) :| s"$c gave ${bitsOf(actual).toHexString}"
    }
  }

  test("all committed cases give the release binary's bits") {
    val wrong = cases.filterNot { c =>
      val (actual, expected) = result(c)
      same(actual, expected)
    }
    assertEquals(wrong.take(10), Vector.empty, s"${wrong.size} of ${cases.size} cases differ")
  }

  // ---- the corpus reaches every table interval and every branch of musl's code

  private val logOff = 0x3fe6000000000000L
  private val logLo = 0x3fee000000000000L
  private val logHi = 0x3ff1090000000000L

  /** The branch of `log.c` an argument takes; the table interval for the main path. */
  private def logBranch(ix: Long): String =
    val top = (ix >>> 48).toInt
    if java.lang.Long.compareUnsigned(ix - logLo, logHi - logLo) < 0 then
      if ix == 0x3ff0000000000000L then "one" else "near one"
    else if Integer.compareUnsigned(top - 0x10, 0x7ff0 - 0x10) >= 0 then
      if ix * 2 == 0 then "zero"
      else if ix == 0x7ff0000000000000L then "inf"
      else if (top & 0x8000) != 0 || (top & 0x7ff0) == 0x7ff0 then "invalid"
      else "subnormal"
    else s"interval ${((ix - logOff) >>> 45) & 127}"

  test("log cases reach every branch and every table interval") {
    val branches = cases.collect { case Case.Log(x, _) => logBranch(x) }.toSet
    assertEquals(
      branches -- (0 until 128).map(i => s"interval $i"),
      Set("one", "near one", "zero", "inf", "invalid", "subnormal")
    )
    assert((0 until 128).forall(i => branches(s"interval $i")))
  }

  /** `exp`'s table index and branch, from the reduction musl makes (`exp.c:78-129`). */
  private def expBranch(x: Double): String =
    val abstop = (bitsOf(x) >>> 52).toInt & 0x7ff
    if abstop < 0x3c9 then "tiny"
    else if abstop >= 0x409 then
      if bitsOf(x) == 0xfff0000000000000L then "-inf"
      else if abstop >= 0x7ff then "inf or nan"
      else if x < 0 then "underflow"
      else "overflow"
    else
      val shift = fromBits(MuslData.Exp.shift)
      val ki = bitsOf(fromBits(MuslData.Exp.invln2N) * x + shift)
      if abstop >= 0x408 then (if (ki & 0x80000000L) == 0 then "special above" else "special below")
      else s"index ${ki & 127}"

  test("exp cases reach every branch and every table index") {
    val branches = cases.collect { case Case.Exp(x, _) => expBranch(fromBits(x)) }.toSet
    assertEquals(
      branches -- (0 until 128).map(i => s"index $i"),
      Set("tiny", "-inf", "inf or nan", "underflow", "overflow", "special above", "special below")
    )
    assert((0 until 128).forall(i => branches(s"index $i")))
  }

  test("exp cases reach results below the normal range, rounded before scaling") {
    val subnormal = cases.collect {
      case Case.Exp(x, r)
          if expBranch(fromBits(x)) == "special below" && r != 0 && r < 0x0010000000000000L =>
        r
    }
    assert(subnormal.size >= 3, subnormal)
  }

  /** `pow`'s branch (`pow.c:265-326`), and the table interval of `log_inline` (`pow.c:46-47`). */
  private def powBranch(ix: Long, iy: Long): String =
    val topx = (ix >>> 52).toInt
    val topy = (iy >>> 52).toInt
    def zeroinfnan(i: Long) =
      java.lang.Long.compareUnsigned(2 * i - 1, 2 * 0x7ff0000000000000L - 1) >= 0
    val tinyOrHugeY = Integer.compareUnsigned((topy & 0x7ff) - 0x3be, 0x43e - 0x3be) >= 0
    if Integer.compareUnsigned(topx - 1, 0x7ff - 1) >= 0 || tinyOrHugeY then
      if zeroinfnan(iy) then "y zero, inf or nan"
      else if zeroinfnan(ix) then "x zero, inf or nan"
      else if ix < 0 && fromBits(iy) != Math.rint(fromBits(iy)) then "negative x, y not integer"
      else if tinyOrHugeY then ((topy & 0x7ff) < 0x3be).fold("tiny y", "huge y")
      else if (topx & 0x7ff) == 0 then "subnormal x"
      else if ix < 0 then "negative x"
      else "unreachable"
    else s"interval ${((ix - 0x3fe6955500000000L) >>> 45) & 127}"

  extension (b: Boolean)
    private def fold[A](ifTrue: A, ifFalse: A): A = if b then ifTrue else ifFalse

  test("pow cases reach every branch, every table interval and every kind of result") {
    val pows = cases.collect { case Case.Pow(x, y, r) => (x, y, r) }
    val branches = pows.map((x, y, _) => powBranch(x, y)).toSet
    assertEquals(
      branches -- (0 until 128).map(i => s"interval $i"),
      Set(
        "y zero, inf or nan",
        "x zero, inf or nan",
        "negative x, y not integer",
        "tiny y",
        "huge y",
        "subnormal x",
        "negative x"
      )
    )
    assert((0 until 128).forall(i => branches(s"interval $i")))
    val results = pows.map((_, _, r) => fromBits(r))
    assert(results.exists(_ == Double.PositiveInfinity), "an overflow")
    assert(results.exists(_ == Double.NegativeInfinity), "a negative overflow")
    assert(results.exists(r => r == 0.0 && bitsOf(r) < 0), "a negative zero")
    assert(
      results.exists(r => r != 0 && Math.abs(r) < java.lang.Double.MIN_NORMAL && r > 0),
      "a subnormal"
    )
    assert(
      results.exists(r => r != 0 && Math.abs(r) < java.lang.Double.MIN_NORMAL && r < 0),
      "a negative subnormal"
    )
  }

  // ---- the tables

  /** The release binary's `__log_data`, `__pow_log_data` and `__exp_data`, as little-endian 64-bit
    * words.
    */
  private val tables: Map[String, Vector[Long]] = Files
    .readString(corpus.resolve("tables.txt"))
    .linesIterator
    .filter(_.nonEmpty)
    .map { line =>
      line.split(' ').toList match
        case name :: size :: bytes :: Nil =>
          assertEquals(bytes.length, 2 * size.toInt)
          name -> bytes
            .grouped(16)
            .map(w => java.lang.Long.reverseBytes(java.lang.Long.parseUnsignedLong(w, 16)))
            .toVector
        case _ => fail(s"malformed table line ${line.take(40)}")
    }
    .toMap

  test("the tables equal the release binary's, word for word") {
    import MuslData.*
    assertEquals(
      tables("__log_data"),
      Vector(Log.ln2hi, Log.ln2lo) ++ Log.poly ++ Log.poly1 ++ Log.tab ++ Log.tab2
    )
    assertEquals(
      tables("__pow_log_data"),
      Vector(PowLog.ln2hi, PowLog.ln2lo) ++ PowLog.poly ++
        PowLog.tab.grouped(3).flatMap(e => Vector(e(0), 0L, e(1), e(2)))
    )
    // The exp2 fields (words 8 to 13) are not read by exp or pow.
    val exp = tables("__exp_data")
    assertEquals(
      exp.take(8),
      Vector(Exp.invln2N, Exp.shift, Exp.negln2hiN, Exp.negln2loN) ++ Exp.poly
    )
    assertEquals(exp.drop(14), Exp.tab.toVector)
  }

  // ---- beyond the corpus

  /* Random arguments against `StrictMath` (fdlibm, under 1 ulp from the exact result), a check that no branch of the
   * port is far off where the corpus and the bulk comparison did not look. musl's worst cases are at most 0.54 ulp
   * (musl 1.2.5 `log.c:51,105-108`, `exp.c:125-126`, `pow.c:14-18`), so the two are less than 1.54 ulps apart: one
   * step of the bit pattern within a binade, three across a power of two.
   */
  private def steps(a: Double, b: Double): Long =
    def ordered(x: Double) =
      val u = bitsOf(x)
      if u < 0 then Long.MinValue - u else u
    Math.abs(ordered(a) - ordered(b))

  property("log is within three steps of StrictMath") {
    val args = Gen.oneOf(
      Gen.choose(0.0, 1.0),
      Gen.choose(0.9, 1.1),
      Gen.choose(-300.0, 300.0).map(Math.pow(10, _)),
      Gen.long.map(fromBits).filter(x => !x.isNaN && x > 0)
    )
    forAll(args)(x => Prop(steps(MuslMath.log(x), StrictMath.log(x)) <= 3) :| s"log($x)")
  }

  property("exp is within three steps of StrictMath") {
    forAll(Gen.choose(-745.0, 709.7))(x =>
      Prop(steps(MuslMath.exp(x), StrictMath.exp(x)) <= 3) :| s"exp($x)"
    )
  }

  property("pow is within three steps of StrictMath") {
    forAll(Gen.choose(1e-3, 3.0), Gen.choose(-100.0, 100.0)) { (x, y) =>
      Prop(steps(MuslMath.pow(x, y), StrictMath.pow(x, y)) <= 3) :| s"pow($x, $y)"
    }
  }
