// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import xyz.kd5ujc.scarab.codec.HaskellShow

/** text-iso8601 0.1.1.1's `parseUTCTime` (`Data/Time/FromText.hs:148-149,513-534`), the parser of
  * aeson 2.2.5.0's `FromJSON UTCTime`, as a check: the refusal it gives, in the order it reads.
  * Reading is left to right over the code points, each step the reference's continuation.
  */
object Iso8601:
  /** What the reference's parser expected, as its messages name it. */
  enum Expected(val text: String):
    case YearStart extends Expected("-, +, or a digit")
    case YearDash extends Expected("a dash after a year part")
    case Digit extends Expected("a digit")
    case Dash extends Expected("a dash, -")
    case DaySeparator extends Expected("a day separator, T or space")
    case Colon extends Expected("a colon, :")
    case TimeZone extends Expected("timezone")
    case TimeZoneStart extends Expected("timezone: Z, +HH:MM or -HH:MM")
    case ColonOrDigit extends Expected("colon or a digit")
    case End extends Expected("end-of-input")

  enum Error:
    /** `unconsAscii_` of a character above U+007F (`FromText.hs:180-183`). */
    case NonAscii

    /** `unexpectedEOF` (`FromText.hs:207-208`). */
    case UnexpectedEnd(expected: Expected)

    /** `unexpectedChar` (`FromText.hs:210-211`): the character as it is, unescaped. */
    case UnexpectedChar(char: Int, expected: Expected)

    /** The first character of the year, shown as a `Char` (`FromText.hs:279`). */
    case UnexpectedYearStart(char: Int)

    /** `parseYear_`: fewer than four digits (`FromText.hs:292,304`). */
    case ShortYear

    /** `fromGregorianValid` refused the date (`FromText.hs:344`). */
    case InvalidDay(year: BigInt, month: Int, day: Int)

    /** `makeTimeOfDay` (`FromText.hs:414-418`); the seconds are picoseconds. */
    case InvalidTimeOfDay(hour: Int, minute: Int, picoseconds: BigInt)

    /** `makeSeconds` (`FromText.hs:436-447`). */
    case NoDecimals, OverTwelveDecimals

    /** `withResult` (`FromText.hs:489-498`). */
    case InvalidTimeZone(hour: Int, minute: Int)

    def message: String = this match
      case NonAscii             => "Non-ASCII character"
      case UnexpectedEnd(e)     => s"Unexpected end-of-input, expecting ${e.text}"
      case UnexpectedChar(c, e) => s"Unexpected '${Character.toString(c)}', expecting ${e.text}"
      case UnexpectedYearStart(c) =>
        s"Unexpected '${HaskellShow.char(c)}, expecting -, +, or a digit"
      case ShortYear                 => "expected year with at least 4 digits"
      case InvalidDay(y, m, d)       => s"invalid day:($y,$m,$d)"
      case InvalidTimeOfDay(h, m, s) => s"Invalid time of day:($h,$m,${pico(s)})"
      case NoDecimals                => "Expecting at least one decimal after a dot"
      case OverTwelveDecimals        => "Unexpectedly over twelve decimals"
      case InvalidTimeZone(h, m)     => s"Invalid TimeZone:($h,$m)"

  /** `show` of a `Pico` (`Data.Fixed` `showFixed False`): every one of its twelve decimals. */
  private def pico(p: BigInt): String =
    val (whole, frac) = (p / Pico, p % Pico)
    s"$whole.${frac.toString.reverse.padTo(12, '0').reverse}"

  private val Pico = BigInt(10).pow(12)

  private type R[A] = Either[Error, A]

  /** The text, as code points, and the position read up to. */
  private final case class Input(points: Vector[Int], at: Int):
    def next: Option[Int] = points.lift(at)
    def advance(n: Int): Input = copy(at = at + n)

  private def isDigit(c: Int) = c >= '0' && c <= '9'

  /** `unconsAscii`: the end, an ASCII character, or the refusal of any other. */
  private def ascii(in: Input, end: => Error): R[Int] =
    in.next match
      case None                => Left(end)
      case Some(c) if c < 0x80 => Right(c)
      case Some(_)             => Left(Error.NonAscii)

  private def twoDigits(in: Input): R[(Int, Input)] =
    for
      c1 <- ascii(in, Error.UnexpectedEnd(Expected.Digit))
      _ <- Either.cond(isDigit(c1), (), Error.UnexpectedChar(c1, Expected.Digit))
      c2 <- ascii(in.advance(1), Error.UnexpectedEnd(Expected.Digit))
      _ <- Either.cond(isDigit(c2), (), Error.UnexpectedChar(c2, Expected.Digit))
    yield ((c1 - '0') * 10 + (c2 - '0'), in.advance(2))

  private def skip(in: Input, char: Int, expected: Expected): R[Input] =
    ascii(in, Error.UnexpectedEnd(expected)).flatMap(c =>
      Either.cond(c == char, in.advance(1), Error.UnexpectedChar(c, expected))
    )

  /** `parseYear__`: the year and its dash. */
  private def year(in: Input): R[(BigInt, Input)] =
    ascii(in, Error.UnexpectedEnd(Expected.YearStart)).flatMap { c =>
      val (negative, from) =
        if c == '-' then (true, in.at + 1)
        else if c == '+' then (false, in.at + 1)
        else (false, in.at)
      if c != '-' && c != '+' && !isDigit(c) then Left(Error.UnexpectedYearStart(c))
      else
        // `loop`: every character it looks at passes `unconsAscii_` first
        val rest = in.points.drop(from)
        val run = rest.takeWhile(p => p < 0x80 && isDigit(p))
        val stop = rest.lift(run.size)
        val digits = run.size
        def value = BigInt(new String(run.toArray, 0, digits)) * (if negative then -1 else 1)
        stop match
          case Some(p) if p >= 0x80 => Left(Error.NonAscii)
          case _ if digits < 4      => Left(Error.ShortYear)
          case None                 => Left(Error.UnexpectedEnd(Expected.YearDash))
          case Some('-')            => Right(value -> Input(in.points, from + digits + 1))
          case Some(p)              => Left(Error.UnexpectedChar(p, Expected.YearDash))
    }

  private def leap(y: BigInt): Boolean =
    y.mod(4) == 0 && (y.mod(400) == 0 || y.mod(100) != 0)

  private def monthLength(y: BigInt, m: Int): Int = m match
    case 2              => if leap(y) then 29 else 28
    case 4 | 6 | 9 | 11 => 30
    case _              => 31

  /** `parseDay_`: `YYYY-MM-DD`, checked by `fromGregorianValid` as soon as the day is read. */
  private def day(in: Input): R[Input] =
    for
      (y, afterYear) <- year(in)
      (m, afterMonth) <- twoDigits(afterYear)
      dash <- skip(afterMonth, '-', Expected.Dash)
      (d, afterDay) <- twoDigits(dash)
      _ <- Either.cond(
        m >= 1 && m <= 12 && d >= 1 && d <= monthLength(y, m),
        (),
        Error.InvalidDay(y, m, d)
      )
    yield afterDay

  /** What follows the time of day: the end, or the next character and the text after it. */
  private enum After:
    case End
    case Next(char: Int, rest: Input)

  /** `parseTimeOfDay__`: `HH:MM[:SS[.SSS]]`, checked by `makeTimeOfDay` before what follows is
    * read.
    */
  private def timeOfDay(in: Input): R[After] =
    def checked(h: Int, m: Int, s: BigInt, after: After): R[After] =
      Either.cond(h < 24 && m < 60 && s < 61 * Pico, after, Error.InvalidTimeOfDay(h, m, s))
    def following(at: Input): R[After] = at.next match
      case None                => Right(After.End)
      case Some(c) if c < 0x80 => Right(After.Next(c, at.advance(1)))
      case Some(_)             => Left(Error.NonAscii)
    for
      (h, afterHour) <- twoDigits(in)
      colon <- skip(afterHour, ':', Expected.Colon)
      (m, afterMinute) <- twoDigits(colon)
      next <- following(afterMinute)
      result <- next match
        case After.Next(':', rest) =>
          for
            (whole, afterSeconds) <- twoDigits(rest)
            more <- following(afterSeconds)
            result <- more match
              case After.Next('.', fraction) =>
                val digits =
                  fraction.points.drop(fraction.at).takeWhile(p => p < 0x80 && isDigit(p))
                val end = fraction.advance(digits.size)
                for
                  stop <- following(end)
                  _ <- Either.cond(digits.nonEmpty, (), Error.NoDecimals)
                  _ <- Either.cond(digits.size <= 12, (), Error.OverTwelveDecimals)
                  picos = BigInt(whole) * Pico +
                    BigInt(new String(digits.toArray, 0, digits.size)) * BigInt(10).pow(
                      12 - digits.size
                    )
                  r <- checked(h, m, picos, stop)
                yield r
              case other => checked(h, m, BigInt(whole) * Pico, other)
          yield result
        case other => checked(h, m, BigInt(0), other)
    yield result

  /** `expectingEOF_`: any character left, ASCII or not, is unexpected. */
  private def end(in: Input): R[Unit] =
    in.next.fold[R[Unit]](Right(()))(c => Left(Error.UnexpectedChar(c, Expected.End)))

  /** `parseTimeZone__` after its first character. */
  private def zone(c: Int, rest: Input): R[Unit] =
    def hhmm: R[Unit] =
      def result(hh: Int, mm: Int) =
        Either.cond(hh < 24 && mm < 60, (), Error.InvalidTimeZone(hh, mm))
      twoDigits(rest).flatMap { (hh, after) =>
        after.next match
          case None                   => result(hh, 0)
          case Some(c1) if c1 >= 0x80 => Left(Error.NonAscii)
          case Some(':') =>
            twoDigits(after.advance(1)).flatMap((mm, done) =>
              end(done).flatMap(_ => result(hh, mm))
            )
          case Some(c1) if isDigit(c1) =>
            ascii(after.advance(1), Error.UnexpectedEnd(Expected.Digit)).flatMap { c2 =>
              if isDigit(c2) then
                end(after.advance(2)).flatMap(_ => result(hh, (c1 - '0') * 10 + (c2 - '0')))
              else Left(Error.UnexpectedChar(c2, Expected.Digit))
            }
          case Some(c1) => Left(Error.UnexpectedChar(c1, Expected.ColonOrDigit))
      }
    c match
      case '-' | '+' => hhmm
      case 'Z'       => end(rest)
      case _         => Left(Error.UnexpectedChar(c, Expected.TimeZoneStart))

  /** `parseUTCTime`: the refusal, if any, of the whole text. */
  def checkUtcTime(text: JsonText): Either[Error, Unit] =
    val in = Input(text.asString.codePoints.toArray.toVector, 0)
    for
      afterDay <- day(in)
      separator <- ascii(afterDay, Error.UnexpectedEnd(Expected.DaySeparator))
      _ <- Either.cond(
        separator == 'T' || separator == ' ',
        (),
        Error.UnexpectedChar(separator, Expected.DaySeparator)
      )
      after <- timeOfDay(afterDay.advance(1))
      _ <- after match
        case After.End           => Left(Error.UnexpectedEnd(Expected.TimeZone))
        case After.Next(c, rest) => zone(c, rest)
    yield ()
