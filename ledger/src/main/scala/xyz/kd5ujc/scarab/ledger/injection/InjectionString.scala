// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** json-stream's text-2 unescape path (`Data/JsonStream/Unescape.hs`). Raw control characters are
  * preserved; escapes and UTF-8 scalar/surrogate validity follow that reader.
  */
private[injection] object InjectionString:
  private def utf8(bytes: Bytes): Either[InjectionJson.Error, String] =
    Either
      .catchOnly[CharacterCodingException] {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toArray))
          .toString
      }
      .leftMap(_ => InjectionJson.Error.StringEncoding)

  private def hex(text: String, start: Int): Option[Int] =
    if start + 4 > text.length then None
    else
      text.substring(start, start + 4).foldLeft(Option(0)) { (n, c) =>
        val digit =
          if c >= '0' && c <= '9' then c - '0'
          else if c >= 'a' && c <= 'f' then c - 'a' + 10
          else if c >= 'A' && c <= 'F' then c - 'A' + 10
          else -1
        n.flatMap(v => Option.when(digit >= 0)(v * 16 + digit))
      }

  def read(bytes: Bytes): Either[InjectionJson.Error, String] = utf8(bytes).flatMap { text =>
    @tailrec def loop(at: Int, pieces: List[String]): Either[InjectionJson.Error, String] =
      if at == text.length then Right(pieces.reverseIterator.mkString)
      else if text.charAt(at) != '\\' then
        val slash = text.indexOf('\\', at)
        val end = if slash < 0 then text.length else slash
        loop(end, text.substring(at, end) :: pieces)
      else if at + 1 == text.length then Left(InjectionJson.Error.StringEscape)
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
                if at + 7 < text.length && text.charAt(at + 6) == '\\' && text.charAt(at + 7) == 'u'
                then
                  hex(text, at + 8) match
                    case Some(low) if low >= 0xdc00 && low <= 0xdfff =>
                      loop(at + 12, s"${high.toChar}${low.toChar}" :: pieces)
                    case _ => Left(InjectionJson.Error.StringSurrogate)
                else Left(InjectionJson.Error.StringSurrogate)
              case Some(low) if low >= 0xdc00 && low <= 0xdfff =>
                Left(InjectionJson.Error.StringSurrogate)
              case Some(value) => loop(at + 6, value.toChar.toString :: pieces)
              case None        => Left(InjectionJson.Error.StringEscape)
          case _ => Left(InjectionJson.Error.StringEscape)
    loop(0, Nil)
  }
