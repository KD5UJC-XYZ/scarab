// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import io.circe.{Json, JsonNumber}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** aeson 2.2.5.0 `Decoding/ByteString.hs:197-276`: strict numeric grammar and machine-Int
  * Scientific exponent arithmetic, without normalizing the coefficient/exponent pair.
  */
private[json] object NativeJsonNumber:
  import NativeJson.{Error, Expected, byte}

  private def digit(c: Int): Boolean = c >= 48 && c <= 57
  @tailrec private def digits(input: Bytes, at: Int): Int =
    if at < input.size && digit(byte(input, at)) then digits(input, at + 1) else at

  private def required(input: Bytes, at: Int): Either[Error, Int] =
    if at == input.size then Left(Error.End(at, Expected.Digit))
    else if !digit(byte(input, at)) then Left(Error.Unexpected(at, Expected.Digit, byte(input, at)))
    else Right(digits(input, at))

  private def integer(input: Bytes, at: Int): Either[Error, Int] =
    required(input, at).flatMap { end =>
      Either.cond(byte(input, at) != 48 || end == at + 1, end, Error.LeadingZero(at))
    }

  private def fraction(input: Bytes, at: Int): Either[Error, (Int, Int)] =
    if at < input.size && byte(input, at) == 46 then
      required(input, at + 1).map(end => (end, end - at - 1))
    else Right((at, 0))

  private def exponent(input: Bytes, at: Int): Either[Error, (Int, Long)] =
    if at == input.size || byte(input, at) != 101 && byte(input, at) != 69 then Right((at, 0L))
    else
      val negative = at + 1 < input.size && byte(input, at + 1) == 45
      val signed = negative || at + 1 < input.size && byte(input, at + 1) == 43
      val first = at + 1 + (if signed then 1 else 0)
      required(input, first).map { end =>
        val magnitude = (first until end).foldLeft(0L)((n, i) => n * 10 + byte(input, i) - 48)
        (end, if negative then -magnitude else magnitude)
      }

  private def scalar(coefficient: BigInt, exponent: Long, start: Int): Either[Error, Json] =
    if exponent == 0 then Right(Json.fromBigInt(coefficient))
    else if exponent >= -Int.MaxValue.toLong && exponent <= -Int.MinValue.toLong then
      Right(Json.fromBigDecimal(BigDecimal(coefficient, (-exponent).toInt)))
    else
      JsonNumber
        .fromString(s"${coefficient}e${exponent}")
        .map(Json.fromJsonNumber)
        .toRight(Error.NumberEncoding(start))

  def read(input: Bytes, start: Int): Either[Error, (Json, Int)] =
    val negative = byte(input, start) == 45
    val first = start + (if negative then 1 else 0)
    for
      wholeEnd <- integer(input, first)
      decimal <- fraction(input, wholeEnd)
      (decimalEnd, width) = decimal
      power <- exponent(input, decimalEnd)
      (end, exponentValue) = power
      whole = new String(
        input.slice(first, wholeEnd).toArray,
        java.nio.charset.StandardCharsets.US_ASCII
      )
      fractionText =
        if width == 0 then ""
        else
          new String(
            input.slice(wholeEnd + 1, decimalEnd).toArray,
            java.nio.charset.StandardCharsets.US_ASCII
          )
      coefficient = BigInt((if negative then "-" else "") + whole + fractionText)
      value <- scalar(coefficient, exponentValue - width, start)
    yield (value, end)
