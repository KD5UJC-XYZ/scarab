// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** aeson 2.2.5.0 `scanNumberLiteral` (`Decoding/ByteString.hs:182-273`), after the sign that
  * `tokenCase` reads (`48-50`): strict numeric grammar and machine-Int Scientific exponent
  * arithmetic, without normalizing the coefficient/exponent pair.
  */
private[json] object NativeJsonNumber:
  import NativeJson.{Error, byte}

  private def digit(c: Int): Boolean = c >= 48 && c <= 57
  @tailrec private def digits(input: Bytes, at: Int): Int =
    if byte(input, at).exists(digit) then digits(input, at + 1) else at

  // `state_start` and `state_after0` (`188-203`): the integer part; a refused byte is a `Word8`.
  private def integer(input: Bytes, at: Int): Either[Error, Int] =
    byte(input, at) match
      case None                 => Left(Error.NumberEnd)
      case Some(c) if !digit(c) => Left(Error.NumberByte(c))
      case Some(48) => Either.cond(!byte(input, at + 1).exists(digit), at + 1, Error.LeadingZero)
      case Some(_)  => Right(digits(input, at))

  // `go_dec` (`216-221`): the fraction part; a refused byte is a `Word8`.
  private def fraction(input: Bytes, at: Int): Either[Error, (Int, Int)] =
    if byte(input, at).contains(46) then
      val first = at + 1
      byte(input, first) match
        case None                 => Left(Error.NumberEnd)
        case Some(c) if !digit(c) => Left(Error.NumberByte(c))
        case Some(_) =>
          val end = digits(input, first)
          Right((end, end - first))
    else Right((at, 0))

  // `go_sci`, `go_sci_pos` and `go_sci_neg` (`235-270`): the exponent, read modulo 2^64 as
  // `fromInteger` to `Int` does; a refused byte is a `Char`.
  private def exponent(input: Bytes, at: Int): Either[Error, (Int, Long)] =
    if !byte(input, at).exists(c => c == 101 || c == 69) then Right((at, 0L))
    else
      val sign = byte(input, at + 1)
      val negative = sign.contains(45)
      val signed = negative || sign.contains(43)
      val first = at + 1 + (if signed then 1 else 0)
      byte(input, first) match
        case None                 => Left(Error.NumberEnd)
        case Some(c) if !digit(c) => Left(Error.NumberChar(c))
        case Some(_) =>
          val end = digits(input, first)
          val magnitude = input.slice(first, end).foldLeft(0L)((n, b) => n * 10 + b - 48)
          Right((end, if negative then -magnitude else magnitude))

  def read(input: Bytes, start: Int): Either[Error, (JsonValue, Int)] =
    val negative = byte(input, start).contains(45)
    val first = start + (if negative then 1 else 0)
    def ascii(from: Int, until: Int) =
      new String(input.slice(from, until).toArray, java.nio.charset.StandardCharsets.US_ASCII)
    for
      wholeEnd <- integer(input, first)
      decimal <- fraction(input, wholeEnd)
      (decimalEnd, width) = decimal
      power <- exponent(input, decimalEnd)
      (end, exponentValue) = power
      fractionText = if width == 0 then "" else ascii(wholeEnd + 1, decimalEnd)
      coefficient = BigInt((if negative then "-" else "") + ascii(first, wholeEnd) + fractionText)
    yield (JsonValue.Number(Scientific(coefficient, exponentValue - width)), end)
