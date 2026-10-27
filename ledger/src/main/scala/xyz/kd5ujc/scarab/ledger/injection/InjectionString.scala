// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** json-stream's text-2 unescape path (`Data/JsonStream/Unescape.hs:99-466`). Raw control
  * characters are preserved; escapes and UTF-8 scalar/surrogate validity follow that reader. Every
  * refusal there is the same `DecodeError desc Nothing` (`Unescape.hs:102-105`), so `None` carries
  * no cause; the parser records whether the string arrived as one token or in parts.
  *
  * The reference unescapes and validates bytes in one pass. Decoding UTF-8 first and then
  * unescaping the text is an implementation difference with identical results, approved by the
  * owner and recorded in #474 (2026-10-27).
  */
private[injection] object InjectionString:
  private def utf8(bytes: Bytes): Option[String] =
    Either
      .catchOnly[CharacterCodingException] {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toArray))
          .toString
      }
      .toOption

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

  def read(bytes: Bytes): Option[String] = utf8(bytes).flatMap { text =>
    @tailrec def loop(at: Int, pieces: List[String]): Option[String] =
      if at == text.length then Some(pieces.reverseIterator.mkString)
      else if text.charAt(at) != '\\' then
        val slash = text.indexOf('\\', at)
        val end = if slash < 0 then text.length else slash
        loop(end, text.substring(at, end) :: pieces)
      else if at + 1 == text.length then None
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
                    case _ => None
                else None
              case Some(low) if low >= 0xdc00 && low <= 0xdfff =>
                None
              case Some(value) => loop(at + 6, value.toChar.toString :: pieces)
              case None        => None
          case _ => None
    loop(0, Nil)
  }
