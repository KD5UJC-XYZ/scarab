// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import xyz.kd5ujc.scarab.codec.Bytes

/** The `Show` renderings of `String`, `Char` and `ByteString` that the reference's JSON values and
  * parse errors print through: ghc-internal 9.1003.0 `GHC/Internal/Show.hs` (`showLitChar` 375-388,
  * `showLitString` 392-401, `protectEsc` 425-428, `asciiTab` 431-437, `Show Char` 191-195), text
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
  private def litChar(c: Int, next: Int): String =
    if c > 0x7f then if isDec(next) then s"\\$c\\&" else s"\\$c"
    else if c == 0x7f then "\\DEL"
    else if c == '\\' then "\\\\"
    else if c >= ' ' then c.toChar.toString
    else
      c match
        case 7  => "\\a"
        case 8  => "\\b"
        case 12 => "\\f"
        case 10 => "\\n"
        case 13 => "\\r"
        case 9  => "\\t"
        case 11 => "\\v"
        case 14 => if next == 'H' then "\\SO\\&" else "\\SO"
        case _  => "\\" + asciiTab(c)

  // `showList` of `Char`: the quoted `showLitString`. The character after the last one is the
  // closing quote, which is neither a digit nor `H`.
  private def litString(points: IndexedSeq[Int]): String =
    points.indices
      .map { i =>
        val c = points(i)
        if c == '"' then "\\\"" else litChar(c, if i + 1 < points.size then points(i + 1) else -1)
      }
      .mkString("\"", "", "\"")

  /** `show` of a `Text`: its code points as a `String`. */
  def text(value: String): String = litString(value.codePoints.toArray.toIndexedSeq)

  /** `show` of a `ByteString`: each byte as the `Char` of that code. */
  def bytes(value: Bytes): String = litString(value.value.map(_ & 0xff))

  /** `show` of a `Char`. */
  def char(c: Int): String = if c == '\'' then "'\\''" else s"'${litChar(c, -1)}'"
