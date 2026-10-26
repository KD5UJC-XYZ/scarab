// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import io.circe.Json
import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx

class InjectionDecimalSuite extends FunSuite:
  private def number(text: String, chunkSize: Int): Json =
    val raw = Bytes.fromArray(s"{\"n\":$text}".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    @tailrec def read(step: InjectionJson.Step, rest: Bytes): Json = step match
      case InjectionJson.Step.NeedData(input) if rest.nonEmpty =>
        read(input.feed(rest.take(chunkSize)).pull, rest.drop(chunkSize))
      case InjectionJson.Step.Entry("n", value, _) => value
      case other => fail(s"expected completed coefficient, got $other")
    read(InjectionJson.initial.pull, raw)

  private def exactInteger(text: String, chunkSize: Int): Unit =
    val expected = BigInt(text)
    val actual = number(text, chunkSize).asNumber.fold(fail("not a number"))(identity)
    // Circe's convenience toBigInt has a 262144-digit bound even for Json.fromBigInt.
    // Observe the complete returned coefficient uniformly, without dropping larger values.
    val observed = actual.toString
    assertEquals(BigInt(observed), expected)
    assertEquals(observed, expected.toString)

  test("canonical integer text preserves exact values around large decimal boundaries") {
    val sizes =
      Vector(1023, 1024, 1025, 32767, 32768, 32769, 65535, 65536, 65537, 163839, 163840, 163841)
    sizes.foreach { size =>
      val digits = "1234567890" * (size / 10) + "1234567890".take(size % 10)
      Vector(digits, "-" + digits, "+000" + digits, "0" * size).foreach { text =>
        exactInteger(text, 65536)
      }
    }
  }

  test("fraction digits and wrapped Scientific exponents remain unnormalized") {
    val digits = "1" * 32768
    val cases = Vector(
      s"$digits.00" -> s"${digits}00e-2",
      s"-$digits.00e18446744073709551616" -> s"-${digits}00e-2",
      s"$digits.00e9223372036854775808" -> s"${digits}00e9223372036854775806"
    )
    cases.foreach { (input, expected) =>
      val actual = number(input, 65536).asNumber.fold(fail("not a number"))(identity)
      // Compare the unnormalized coefficient/exponent representation, not numeric equality.
      val rendered = actual.toString
      val decimal = "(-?)([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?".r
      val observed = rendered match
        case decimal(sign, whole, fraction, power) =>
          val trailing = Option(fraction).getOrElse("")
          s"${BigInt(sign + whole + trailing)}e${Option(power).fold(BigInt(0))(BigInt(_)) - trailing.length}"
        case _ => fail("not a decimal representation")
      assertEquals(observed, expected)
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
    @tailrec def read(step: InjectionLexer.Step, rest: Bytes): Json = step match
      case InjectionLexer.Step.NeedData(input) if rest.nonEmpty =>
        read(InjectionLexer.pull(input.feed(rest.take(65536))), rest.drop(65536))
      case InjectionLexer.Step.Emit(InjectionLexer.Token.Scalar(value), _) => value
      case other => fail(s"expected complete root coefficient, got $other")
    val result = read(InjectionLexer.pull(InjectionLexer.initial), raw)
    assertEquals(result.asNumber.map(_.toString), Some(digits))
  }

  test("the repeated-coefficient workload constructs every returned array value") {
    val digits = "1" * 262145
    val raw = Vector.fill(8)(digits).mkString("[", ",", "]")
    val values = number(raw, 65536).asArray.fold(fail("not an array"))(identity)
    assertEquals(values.size, 8)
    values.foreach(v => assertEquals(v.asNumber.map(_.toString), Some(digits)))
  }

  private val accessorCases = Vector(
    "zero" -> BigInt(0),
    "negative-zero-normalized" -> BigInt("-0"),
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
  accessorCases.foreach { (name, coefficient) =>
    test(s"integer representation preserves complete accessors: $name") {
      val expected = Json.fromBigInt(coefficient)
      val text = coefficient.toString
      // This matrix compares constructor/accessor representations in one supplied chunk.
      // Separate fixed-size-feed controls retain the native partial-number boundary.
      val actual = number(text, text.length + 6)
      val before = expected.asNumber.fold(fail("expected number"))(identity)
      val after = actual.asNumber.fold(fail("actual number"))(identity)
      assertEquals(after.toString, before.toString)
      assertEquals(actual, expected)
      assertEquals(actual.hashCode, expected.hashCode)
      val oldDecimal = before.toBigDecimal.fold(fail("expected decimal"))(identity)
      val newDecimal = after.toBigDecimal.fold(fail("actual decimal"))(identity)
      assertEquals(newDecimal.bigDecimal.unscaledValue, coefficient.bigInteger)
      assertEquals(newDecimal.bigDecimal.scale, oldDecimal.bigDecimal.scale)
      assertEquals(newDecimal.mc, oldDecimal.mc)
      assertEquals(after.toBigInt, before.toBigInt)
      assertEquals(after.toLong, before.toLong)
    }
  }
