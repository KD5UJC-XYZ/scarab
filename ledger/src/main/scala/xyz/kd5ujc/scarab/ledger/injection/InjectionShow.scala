// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.injection.InjectionJson.{End, Item, Scalar}
import xyz.kd5ujc.scarab.ledger.json.Scientific

/** Haskell `show` of the elements json-stream's refusals print: the derived `Show Element`
  * (`Data/JsonStream/TokenParser.hs:12-22`) over Char8 `ByteString` (bytestring 0.12.2.0
  * `Data/ByteString/Internal/Type.hs:330-331`), aeson 2.2.5.0 `Value`
  * (`Data/Aeson/Types/Internal.hs:382-395`), scientific 0.3.8.1 `Scientific`
  * (`Data/Scientific.hs:958-1001,1078-1111`) and `CLong`, as installed with GHC 9.10.3. Rendering
  * happens only when a message is requested.
  */
private[injection] object InjectionShow:
  def item(value: Item): String = value match
    case Item.ArrayBegin      => "ArrayBegin"
    case Item.ObjectBegin     => "ObjectBegin"
    case Item.ArrayEnd(rest)  => "ArrayEnd " + bytes(rest)
    case Item.JValue(value)   => "JValue " + scalar(value)
    case Item.JInteger(value) => "JInteger " + signed(value)

  def end(value: End): String = value match
    case End.ArrayEnd(rest)  => "ArrayEnd " + bytes(rest)
    case End.ObjectEnd(rest) => "ObjectEnd " + bytes(rest)

  // The field of a derived constructor is shown at precedence 11.
  private def scalar(value: Scalar): String = value match
    case Scalar.Null          => "Null"
    case Scalar.Bool(b)       => if b then "(Bool True)" else "(Bool False)"
    case Scalar.Number(value) => "(Number " + scientific(value) + ")"

  // `showSignedInt` (ghc-internal 9.1003.0 `GHC/Internal/Show.hs:450-453`) at precedence 11.
  private def signed(value: Long): String = if value < 0 then s"($value)" else value.toString

  // `showsPrec 11`: a negative coefficient is parenthesised above `prefixMinusPrec` 6.
  private def scientific(value: Scientific): String =
    if value.coefficient < 0 then
      "(-" + positive(Scientific(-value.coefficient, value.base10Exponent)) + ")"
    else positive(value)

  // `fmtAsGeneric . toDecimalDigits`; the exponent arithmetic wraps as the reference's `Int`.
  // Zero has exponent 0, so `fmtAsExponent`'s zero case is never reached.
  private def positive(value: Scientific): String =
    val (digits, exponent) =
      if value.coefficient == 0 then ("0", 0L)
      else
        val normal = value.normalize
        val text = normal.coefficient.toString
        (text, text.length + normal.base10Exponent)
    if exponent < 0 || exponent > 7 then
      if digits.length == 1 then s"$digits.0e${exponent - 1}"
      else s"${digits.head}.${digits.tail}e${exponent - 1}"
    else if exponent == 0 then "0." + digits
    else
      val width = exponent.toInt
      val whole = digits.take(width).padTo(width, '0')
      val fraction = digits.drop(width)
      whole + "." + (if fraction.isEmpty then "0" else fraction)

  private val asciiTab = Vector(
    "NUL",
    "SOH",
    "STX",
    "ETX",
    "EOT",
    "ENQ",
    "ACK",
    "BEL",
    "BS",
    "HT",
    "LF",
    "VT",
    "FF",
    "CR",
    "SO",
    "SI",
    "DLE",
    "DC1",
    "DC2",
    "DC3",
    "DC4",
    "NAK",
    "SYN",
    "ETB",
    "CAN",
    "EM",
    "SUB",
    "ESC",
    "FS",
    "GS",
    "RS",
    "US"
  )

  // `showList` of the bytes as Latin-1 characters: `showLitString`, `showLitChar` and
  // `protectEsc` (ghc-internal 9.1003.0 `GHC/Internal/Show.hs:375-437`).
  private def bytes(value: Bytes): String =
    def at(i: Int): Int = if i < value.size then value(i) & 255 else '"'
    def char(i: Int): String = at(i) match
      case 34  => "\\\""
      case 92  => "\\\\"
      case 127 => "\\DEL"
      case 7   => "\\a"
      case 8   => "\\b"
      case 12  => "\\f"
      case 10  => "\\n"
      case 13  => "\\r"
      case 9   => "\\t"
      case 11  => "\\v"
      case 14  => if at(i + 1) == 'H' then "\\SO\\&" else "\\SO"
      case c if c > 127 =>
        val digit = at(i + 1) >= '0' && at(i + 1) <= '9'
        "\\" + c.toString + (if digit then "\\&" else "")
      case c if c >= 32 => c.toChar.toString
      case c            => "\\" + asciiTab(c)
    (0 until value.size).iterator.map(char).mkString("\"", "", "\"")
