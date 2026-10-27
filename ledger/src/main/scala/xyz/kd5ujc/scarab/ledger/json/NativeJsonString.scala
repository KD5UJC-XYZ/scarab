// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** aeson 2.2.5.0 `Decoding/ByteString.hs:143-175` and its `Internal/Unescape.hs` text >= 2 branch:
  * raw controls are forbidden, UTF-8 is strict, and escaped surrogates must form a scalar pair.
  */
private[json] object NativeJsonString:
  import NativeJson.{Error, Expected, byte}

  private def utf8(input: Bytes, start: Int): Either[Error, String] =
    Either
      .catchOnly[CharacterCodingException] {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(input.toArray))
          .toString
      }
      .leftMap(_ => Error.StringEncoding(start))

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

  private def unescape(text: String, start: Int): Either[Error, String] =
    @tailrec def loop(at: Int, pieces: List[String]): Either[Error, String] =
      if at == text.length then Right(pieces.reverseIterator.mkString)
      else if text.charAt(at) != '\\' then
        val slash = text.indexOf('\\', at)
        val end = if slash < 0 then text.length else slash
        loop(end, text.substring(at, end) :: pieces)
      else if text.length - at < 2 then Left(Error.StringEscape(start))
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
                    case _ => Left(Error.StringSurrogate(start))
                else Left(Error.StringSurrogate(start))
              case Some(low) if low >= 0xdc00 && low <= 0xdfff => Left(Error.StringSurrogate(start))
              case Some(value) => loop(at + 6, value.toChar.toString :: pieces)
              case None        => Left(Error.StringEscape(start))
          case _ => Left(Error.StringEscape(start))
    loop(0, Nil)

  def read(input: Bytes, start: Int): Either[Error, (String, Int)] =
    @tailrec def end(at: Int, escaped: Boolean): Either[Error, Int] =
      if at == input.size then Left(Error.End(at, Expected.StringEnd))
      else if escaped then end(at + 1, false)
      else
        byte(input, at) match
          case 34          => Right(at)
          case 92          => end(at + 1, true)
          case c if c < 32 => Left(Error.StringControl(at, c))
          case _           => end(at + 1, false)
    for
      close <- end(start + 1, false)
      text <- utf8(input.slice(start + 1, close), start)
      value <- unescape(text, start)
    yield (value, close + 1)
