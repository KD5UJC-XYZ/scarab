// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import scala.annotation.tailrec

/** The subset of prettyprinter's document algebra used by the Plutus diagnostic instances.
  * `Prettyprinter/Internal.hs` (prettyprinter 1.7.2), `group`, `flatAlt`, `encloseSep` and
  * `layoutPretty`; the reference's default page is 80 columns with ribbon fraction 1. Both fitting
  * and rendering use explicit work lists: argument nesting never consumes the JVM stack. The
  * context schemas put later groups behind line breaks or already chosen closing delimiters.
  * General term printers must extend fitting to select those later groups independently.
  */
private[ntc] object ReferenceDocument:
  enum Doc:
    case Empty
    case Text(value: String)
    case Line
    case Append(left: Doc, right: Doc)
    case Nest(amount: Int, body: Doc)
    case Align(body: Doc)
    case Alternative(normal: Doc, flattened: Doc)
    case Group(body: Doc)

    def ++(other: Doc): Doc = Append(this, other)
    def space(other: Doc): Doc = this ++ text(" ") ++ other
    def nest(amount: Int): Doc = Nest(amount, this)
    def align: Doc = Align(this)
    def group: Doc = Group(this)
    def hang(amount: Int): Doc = nest(amount).align
    def indent(amount: Int): Doc = (text(" " * amount) ++ this).hang(amount)
    def render: String = ReferenceDocument.render(this)

  import Doc.*

  def text(value: String): Doc = if value.isEmpty then Empty else Text(value)
  val line: Doc = Alternative(Line, text(" "))
  val lineBreak: Doc = Alternative(Line, Empty)
  def join(separator: Doc, values: Iterable[Doc]): Doc =
    values.iterator
      .foldLeft(Option.empty[Doc])((done, next) => Some(done.fold(next)(_ ++ separator ++ next)))
      .getOrElse(Empty)
  def vsep(values: Iterable[Doc]): Doc = join(line, values)
  def sep(values: Iterable[Doc]): Doc = vsep(values).group
  def parens(body: Doc): Doc = text("(") ++ body ++ text(")")

  private def enclose(open: String, close: String, values: Vector[Doc]): Doc =
    val left = Alternative(text(open + " "), text(open))
    val right = Alternative(text(" " + close), text(close))
    val parts = values.zipWithIndex.map { (value, index) =>
      (if index == 0 then left else text(", ")) ++ value
    }
    (values match
      case Vector()     => left ++ right
      case Vector(only) => left ++ only ++ right
      case _            => join(lineBreak, parts).group ++ right
    ).group

  def list(values: Vector[Doc]): Doc = enclose("[", "]", values).align
  def tuple(values: Vector[Doc]): Doc = enclose("(", ")", values)

  private final case class Frame(indent: Int, flattened: Boolean, document: Doc)

  private def width(value: String): Int = value.codePointCount(0, value.length)

  @tailrec private def fits(remaining: Int, pending: List[Frame]): Boolean =
    if remaining < 0 then false
    else
      pending match
        case Nil => true
        case Frame(indent, flat, document) :: rest =>
          document match
            case Empty       => fits(remaining, rest)
            case Text(value) => fits(remaining - width(value), rest)
            case Line        => !flat
            case Append(left, right) =>
              fits(remaining, Frame(indent, flat, left) :: Frame(indent, flat, right) :: rest)
            case Nest(amount, body) =>
              fits(remaining, Frame(math.max(0, indent + amount), flat, body) :: rest)
            case Align(body) => fits(remaining, Frame(indent, flat, body) :: rest)
            case Alternative(normal, flattened) =>
              fits(remaining, Frame(indent, flat, if flat then flattened else normal) :: rest)
            case Group(body) => fits(remaining, Frame(indent, flat, body) :: rest)

  private def render(document: Doc): String =
    @tailrec def loop(
        pending: List[Frame],
        column: Int,
        chunks: List[String],
        indentation: Int
    ): String =
      pending match
        case Nil => chunks.reverse.mkString
        case Frame(indent, flat, next) :: rest =>
          next match
            case Empty => loop(rest, column, chunks, indentation)
            case Text(value) =>
              loop(rest, column + width(value), value :: (" " * indentation) :: chunks, 0)
            case Line => loop(rest, indent, "\n" :: chunks, indent)
            case Append(left, right) =>
              loop(
                Frame(indent, flat, left) :: Frame(indent, flat, right) :: rest,
                column,
                chunks,
                indentation
              )
            case Nest(amount, body) =>
              loop(
                Frame(math.max(0, indent + amount), flat, body) :: rest,
                column,
                chunks,
                indentation
              )
            case Align(body) => loop(Frame(column, flat, body) :: rest, column, chunks, indentation)
            case Alternative(normal, flattened) =>
              loop(
                Frame(indent, flat, if flat then flattened else normal) :: rest,
                column,
                chunks,
                indentation
              )
            case Group(body) =>
              val flattened = Frame(indent, true, body)
              val useFlat = flat || fits(80 - column, flattened :: rest)
              loop(Frame(indent, useFlat, body) :: rest, column, chunks, indentation)
    loop(List(Frame(0, false, document)), 0, Nil, 0)
