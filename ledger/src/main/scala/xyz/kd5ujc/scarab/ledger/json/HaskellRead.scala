// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import scala.annotation.tailrec

/** `readEither` of a `Word64`, a `Natural` and an `Integer`, through which cardano-prelude 0.2.2.0
  * reads them from a string of canonical JSON (`FromJSON Word64`, `FromJSON Integer` and
  * `FromJSON Natural` are `parseJSString (readEither . Text.unpack)`,
  * `Cardano/Prelude/Json/Canonical.hs:114-121`), and so a Byron genesis balance
  * (`Cardano/Chain/Common/Lovelace.hs:117-118`, cardano-ledger f649f975) and most of the Byron
  * genesis parameters (#734).
  *
  * `Read Word64` reads an `Integer` and takes it modulo 2^64 (`fromInteger`); `Read Natural` reads
  * an `Integer` and keeps it only if it is not negative (ghc-internal 9.1003.0
  * `GHC/Internal/Read.hs`, so `-0` is 0). The `Integer` is `readNumber convertInt` (ghc-internal
  * 9.1003.0 `GHC/Internal/Read.hs`): any number of parentheses around an optional `-` lexeme and a
  * number lexeme, whitespace (`isSpace`) before each lexeme and at the end. A number lexeme
  * (`Text/Read/Lex.hs`, `lexNumber`) is `0x` /`0X` and hex digits, `0o` /`0O` and octal digits, or
  * decimal digits; a decimal with a fraction or an exponent is lexed but is not an integer
  * (`numberToInteger`), and so is refused, as is anything after the lexemes. `+`, a binary literal,
  * an underscore and a second `-` are refused. Measured against the reference with
  * `scripts/oracle/byron-genesis-balances` (#729) and `scripts/oracle/byron-genesis-data` (#734).
  */
object HaskellRead:
  private val Modulus = BigInt(1) << 64

  /** `Data.Char.isSpace` (ghc-internal 9.1003.0 `GHC/Internal/Unicode.hs`): space, `\t` to `\r` and
    * no-break space in Latin-1, the `Space` general category above it.
    */
  private def isSpace(c: Int): Boolean =
    if c <= 0x377 then c == ' ' || (c >= 0x09 && c <= 0x0d) || c == 0xa0
    else Character.getType(c) == Character.SPACE_SEPARATOR

  /** `isSymbolChar` of the lexer for ASCII (`Text/Read/Lex.hs`): a `-` followed by one of these is
    * a longer symbol, not the minus sign.
    */
  private def isSymbol(c: Int): Boolean = "!@#$%&*+./<=>?\\^|:-~".indexOf(c) >= 0

  private def digit(c: Int, base: Int): Option[Int] =
    Option(Character.digit(c, base)).filter(d => d >= 0 && c < 0x80)

  @tailrec private def spaces(s: IndexedSeq[Int], i: Int): Int =
    if i < s.size && isSpace(s(i)) then spaces(s, i + 1) else i

  @tailrec private def digits(
      s: IndexedSeq[Int],
      i: Int,
      base: Int,
      acc: BigInt,
      n: Int
  ): (BigInt, Int, Int) =
    if i < s.size then
      digit(s(i), base) match
        case Some(d) => digits(s, i + 1, base, acc * base + d, n + 1)
        case None    => (acc, i, n)
    else (acc, i, n)

  /** The number lexeme at `i`, if it is an integer: its value and the index after it. */
  private def number(s: IndexedSeq[Int], i: Int): Option[(BigInt, Int)] =
    def at(j: Int) = if j < s.size then s(j) else -1
    val hexOct =
      Option.when(at(i) == '0')(at(i + 1)).flatMap {
        case 'x' | 'X' => Some(16)
        case 'o' | 'O' => Some(8)
        case _         => None
      }
    val based = hexOct.flatMap(base =>
      val (v, end, n) = digits(s, i + 2, base, BigInt(0), 0)
      Option.when(n > 0)((v, end))
    )
    based.orElse {
      val (v, end, n) = digits(s, i, 10, BigInt(0), 0)
      // a fraction (`.` and a digit) or an exponent (`e`, an optional sign, a digit) makes the
      // lexeme a decimal that is not an integer
      def isDigitAt(j: Int) = digit(at(j), 10).isDefined
      val fraction = at(end) == '.' && isDigitAt(end + 1)
      val exponent = (at(end) == 'e' || at(end) == 'E') &&
        (isDigitAt(end + 1) || ((at(end + 1) == '+' || at(end + 1) == '-') && isDigitAt(end + 2)))
      Option.when(n > 0 && !fraction && !exponent)((v, end))
    }

  @tailrec private def opening(s: IndexedSeq[Int], i: Int, depth: Int): (Int, Int) =
    val j = spaces(s, i)
    if j < s.size && s(j) == '(' then opening(s, j + 1, depth + 1) else (j, depth)

  @tailrec private def closing(s: IndexedSeq[Int], i: Int, depth: Int): Option[Int] =
    if depth == 0 then Some(i)
    else
      val j = spaces(s, i)
      if j < s.size && s(j) == ')' then closing(s, j + 1, depth - 1) else None

  /** The `Word64` that `readEither` reads from `text`, or `None` where it refuses it. */
  def word64(text: String): Option[BigInt] = integer(text).map(_.mod(Modulus))

  /** The `Natural` that `readEither` reads from `text`, or `None` where it refuses it. */
  def natural(text: String): Option[BigInt] = integer(text).filter(_ >= 0)

  /** The `Integer` that `readEither` reads from `text`, or `None` where it refuses it. */
  def integer(text: String): Option[BigInt] =
    val s = text.codePoints.toArray.toIndexedSeq
    val (start, depth) = opening(s, 0, 0)
    val negative =
      start < s.size && s(start) == '-' && !(start + 1 < s.size && isSymbol(s(start + 1)))
    val numberAt = if negative then spaces(s, start + 1) else start
    for
      (value, end) <- number(s, numberAt)
      after <- closing(s, end, depth)
      if spaces(s, after) == s.size
    yield if negative then -value else value
