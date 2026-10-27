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
