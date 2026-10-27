// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

/** Haskell's `show` of a `String` (`GHC.Show.showLitString` and `showLitChar`, base 4.18): in
  * double quotes, `"` and `\` escaped, a character above `DEL` as its decimal code point, the
  * control characters by their names, and `\&` where an escape would otherwise run into the next
  * character. The reference shows its paths this way in traces (`show (LocalAddress path)`).
  */
object HaskellShow:
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

  /** `show s`. */
  def string(s: String): String =
    val chars = s.codePoints.toArray.toList
    val nexts = chars.drop(1).map(Option(_)) :+ None
    chars.zip(nexts).map(escape.tupled).mkString("\"", "", "\"")

  /** `showLitChar c` before `next`, inside a string (where `"` is escaped too). */
  private def escape(c: Int, next: Option[Int]): String =
    // `protectEsc`: an escape that the next character would extend is closed by `\&`
    def protect(escaped: String, continues: Int => Boolean): String =
      if next.exists(continues) then escaped + "\\&" else escaped
    c match
      case '"'           => "\\\""
      case _ if c > 0x7f => protect(s"\\$c", d => d >= '0' && d <= '9')
      case 0x7f          => "\\DEL"
      case '\\'          => "\\\\"
      case _ if c >= ' ' => Character.toString(c)
      case 0x07          => "\\a"
      case '\b'          => "\\b"
      case '\f'          => "\\f"
      case '\n'          => "\\n"
      case '\r'          => "\\r"
      case '\t'          => "\\t"
      case 0x0b          => "\\v"
      case 0x0e          => protect("\\SO", _ == 'H')
      case _             => "\\" + asciiTab(c)

  /** `showsPrec 11 n` of an `Int`: a negative number in parentheses. */
  def intArgument(n: Int): String = if n < 0 then s"($n)" else n.toString
