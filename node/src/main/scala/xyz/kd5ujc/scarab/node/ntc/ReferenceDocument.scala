// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Eval

/** prettyprinter 1.7.2's unannotated document algebra and Wadler/Leijen layout, at the reference's
  * full-width ribbon. `Prettyprinter/Internal.hs:1925-2149` defines first-line (`layoutPretty`) and
  * indentation-sensitive (`layoutSmart`) fitting. Alternatives examine the laid-out continuation:
  * later groups make their own choices. Lazy, memoized stream tails and Eval's trampoline retain
  * that correspondence without using the JVM stack for document depth.
  */
private[ntc] object ReferenceDocument:
  enum Mode:
    case Pretty, Smart, Unbounded

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
    def render: String = renderWith(Mode.Pretty)
    def renderWith(
        mode: Mode,
        pageWidth: Int = 80,
        ribbonWidth: Option[Int] = None
    ): String = ReferenceDocument.render(this, mode, pageWidth, ribbonWidth)

  import Doc.*

  def text(value: String): Doc =
    join(
      line,
      value.split("\n", -1).toVector.map(part => if part.isEmpty then Empty else Text(part))
    )
  val line: Doc = Alternative(Line, Text(" "))
  val lineBreak: Doc = Alternative(Line, Empty)
  def join(separator: Doc, values: Iterable[Doc]): Doc =
    values.iterator
      .foldLeft(Option.empty[Doc])((done, next) => Some(done.fold(next)(_ ++ separator ++ next)))
      .getOrElse(Empty)
  def vsep(values: Iterable[Doc]): Doc = join(line, values)
  def hsep(values: Iterable[Doc]): Doc = join(text(" "), values)
  def sep(values: Iterable[Doc]): Doc = vsep(values).group
  def parens(body: Doc): Doc = text("(") ++ body ++ text(")")
  def brackets(body: Doc): Doc = text("[") ++ body ++ text("]")

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

  private enum Stream:
    case End
    case Chunk(value: String, next: Eval[Stream])
    case Break(indent: Int, flattenable: Boolean, next: Eval[Stream])

  private def width(value: String): Int = value.codePointCount(0, value.length)

  private def render(
      document: Doc,
      mode: Mode,
      pageWidth: Int,
      ribbonWidth: Option[Int]
  ): String =
    val ribbon = math.max(0, math.min(pageWidth, ribbonWidth.getOrElse(pageWidth)))
    def fits(
        remaining: Int,
        minimumIndent: Int,
        stream: Eval[Stream]
    ): Eval[Boolean] =
      if remaining < 0 && mode != Mode.Unbounded then Eval.now(false)
      else
        stream.flatMap {
          case Stream.End => Eval.now(true)
          case Stream.Chunk(value, next) =>
            Eval.defer(fits(remaining - width(value), minimumIndent, next))
          case Stream.Break(indent, flattenable, next) =>
            if !flattenable then Eval.now(false)
            else if mode == Mode.Smart && minimumIndent < indent then
              Eval.defer(fits(pageWidth - indent, minimumIndent, next))
            else Eval.now(true)
        }

    def best(lineIndent: Int, column: Int, pending: List[Frame]): Eval[Stream] = Eval.defer {
      pending match
        case Nil => Eval.now(Stream.End)
        case Frame(indent, flat, next) :: rest =>
          next match
            case Empty => best(lineIndent, column, rest)
            case Text(value) =>
              Eval.now(
                Stream.Chunk(value, best(lineIndent, column + width(value), rest).memoize)
              )
            case Line =>
              val following = best(indent, indent, rest).memoize
              following.map { after =>
                val displayedIndent = after match
                  case Stream.End | Stream.Break(_, _, _) => 0
                  case Stream.Chunk(_, _)                 => indent
                Stream.Break(displayedIndent, !flat, Eval.now(after))
              }
            case Append(left, right) =>
              best(
                lineIndent,
                column,
                Frame(indent, flat, left) :: Frame(indent, flat, right) :: rest
              )
            case Nest(amount, body) =>
              best(lineIndent, column, Frame(indent + amount, flat, body) :: rest)
            case Align(body) =>
              best(lineIndent, column, Frame(column, flat, body) :: rest)
            case Alternative(normal, flattened) =>
              best(
                lineIndent,
                column,
                Frame(indent, flat, if flat then flattened else normal) :: rest
              )
            case Group(body) if flat =>
              best(lineIndent, column, Frame(indent, true, body) :: rest)
            case Group(body) =>
              val flattened = best(lineIndent, column, Frame(indent, true, body) :: rest).memoize
              val normal = best(lineIndent, column, Frame(indent, false, body) :: rest).memoize
              val minimumIndent =
                if mode == Mode.Smart then
                  normal.map {
                    case Stream.Break(i, _, _) => math.min(i, column)
                    case _                     => column
                  }
                else Eval.now(column)
              minimumIndent.flatMap { minimum =>
                fits(math.min(pageWidth - column, lineIndent + ribbon - column), minimum, flattened)
                  .flatMap { useFlat =>
                    if useFlat then flattened else normal
                  }
              }
    }

    def collect(stream: Eval[Stream], chunks: List[String]): Eval[String] =
      stream.flatMap {
        case Stream.End                => Eval.now(chunks.reverse.mkString)
        case Stream.Chunk(value, next) => Eval.defer(collect(next, value :: chunks))
        case Stream.Break(indent, _, next) =>
          Eval.defer(collect(next, (" " * math.max(0, indent)) :: "\n" :: chunks))
      }

    collect(best(0, 0, List(Frame(0, false, document))), Nil).value
