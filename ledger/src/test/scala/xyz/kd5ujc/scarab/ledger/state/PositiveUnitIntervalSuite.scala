// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import io.circe.{Json, parser}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.json.{FromJson, JsonKind, NumberError, RatioKey, Scientific}

/** `PositiveUnitInterval` as cardano-ledger f649f975 reads it from JSON (`BaseTypes.hs:437-531`),
  * and the aeson `FromJSON` instances the genesis numbers use.
  */
class PositiveUnitIntervalSuite extends FunSuite:
  private def json(text: String): Json = parser.parse(text).fold(e => fail(e.message), identity)
  private def read(text: String) = PositiveUnitInterval.fromJson(json(text)).map(_.rational)
  private val maxWord = BigInt(2).pow(64) - 1

  test("a decimal is accepted with at most 19 decimal places once normalized") {
    assertEquals(read("0.05"), Right(Rational(1, 20)))
    assertEquals(
      read("0.1234567890123456789"),
      Right(Rational(BigInt("1234567890123456789"), BigInt(10).pow(19)))
    )
    assertEquals(read("0.0000000000000000001"), Right(Rational(1, BigInt(10).pow(19))))
    assertEquals(read("1e-19"), Right(Rational(1, BigInt(10).pow(19))))
    // trailing zeros normalize away before the precision check
    assertEquals(read("0.10000000000000000000"), Right(Rational(1, 10)))
    assertEquals(read("5e-2"), Right(Rational(1, 20)))
    assertEquals(read("1"), Right(Rational.one))
    assertEquals(read("1.0"), Right(Rational.one))
  }

  test("20 decimal places are too precise, whatever the value") {
    assertEquals(read("0.00000000000000000001"), Left(NumberError.TooPrecise(Scientific(1, -20))))
    assertEquals(read("1e-20"), Left(NumberError.TooPrecise(Scientific(1, -20))))
    assertEquals(
      read("0.12345678901234567891"),
      Left(NumberError.TooPrecise(Scientific(BigInt("12345678901234567891"), -20)))
    )
    assertEquals(
      read("1e-20").left.map(_.message),
      Left("Value is too precise: 1.0e-20")
    )
  }

  test("a negative value is refused before its precision or bounds") {
    assertEquals(read("-1e-25"), Left(NumberError.Negative(Scientific(-1, -25))))
    assertEquals(read("-1e-25").left.map(_.message), Left("Value is negative: -1.0e-25"))
    assertEquals(read("-0.5"), Left(NumberError.Negative(Scientific(-5, -1))))
  }

  test("values outside (0, 1] and exponents above 19 are refused") {
    assertEquals(read("0"), Left(NumberError.OutsideOfBounds(Rational.zero)))
    assertEquals(read("0").left.map(_.message), Left("Value is outside of bounds: 0 % 1"))
    assertEquals(read("-0"), Left(NumberError.OutsideOfBounds(Rational.zero)))
    assertEquals(
      read("1.0000000000000000001"),
      Left(NumberError.OutsideOfBounds(Rational(BigInt(10).pow(19) + 1, BigInt(10).pow(19))))
    )
    assertEquals(read("10"), Left(NumberError.OutsideOfBounds(Rational(10))))
    assertEquals(read("1e19"), Left(NumberError.OutsideOfBounds(Rational(BigInt(10).pow(19)))))
    assertEquals(read("1e20"), Left(NumberError.TooBig(Scientific(1, 20))))
    assertEquals(read("1e20").left.map(_.message), Left("Value is too big: 1.0e20"))
  }

  test("the object form is reduced before its components are bounded to Word64") {
    assertEquals(read("""{"numerator": 1, "denominator": 20}"""), Right(Rational(1, 20)))
    assertEquals(read("""{"numerator": 2, "denominator": 40}"""), Right(Rational(1, 20)))
    assertEquals(read("""{"numerator": -1, "denominator": -2}"""), Right(Rational(1, 2)))
    assertEquals(
      read(s"""{"numerator": ${BigInt(2).pow(64)}, "denominator": ${BigInt(2).pow(65)}}"""),
      Right(Rational(1, 2))
    )
    assertEquals(read(s"""{"numerator": $maxWord, "denominator": $maxWord}"""), Right(Rational.one))
    assertEquals(read("""{"numerator": 2.0, "denominator": 4e0}"""), Right(Rational(1, 2)))
    // a third has no decimal form, but is a PositiveUnitInterval
    assertEquals(read("""{"numerator": 1, "denominator": 3}"""), Right(Rational(1, 3)))
    assertEquals(
      read(s"""{"numerator": 1, "denominator": ${BigInt(10).pow(19)}}"""),
      Right(Rational(1, BigInt(10).pow(19)))
    )
  }

  test("the object form refuses components beyond Word64, values beyond the bounds and zero") {
    val over = BigInt(2).pow(64)
    assertEquals(
      read(s"""{"numerator": 1, "denominator": $over}"""),
      Left(NumberError.OutsideOfBounds(Rational(1, over)))
    )
    assertEquals(
      read(s"""{"numerator": $maxWord, "denominator": $over}"""),
      Left(NumberError.OutsideOfBounds(Rational(maxWord, over)))
    )
    assertEquals(
      read(s"""{"numerator": 1, "denominator": ${BigInt(10).pow(19) + 1}}"""),
      Left(NumberError.OutsideOfBounds(Rational(1, BigInt(10).pow(19) + 1)))
    )
    assertEquals(
      read("""{"numerator": 3, "denominator": 2}"""),
      Left(NumberError.OutsideOfBounds(Rational(3, 2)))
    )
    assertEquals(
      read("""{"numerator": -1, "denominator": 2}""").left.map(_.message),
      Left("Value is outside of bounds: (-1) % 2")
    )
    assertEquals(read("""{"numerator": 1, "denominator": 0}"""), Left(NumberError.ZeroDenominator))
  }

  test("the object form's components are aeson Integers") {
    assertEquals(
      read("""{"numerator": 1}"""),
      Left(NumberError.MissingKey(RatioKey.Denominator))
    )
    assertEquals(
      read("""{"denominator": 1}""").left.map(_.message),
      Left("key \"numerator\" not found")
    )
    assertEquals(
      read("""{"numerator": 1.5, "denominator": 2}"""),
      Left(NumberError.InComponent(RatioKey.Numerator, NumberError.Floating(Scientific(15, -1))))
    )
    assertEquals(
      read("""{"numerator": "1", "denominator": 2}"""),
      Left(NumberError.InComponent(RatioKey.Numerator, NumberError.TypeMismatch(JsonKind.String)))
    )
    assertEquals(
      read("""{"numerator": 1, "denominator": 1e1025}"""),
      Left(
        NumberError.InComponent(
          RatioKey.Denominator,
          NumberError.ExponentOutOfBounds(Scientific(1, 1025))
        )
      )
    )
  }

  test("anything but an object or a number is a type mismatch") {
    assertEquals(read("\"0.05\""), Left(NumberError.TypeMismatch(JsonKind.String)))
    assertEquals(read("null"), Left(NumberError.TypeMismatch(JsonKind.Null)))
    assertEquals(read("[1]").left.map(_.message), Left("expected Number, but encountered Array"))
  }

  test("Word64 and NonZero Word64 accept a whole number in any notation, within the bounds") {
    def word(text: String) = FromJson.word64(json(text)).map(_.toBigInt)
    assertEquals(word("2160"), Right(BigInt(2160)))
    assertEquals(word("2160.0"), Right(BigInt(2160)))
    assertEquals(word("2.16e3"), Right(BigInt(2160)))
    assertEquals(word(maxWord.toString), Right(maxWord))
    assertEquals(word("0"), Right(BigInt(0)))
    assertEquals(
      word((maxWord + 1).toString),
      Left(NumberError.NotWord64(Scientific(maxWord + 1, 0)))
    )
    assertEquals(word("-1"), Left(NumberError.NotWord64(Scientific(-1, 0))))
    assertEquals(word("1.5"), Left(NumberError.NotWord64(Scientific(15, -1))))
    assertEquals(word("1e400"), Left(NumberError.NotWord64(Scientific(1, 400))))
    assertEquals(
      word("1.5").left.map(_.message),
      Left("parsing Word64 failed, value is either floating or will cause over or underflow 1.5")
    )
    assertEquals(FromJson.nonZeroWord64(json("0")).map(_.value.toBigInt), Left(NumberError.Zero))
    assertEquals(
      NumberError.Zero.message,
      "Encountered zero while trying to construct a NonZero value"
    )
    assertEquals(FromJson.nonZeroWord64(json("1")).map(_.value.toBigInt), Right(BigInt(1)))
  }

  test("Scientific shows as the reference's Show instance does") {
    val cases = List(
      Scientific(0, 0) -> "0.0",
      Scientific(5, -2) -> "5.0e-2",
      Scientific(1, -1) -> "0.1",
      Scientific(12345, -2) -> "123.45",
      Scientific(1234567, 0) -> "1234567.0",
      Scientific(1, 7) -> "1.0e7",
      Scientific(12345678, 0) -> "1.2345678e7",
      Scientific(100, 0) -> "100.0",
      Scientific(-1, -25) -> "-1.0e-25",
      Scientific(15, -1) -> "1.5"
    )
    cases.foreach((s, shown) => assertEquals(s.shown, shown, s.toString))
  }
