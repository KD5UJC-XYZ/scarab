// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx
import xyz.kd5ujc.scarab.ledger.json.{JsonValue, Scientific}

class InjectionDecimalSuite extends FunSuite:
  private def value(text: String, chunkSize: Int): JsonValue =
    val raw = Bytes.fromArray(s"{\"n\":$text}".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    @tailrec def read(step: InjectionJson.Step, rest: Bytes): JsonValue = step match
      case InjectionJson.Step.NeedData(input) if rest.nonEmpty =>
        read(input.feed(rest.take(chunkSize)).pull, rest.drop(chunkSize))
      case InjectionJson.Step.Entry("n", value, _) => value
      case other => fail(s"expected completed coefficient, got $other")
    read(InjectionJson.initial.pull, raw)

  // The stored pair, observed field by field: `Scientific` equality would normalize it.
  private def number(text: String, chunkSize: Int): (BigInt, Long) = value(text, chunkSize) match
    case JsonValue.Number(n) => (n.coefficient, n.base10Exponent)
    case other               => fail(s"not a number: $other")

  private def exactInteger(text: String, chunkSize: Int): Unit =
    assertEquals(number(text, chunkSize), (BigInt(text), 0L))

  test("integer coefficients are exact around large decimal boundaries") {
    val sizes =
      Vector(1023, 1024, 1025, 10239, 10240, 10241, 20480, 20481, 32767, 32768, 32769, 65535, 65536,
        65537, 163839, 163840, 163841)
    sizes.foreach { size =>
      val digits = "1234567890" * (size / 10) + "1234567890".take(size % 10)
      Vector(digits, "-" + digits, "+000" + digits, "0" * size).foreach { text =>
        exactInteger(text, 65536)
      }
    }
  }

  test("signs, leading zeros and fractions give the reference's coefficient and exponent") {
    val long = "1234567890" * 2048
    val cases = Vector(
      "007" -> (BigInt(7), 0L),
      "-0" -> (BigInt(0), 0L),
      "-000" -> (BigInt(0), 0L),
      "+0" -> (BigInt(0), 0L),
      "1.5e1" -> (BigInt(15), 0L),
      "-00120.0e1" -> (BigInt(-1200), 0L),
      ("0" * 20000) -> (BigInt(0), 0L),
      ("-" + "0" * 20000) -> (BigInt(0), 0L),
      ("+" + "0" * 20000 + long) -> (BigInt(long), 0L),
      ("-" + "0" * 20000 + long + ".00e2") -> (-BigInt(s"${long}00"), 0L)
    )
    cases.foreach { (input, expected) =>
      assertEquals(number(input, input.length + 6), expected, input.take(40))
    }
  }

  test("exponents at the ends of the machine Int are kept as parsed") {
    val cases = Vector(
      "-000123e9223372036854775807" -> (BigInt(-123), Long.MaxValue),
      "-0e9223372036854775807" -> (BigInt(0), Long.MaxValue),
      "+00e-9223372036854775807" -> (BigInt(0), -Long.MaxValue)
    )
    cases.foreach { (input, expected) =>
      assertEquals(number(input, input.length + 6), expected, input)
    }
  }

  test("fraction digits and wrapped Scientific exponents remain unnormalized") {
    val digits = "1" * 32768
    val cases = Vector(
      s"$digits.00" -> (BigInt(s"${digits}00"), -2L),
      s"-$digits.00e18446744073709551616" -> (-BigInt(s"${digits}00"), -2L),
      s"$digits.00e9223372036854775808" -> (BigInt(s"${digits}00"), 9223372036854775806L)
    )
    cases.foreach { (input, expected) =>
      assertEquals(number(input, 65536), expected)
    }
  }

  test("largest terminating fixed-chunk coefficient and larger single chunk stay stack safe") {
    PreprodDeepTx.onSmallStack {
      exactInteger("1" * 327674, 65536)
      // This API accepts a larger chunk; no new product digit bound is introduced.
      exactInteger("1" * 524288, 524300)
    }
  }

  test("the root-scalar workload reaches the complete lexer coefficient before it is ignored") {
    val digits = "1" * 327679
    val raw = Bytes.fromArray((digits + " ").getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    @tailrec def read(step: InjectionLexer.Step, rest: Bytes): JsonValue = step match
      case InjectionLexer.Step.NeedData(input) if rest.nonEmpty =>
        read(InjectionLexer.pull(input.feed(rest.take(65536))), rest.drop(65536))
      case InjectionLexer.Step.Emit(InjectionLexer.Token.Value(scalar), _) => scalar.json
      case other => fail(s"expected complete root coefficient, got $other")
    val result = read(InjectionLexer.pull(InjectionLexer.initial), raw)
    assertEquals(result, JsonValue.Number(Scientific(BigInt(digits), 0)))
  }

  test("the repeated-coefficient workload constructs every returned array value") {
    val digits = "1" * 262145
    val raw = Vector.fill(8)(digits).mkString("[", ",", "]")
    value(raw, 65536) match
      case JsonValue.Array(values) =>
        assertEquals(values, Vector.fill(8)(JsonValue.Number(Scientific(BigInt(digits), 0))))
      case other => fail(s"not an array: $other")
  }

  private val integerCases = Vector(
    "zero" -> BigInt(0),
    "one" -> BigInt(1),
    "negative" -> BigInt(-1234567),
    "trailing-zeros" -> BigInt("1234000000000000000000000000000000000000000000"),
    "long-min" -> BigInt(Long.MinValue),
    "below-long" -> (BigInt(Long.MinValue) - 1),
    "long-max" -> BigInt(Long.MaxValue),
    "above-long" -> (BigInt(Long.MaxValue) + 1),
    "34digits" -> BigInt("1234567890123456789012345678901234"),
    "35digits" -> BigInt("12345678901234567890123456789012345"),
    "native262145" -> BigInt("1" * 262145),
    "boundary262144" -> BigInt("1" * 262144),
    "negative327674" -> -BigInt("1" * 327674),
    "large-trailing-zeros" -> BigInt("123456789" + "0" * 32768)
  )
  integerCases.foreach { (name, coefficient) =>
    test(s"an integer is its own coefficient with exponent zero: $name") {
      val text = coefficient.toString
      // One supplied chunk; separate fixed-size-feed controls keep the native partial-number
      // boundary.
      assertEquals(number(text, text.length + 6), (coefficient, 0L))
    }
  }

  // `parseNumber` (`CLexer.hs:130-172`) as written: the whole and fraction digits folded one at a
  // time into an Integer, combined as `num * 10^frdigits + frac` with exponent `texp - frdigits`;
  // the exponent folds in the 64-bit `Int` and wraps.
  private def referenceNumber(text: String): Option[(BigInt, Long)] =
    def sign(at: Int): (Int, Int) =
      if at < text.length && text(at) == '+' then (1, at + 1)
      else if at < text.length && text(at) == '-' then (-1, at + 1)
      else (1, at)
    @tailrec def digits(at: Int, value: BigInt, count: Int): (BigInt, Int, Int) =
      if at < text.length && text(at) >= '0' && text(at) <= '9' then
        digits(at + 1, value * 10 + (text(at) - '0'), count + 1)
      else (value, count, at)
    @tailrec def int(at: Int, value: Long): (Long, Int) =
      if at < text.length && text(at) >= '0' && text(at) <= '9' then
        int(at + 1, value * 10 + (text(at) - '0'))
      else (value, at)
    val (csign, r1) = sign(0)
    val (num, numdigits, r2) = digits(r1, 0, 0)
    val (frac, frdigits, r3) =
      if r2 < text.length && text(r2) == '.' then digits(r2 + 1, 0, 0) else (BigInt(0), 0, r2)
    val (texp, rest) =
      if r3 < text.length && (text(r3) == 'e' || text(r3) == 'E') then
        val (esign, r4) = sign(r3 + 1)
        val (dnum, r5) = int(r4, 0)
        (dnum * esign, r5)
      else (0L, r3)
    Option.when(numdigits != 0 && rest == text.length)(
      (csign * (num * BigInt(10).pow(frdigits) + frac), texp - frdigits)
    )

  private def general(text: String): Option[(BigInt, Long)] =
    InjectionLexer.generalNumber(text).map(n => (n.coefficient, n.base10Exponent))

  test("midpoint coefficients equal the reference's one-pass digit fold at the split boundaries") {
    val random = new scala.util.Random(499L)
    def digitsOf(n: Int): String = Iterator.fill(n)(('0' + random.nextInt(10)).toChar).mkString
    val widths = Vector(10239, 10240, 10241, 20479, 20480, 20481, 40959, 40960, 40961)
    val shapes = widths.flatMap { width =>
      val half = width / 2
      Vector(
        digitsOf(width),
        // The right half begins with zeros, or is all zeros, at the midpoint split.
        digitsOf(half) + "000" + digitsOf(width - half - 3),
        "1" + "0" * (width - 1),
        digitsOf(half) + "0" * (width - half),
        "0" * half + digitsOf(width - half)
      )
    }
    val inputs = shapes.flatMap { digits =>
      val cut = digits.length / 3
      Vector(
        digits,
        s"-${digits}e9223372036854775807",
        s"-${digits.take(cut)}.${digits.drop(cut)}e-17",
        s"+${digits.take(cut)}.${digits.drop(cut)}E+18446744073709551616"
      )
    }
    inputs.foreach(text => assertEquals(general(text), referenceNumber(text), text.take(40)))
  }

  test("midpoint coefficients equal the reference's one-pass digit fold on random inputs") {
    val random = new scala.util.Random(0x499L)
    val alphabet = "0123456789+-.eE"
    // Short strings over the lexer's number characters also reach parseNumber's refusals.
    val syntax = Vector.fill(2000)(
      Iterator.fill(random.nextInt(12))(alphabet(random.nextInt(alphabet.length))).mkString
    )
    val long = Vector.fill(40) {
      val width = 1 + random.nextInt(40000)
      val digits = Iterator
        .fill(width)(if random.nextInt(4) == 0 then '0' else ('0' + random.nextInt(10)).toChar)
        .mkString
      val cut = random.nextInt(width + 1)
      val sign = Vector("", "-", "+")(random.nextInt(3))
      val fraction =
        if random.nextBoolean() then s"${digits.take(cut)}.${digits.drop(cut)}" else digits
      val exponent = if random.nextBoolean() then s"e${random.nextLong()}" else ""
      sign + (if fraction.startsWith(".") then "0" + fraction else fraction) + exponent
    }
    (syntax ++ long).foreach(text =>
      assertEquals(general(text), referenceNumber(text), text.take(40))
    )
    assert(syntax.exists(referenceNumber(_).isEmpty) && syntax.exists(referenceNumber(_).nonEmpty))
  }
