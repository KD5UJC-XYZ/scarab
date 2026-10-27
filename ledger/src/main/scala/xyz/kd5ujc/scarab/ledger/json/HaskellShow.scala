// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** The `Show` renderings of `String`, `Char` and `ByteString` that the reference's JSON values and
  * parse errors print through: ghc-internal 9.1003.0 `GHC/Internal/Show.hs` (`showLitChar` 375-388,
  * `showLitString` 392-401, `protectEsc` 425-428, `asciiTab` 431-437, `Show Char` 190-194), text
  * 2.1.3 `Show Text` (`Data/Text/Show.hs:48-49`, a `String` of the code points) and bytestring
  * 0.12.2.0 `Show ByteString` (`Data/ByteString/Internal/Type.hs:330-331`, one `Char` per byte).
  */
private[json] object HaskellShow:
  private val asciiTab =
    ("NUL SOH STX ETX EOT ENQ ACK BEL BS HT LF VT FF CR SO SI " +
      "DLE DC1 DC2 DC3 DC4 NAK SYN ETB CAN EM SUB ESC FS GS RS US SP").split(' ').toVector

  private def isDec(c: Int): Boolean = c >= '0' && c <= '9'

  /** `showLitChar c`, where `next` is the first character of what follows (`protectEsc` inspects
    * it), or -1 when what follows cannot start with a digit or `H`.
    */
  private def litChar(c: Int, next: Int, out: StringBuilder): Unit =
    if c > 0x7f then
      out.append('\\').append(c)
      if isDec(next) then out.append("\\&")
    else if c == 0x7f then out.append("\\DEL")
    else if c == '\\' then out.append("\\\\")
    else if c >= ' ' then out.append(c.toChar)
    else
      c match
        case 7  => out.append("\\a")
        case 8  => out.append("\\b")
        case 12 => out.append("\\f")
        case 10 => out.append("\\n")
        case 13 => out.append("\\r")
        case 9  => out.append("\\t")
        case 11 => out.append("\\v")
        case 14 =>
          out.append("\\SO")
          if next == 'H' then out.append("\\&")
        case _ => out.append('\\').append(asciiTab(c))

  // `showList` of `Char`: the quoted `showLitString`. The character after the last one is the
  // closing quote, which is neither a digit nor `H`.
  private def litString(size: Int, at: Int => Int, out: StringBuilder): Unit =
    @tailrec def loop(i: Int): Unit =
      if i < size then
        val c = at(i)
        if c == '"' then out.append("\\\"")
        else litChar(c, if i + 1 < size then at(i + 1) else -1, out)
        loop(i + 1)
    out.append('"')
    loop(0)
    out.append('"')

  /** `show` of a `Text`: its code points as a `String`. */
  def text(value: String, out: StringBuilder): Unit =
    val points = value.codePoints.toArray
    litString(points.length, points(_), out)

  def text(value: String): String =
    val out = new StringBuilder
    text(value, out)
    out.result()

  /** `show` of a `ByteString`: each byte as the `Char` of that code. */
  def bytes(value: Bytes): String =
    val out = new StringBuilder
    litString(value.size, value(_) & 0xff, out)
    out.result()

  /** `show` of a `Char`. */
  def char(c: Int): String =
    if c == '\'' then "'\\''"
    else
      val out = new StringBuilder("'")
      litChar(c, -1, out)
      out.append('\'').result()
