// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import lab.cbor.Bytes

/** Bounded JSON reader for original reference exports. Preserves number lexemes and rejects
  * duplicate object names before any projection; no floating-point conversion or new dependency.
  */
private[lab] object ReferenceJson:
  enum Json:
    case Obj(fields: Map[String, Json])
    case Arr(values: Vector[Json])
    case Str(value: String)
    case Num(lexeme: String)
    case Lit(value: String)
  import Json.*
  def parse(raw: Bytes): Json =
    require(raw.size <= 4194304, "reference JSON exceeds byte bound")
    val text = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(raw.toArray))
      .toString
    val parser = new Parser(text)
    val result = parser.value(0)
    parser.space()
    require(parser.at == text.length, "trailing JSON data")
    result
  def field(root: Json, path: String*): Json = path.foldLeft(root) { (value, key) =>
    value match
      case Obj(fields) =>
        fields.getOrElse(key, throw new IllegalArgumentException("missing JSON field: " + key))
      case _ => throw new IllegalArgumentException("JSON object required")
  }
  def uint(value: Json): BigInt = value match
    case Num(n) if n.matches("0|[1-9][0-9]{0,38}") => BigInt(n)
    case _ => throw new IllegalArgumentException("bounded canonical unsigned JSON integer required")
  def string(value: Json): String = value match
    case Str(s) => s
    case _      => throw new IllegalArgumentException("JSON string required")
  def array(value: Json): Vector[Json] = value match
    case Arr(xs) => xs
    case _       => throw new IllegalArgumentException("JSON array required")
  private final class Parser(text: String):
    var at = 0
    private var nodes = 0
    private def peek: Char = if at < text.length then text(at) else 0.toChar
    def space(): Unit = while at < text.length && " \t\r\n".contains(text(at)) do at += 1
    private def take(c: Char): Unit =
      require(peek == c, "invalid JSON punctuation")
      at += 1
    private def quoted(): String =
      take('"')
      val out = new StringBuilder
      while peek != '"' do
        require(at < text.length && peek >= ' ', "invalid JSON string")
        val c = peek
        at += 1
        if c != '\\' then out.append(c)
        else
          require(at < text.length, "truncated JSON escape")
          val escaped = peek
          at += 1
          escaped match
            case '"'  => out.append('"')
            case '\\' => out.append('\\')
            case '/'  => out.append('/')
            case 'b'  => out.append('\b')
            case 'f'  => out.append('\f')
            case 'n'  => out.append('\n')
            case 'r'  => out.append('\r')
            case 't'  => out.append('\t')
            case 'u' =>
              require(at + 4 <= text.length, "truncated JSON unicode escape")
              val digits = text.substring(at, at + 4)
              require(
                digits.forall(c => "0123456789abcdefABCDEF".contains(c)),
                "invalid JSON unicode escape"
              )
              out.append(Integer.parseInt(digits, 16).toChar)
              at += 4
            case _ => throw new IllegalArgumentException("invalid JSON escape")
        require(out.length <= 1048576, "JSON string exceeds bound")
      take('"')
      val s = out.toString
      var i = 0
      while i < s.length do
        if Character.isHighSurrogate(s(i)) then
          require(i + 1 < s.length && Character.isLowSurrogate(s(i + 1)), "unpaired JSON surrogate")
          i += 2
        else
          require(!Character.isLowSurrogate(s(i)), "unpaired JSON surrogate")
          i += 1
      s
    def value(depth: Int): Json =
      nodes += 1
      require(depth <= 64 && nodes <= 200000, "JSON nesting/node bound exceeded")
      space()
      val result = peek match
        case '{' =>
          at += 1; space()
          var fields = Map.empty[String, Json]
          if peek != '}' then
            var more = true
            while more do
              val key = quoted()
              require(!fields.contains(key), "duplicate JSON field: " + key)
              space(); take(':')
              fields = fields.updated(key, value(depth + 1))
              space()
              more = peek == ','
              if more then { at += 1; space() }
          take('}')
          Obj(fields)
        case '[' =>
          at += 1; space()
          val values = Vector.newBuilder[Json]
          if peek != ']' then
            var more = true
            while more do
              values += value(depth + 1)
              space()
              more = peek == ','
              if more then at += 1
          take(']')
          Arr(values.result())
        case '"' => Str(quoted())
        case c if c == '-' || (c >= '0' && c <= '9') =>
          val start = at
          while at < text.length && "-+0123456789.eE".contains(peek) do at += 1
          val n = text.substring(start, at)
          require(
            n.length <= 128 && n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"),
            "invalid JSON number"
          )
          Num(n)
        case _ =>
          val literal = Vector("true", "false", "null")
            .find(text.startsWith(_, at))
            .getOrElse(throw new IllegalArgumentException("invalid JSON value"))
          at += literal.length
          Lit(literal)
      result
