// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import scala.annotation.tailrec

/** Haskell's character escapes, as the textual syntax reads and writes the characters of a `string`
  * constant.
  *
  * Reading: the parser takes the characters of a string literal one at a time with megaparsec's
  * `charLiteral` (`PlutusCore/Parser/Builtin.hs:86-87`, plutus 1.70.0.0 `507cab8c`), which looks
  * ahead at most ten characters and hands them to base's `readLitChar` (`Text.Read.Lex.lexChar`),
  * then skips as many characters as that consumed. [[readLitChar]] transcribes that function on
  * such a window: an escape longer than the window is read within it, and the `\&` that `lexChar`
  * consumes after a character is consumed only while it lies within the window. Neither megaparsec
  * nor base is vendored, and the tree does not pin the versions the reference resolves: this
  * transcribes `Text.Megaparsec.Char.Lexer.charLiteral` of megaparsec 9.7.0 and
  * `Text.Read.Lex.lexChar` of base 4.18.2.1 as Hackage publishes them, and is checked against the
  * reference's own parser on the observations of `fixtures/reference/plutus-text` and on the
  * corpus.
  *
  * Writing: [[escaped]] is the body of a literal as `PrettyConst` writes it
  * (`PlutusCore/Pretty/PrettyConst.hs:112-121`): a quote and a backslash escaped, a printable
  * character as itself, any other through base's `showLitChar`.
  */
object HaskellChar:
  /** How many characters `charLiteral` looks ahead. */
  val Window: Int = 10

  private val Backslash = '\\'.toInt

  /** The single-character escapes (`lexEscChar`). */
  private val single: Map[Int, Int] = Map(
    'a'.toInt -> 7,
    'b'.toInt -> 8,
    'f'.toInt -> 12,
    'n'.toInt -> 10,
    'r'.toInt -> 13,
    't'.toInt -> 9,
    'v'.toInt -> 11,
    '\\'.toInt -> '\\'.toInt,
    '"'.toInt -> '"'.toInt,
    '\''.toInt -> '\''.toInt
  )

  /** The names of the control characters 0 to 32 (`asciiTab`, in code order). */
  private val asciiNames: Vector[String] =
    ("NUL SOH STX ETX EOT ENQ ACK BEL BS HT LF VT FF CR SO SI DLE DC1 DC2 DC3 DC4 NAK SYN ETB " +
      "CAN EM SUB ESC FS GS RS US SP").split(' ').toVector

  /** `lexAscii`'s names with their characters, `SOH` before its prefix `SO` (maximal munch). */
  private val asciiEscapes: Vector[(String, Int)] =
    (asciiNames.zipWithIndex :+ ("DEL" -> 127)).sortBy((name, _) => -name.length)

  /** The character the window starts with and the number of characters of the window it takes, none
    * when the window does not start with a character literal (`readLitChar`).
    *
    * @param window
    *   at most [[Window]] code points, at least one
    */
  def readLitChar(window: IndexedSeq[Int]): Option[(Int, Int)] =
    lexCharE(window).map((c, used) => (c, consumeEmpties(window, used)))

  // `consumeEmpties`: every `\&` that follows, greedily.
  @tailrec private def consumeEmpties(window: IndexedSeq[Int], at: Int): Int =
    if window.lift(at).contains(Backslash) && window.lift(at + 1).contains('&'.toInt) then
      consumeEmpties(window, at + 2)
    else at

  private def lexCharE(window: IndexedSeq[Int]): Option[(Int, Int)] =
    window.headOption.flatMap { c =>
      if c == Backslash then lexEsc(window, 1) else Some((c, 1))
    }

  // `lexEsc`: the four kinds of escape start with distinct characters, so at most one applies.
  private def lexEsc(window: IndexedSeq[Int], at: Int): Option[(Int, Int)] =
    window.lift(at).flatMap { c =>
      single
        .get(c)
        .map(_ -> (at + 1))
        .orElse(numeric(window, at))
        .orElse(control(window, at))
        .orElse(ascii(window, at))
    }

  private def digitValue(base: Int)(c: Int): Option[Int] =
    val value =
      if c >= '0' && c <= '9' then c - '0'
      else if c >= 'a' && c <= 'f' then c - 'a' + 10
      else if c >= 'A' && c <= 'F' then c - 'A' + 10
      else base
    Option.when(value < base)(value)

  // `lexNumeric`: `o`/`O` or `x`/`X` fixes the base (`lexBaseChar <++ return 10`), then the longest run of
  // its digits, at most `maxBound :: Char`.
  private def numeric(window: IndexedSeq[Int], at: Int): Option[(Int, Int)] =
    val (base, start) =
      if window(at) == 'o' || window(at) == 'O' then (8, at + 1)
      else if window(at) == 'x' || window(at) == 'X' then (16, at + 1)
      else (10, at)
    val digits = window.drop(start).map(digitValue(base)).takeWhile(_.isDefined).flatten
    val value = digits.foldLeft(BigInt(0))(_ * base + _)
    Option.when(digits.nonEmpty && value <= Character.MAX_CODE_POINT)(
      (value.toInt, start + digits.length)
    )

  // `lexCntrlChar`: `^` and one of `@A-Z[\]^_`.
  private def control(window: IndexedSeq[Int], at: Int): Option[(Int, Int)] =
    Option
      .when(window(at) == '^')(window.lift(at + 1))
      .flatten
      .filter(c => c >= '@' && c <= '_')
      .map(c => (c - '@', at + 2))

  private def ascii(window: IndexedSeq[Int], at: Int): Option[(Int, Int)] =
    asciiEscapes.collectFirst {
      case (name, code) if window.slice(at, at + name.length) == name.map(_.toInt) =>
        (code, at + name.length)
    }

  /** Haskell's `isPrint` (`GHC.Unicode`, base 4.18.2.1): any character but a control, format,
    * surrogate, private-use or unassigned one, or a line or paragraph separator. The JDK's Unicode
    * tables stand in for GHC's; where they differ, a character is escaped by one and not the other,
    * and reads back the same either way.
    */
  def isPrint(c: Int): Boolean = Character.getType(c) match
    case Character.CONTROL | Character.FORMAT | Character.SURROGATE | Character.PRIVATE_USE |
        Character.UNASSIGNED | Character.LINE_SEPARATOR | Character.PARAGRAPH_SEPARATOR =>
      false
    case _ => true

  /** The body of the literal of `codePoints` (`PrettyConst.hs:112-121` and base's `showLitChar`),
    * with `\&` where an escape would otherwise run into the next character: after a numeric escape
    * followed by a digit, and after `\SO` followed by `H` (`protectEsc`).
    */
  def escaped(codePoints: IndexedSeq[Int]): String =
    codePoints.indices.map { i =>
      val c = codePoints(i)
      val next = codePoints.lift(i + 1)
      if c == '"' then "\\\""
      else if c == Backslash then "\\\\"
      else if isPrint(c) then new String(Character.toChars(c))
      else if c > 127 then s"\\$c" + (if next.exists(n => n >= '0' && n <= '9') then "\\&" else "")
      else if c == 127 then "\\DEL"
      else
        c match
          case 7  => "\\a"
          case 8  => "\\b"
          case 12 => "\\f"
          case 10 => "\\n"
          case 13 => "\\r"
          case 9  => "\\t"
          case 11 => "\\v"
          case 14 => "\\SO" + (if next.contains('H'.toInt) then "\\&" else "")
          case _  => "\\" + asciiNames(c)
    }.mkString
