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
    if at < input.size && digit(byte(input, at)) then digits(input, at + 1) else at

  // `state_start` and `state_after0` (`188-203`): the integer part; a refused byte is a `Word8`.
  private def integer(input: Bytes, at: Int): Either[Error, Int] =
    if at == input.size then Left(Error.NumberEnd)
    else if !digit(byte(input, at)) then Left(Error.NumberByte(byte(input, at)))
    else if byte(input, at) == 48 then
      Either.cond(at + 1 == input.size || !digit(byte(input, at + 1)), at + 1, Error.LeadingZero)
    else Right(digits(input, at))

  // `go_dec` (`216-221`): the fraction part; a refused byte is a `Word8`.
  private def fraction(input: Bytes, at: Int): Either[Error, (Int, Int)] =
    if at < input.size && byte(input, at) == 46 then
      val first = at + 1
      if first == input.size then Left(Error.NumberEnd)
      else if !digit(byte(input, first)) then Left(Error.NumberByte(byte(input, first)))
      else
        val end = digits(input, first)
        Right((end, end - first))
    else Right((at, 0))

  // `go_sci`, `go_sci_pos` and `go_sci_neg` (`235-270`): the exponent, read modulo 2^64 as
  // `fromInteger` to `Int` does; a refused byte is a `Char`.
  private def exponent(input: Bytes, at: Int): Either[Error, (Int, Long)] =
    if at == input.size || byte(input, at) != 101 && byte(input, at) != 69 then Right((at, 0L))
    else
      val negative = at + 1 < input.size && byte(input, at + 1) == 45
      val signed = negative || at + 1 < input.size && byte(input, at + 1) == 43
      val first = at + 1 + (if signed then 1 else 0)
      if first == input.size then Left(Error.NumberEnd)
      else if !digit(byte(input, first)) then Left(Error.NumberChar(byte(input, first)))
      else
        val end = digits(input, first)
        val magnitude = (first until end).foldLeft(0L)((n, i) => n * 10 + byte(input, i) - 48)
        Right((end, if negative then -magnitude else magnitude))

  def read(input: Bytes, start: Int): Either[Error, (JsonValue, Int)] =
    val negative = byte(input, start) == 45
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
