// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** aeson 2.2.5.0 `scanStringLiteral` (`Decoding/ByteString.hs:132-166`) and its
  * `Internal/Unescape.hs` text >= 2 branch (`55-400`): raw controls are forbidden, UTF-8 is strict
  * (well-formed UTF-8 only, as the JVM decoder and the reference's automaton both accept), and
  * escaped surrogates must form a scalar pair. Every UTF-8 or escape failure is the reference's one
  * `UnicodeException`.
  */
private[json] object NativeJsonString:
  import NativeJson.{Error, byte}

  private def utf8(input: Bytes): Either[Error, String] =
    Either
      .catchOnly[CharacterCodingException] {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(input.toArray))
          .toString
      }
      .leftMap(_ => Error.InvalidUtf8Stream)

  private def hex(text: String, at: Int): Option[Int] =
    if text.length - at < 4 then None
    else
      text.substring(at, at + 4).foldLeft(Option(0)) { (value, c) =>
        val digit =
          if c >= '0' && c <= '9' then c - '0'
          else if c >= 'a' && c <= 'f' then c - 'a' + 10
          else if c >= 'A' && c <= 'F' then c - 'A' + 10
          else -1
        value.flatMap(n => Option.when(digit >= 0)(n * 16 + digit))
      }

  private def unescape(text: String): Either[Error, String] =
    @tailrec def loop(at: Int, pieces: List[String]): Either[Error, String] =
      if at == text.length then Right(pieces.reverseIterator.mkString)
      else if text.charAt(at) != '\\' then
        val slash = text.indexOf('\\', at)
        val end = if slash < 0 then text.length else slash
        loop(end, text.substring(at, end) :: pieces)
      else if text.length - at < 2 then Left(Error.InvalidUtf8Stream)
      else
        text.charAt(at + 1) match
          case '"'  => loop(at + 2, "\"" :: pieces)
          case '\\' => loop(at + 2, "\\" :: pieces)
          case '/'  => loop(at + 2, "/" :: pieces)
          case 'b'  => loop(at + 2, "\b" :: pieces)
          case 'f'  => loop(at + 2, "\f" :: pieces)
          case 'n'  => loop(at + 2, "\n" :: pieces)
          case 'r'  => loop(at + 2, "\r" :: pieces)
          case 't'  => loop(at + 2, "\t" :: pieces)
          case 'u' =>
            hex(text, at + 2) match
              case Some(high) if high >= 0xd800 && high <= 0xdbff =>
                if text.length - at >= 12 && text.charAt(at + 6) == '\\' && text.charAt(
                    at + 7
                  ) == 'u'
                then
                  hex(text, at + 8) match
                    case Some(low) if low >= 0xdc00 && low <= 0xdfff =>
                      loop(at + 12, s"${high.toChar}${low.toChar}" :: pieces)
                    case _ => Left(Error.InvalidUtf8Stream)
                else Left(Error.InvalidUtf8Stream)
              case Some(low) if low >= 0xdc00 && low <= 0xdfff => Left(Error.InvalidUtf8Stream)
              case Some(value) => loop(at + 6, value.toChar.toString :: pieces)
              case None        => Left(Error.InvalidUtf8Stream)
          case _ => Left(Error.InvalidUtf8Stream)
    loop(0, Nil)

  def read(input: Bytes, start: Int): Either[Error, (JsonText, Int)] =
    @tailrec def end(at: Int, escaped: Boolean): Either[Error, Int] =
      byte(input, at) match
        case None               => Left(Error.StringEnd)
        case Some(_) if escaped => end(at + 1, false)
        case Some(34)           => Right(at)
        case Some(92)           => end(at + 1, true)
        case Some(c) if c < 32  => Left(Error.StringControl)
        case Some(_)            => end(at + 1, false)
    for
      close <- end(start + 1, false)
      text <- utf8(input.slice(start + 1, close))
      value <- unescape(text)
    yield (JsonText.pack(value), close + 1)
